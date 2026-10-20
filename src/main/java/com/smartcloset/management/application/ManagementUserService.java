package com.smartcloset.management.application;

import com.smartcloset.management.ConditionalOnManagementEnabled;
import com.smartcloset.management.ManagementException;
import com.smartcloset.security.AccountEvents;
import com.smartcloset.security.ClosetAccountService;
import com.smartcloset.security.LoginThrottle;
import com.smartcloset.security.PortableLoginService;
import com.smartcloset.security.QuotaPolicy;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Orchestrates core account services for the management contract; all reads are owner-agnostic SQL projections. */
@Service
@ConditionalOnManagementEnabled
public class ManagementUserService {
    public record Query(String q, String status, String role, String sort, String order, int page, int size, String registeredVia, Boolean deactivated) {
        public Query(String q, String status, String role, String sort, String order, int page, int size) { this(q, status, role, sort, order, page, size, null, null); }
    }
    public record Summary(String username, String role, boolean enabled, boolean mustChangePassword, long createdAtMs,
            Long lastActiveAtMs, int activeSessionCount, int garmentCount, int outfitCount, String displayName, String registeredVia,
            Long lastLoginAtMs, Long deactivatedAtMs) {}
    public record Page(List<Summary> items, long total, int page, int size) {}
    public record Detail(String username, String role, boolean enabled, boolean mustChangePassword, long createdAtMs,
            Long lastActiveAtMs, int activeSessionCount, Map<String, Integer> garments, int outfitCount, int outfitPlanCount,
            int quickIntakeCount, boolean hasWearerProfile, int activePortableLinkCount, QuotaPolicy.Snapshot quota, String displayName,
            String registeredVia, Long lastLoginAtMs, Long deactivatedAtMs, String inviteId, LoginThrottle.UserLock loginLock) {}
    public record Credential(String username, String temporaryPassword) {}
    public record Reset(String username, String temporaryPassword, int revokedSessions) {}
    public record StatusResult(String username, boolean enabled, int revokedSessions) {}
    public record RoleResult(String username, String role, int revokedSessions) {}
    public record Session(String sessionRef, long createdAtMs, long lastAccessAtMs, long expiresAtMs, boolean portable) {}
    public record PortableLink(String id, long createdAtMs, long expiresAtMs, Long sessionExpiresAtMs, String status) {}

    private static final Map<String, String> SORTS = Map.of("createdAt", "u.created_at_ms", "username", "u.username", "lastActiveAt", "s.last_access", "lastLoginAt", "u.last_login_at_ms");
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final ClosetAccountService accounts;
    private final PortableLoginService portableLinks;
    private final QuotaPolicy quotas;
    private final ManagementOperationLog operations;
    private final AccountEvents events;
    private final LoginThrottle throttle;

    public ManagementUserService(JdbcTemplate jdbc, ClosetAccountService accounts, PortableLoginService portableLinks,
            QuotaPolicy quotas, ManagementOperationLog operations, AccountEvents events, LoginThrottle throttle) {
        this.jdbc = jdbc; this.accounts = accounts; this.portableLinks = portableLinks; this.quotas = quotas; this.operations = operations;
        this.events = events; this.throttle = throttle;
    }

    public record Profile(String username, String displayName) {}
    @Transactional
    public Profile updateProfile(String username, String displayName) {
        require(username);
        try { accounts.updateDisplayName(username, displayName); }
        catch (IllegalArgumentException e) { throw ManagementException.invalid(e.getMessage()); }
        operations.record("USER_PROFILE_UPDATED", username, "SUCCESS", null);
        accountEvent(username, "MANAGE_PROFILE_UPDATED", null);
        return new Profile(username, accounts.displayName(username));
    }

    /** Signs one session out; {@code sessionRef} is the value listed by {@link #sessions}. */
    @Transactional
    public void revokeSession(String username, String sessionRef) {
        require(username);
        var ids = jdbc.query("SELECT PRIMARY_ID FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?", (r, n) -> r.getString(1), username);
        var match = ids.stream().filter(id -> reference(id).equals(sessionRef)).findFirst()
                .orElseThrow(() -> new ManagementException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "会话不存在", "该用户没有这个会话。"));
        jdbc.update("DELETE FROM SPRING_SESSION WHERE PRIMARY_ID=?", match);
        operations.record("USER_SESSION_REVOKED", username, "SUCCESS", "sessionRef=" + sessionRef);
        accountEvent(username, "MANAGE_SESSIONS_REVOKED", "revokedSessions=1");
    }

    public LoginThrottle.UserLock loginLock(String username) { require(username); return throttle.userLock(username); }

    @Transactional
    public LoginThrottle.UserLock clearLoginLock(String username) {
        require(username);
        throttle.clearUser(username);
        operations.record("USER_LOGIN_UNLOCKED", username, "SUCCESS", null);
        accountEvent(username, "MANAGE_LOGIN_UNLOCKED", null);
        return throttle.userLock(username);
    }

    /** Recent account events (register, login, password, profile, admin and management actions), newest first. */
    public List<AccountEvents.Event> events(String username) { require(username); return events.recent(username, 50); }

    /** Management writes also land in the user's account history so both admin surfaces show the same story. */
    private void accountEvent(String username, String action, String detail) {
        var principal = ManagementOperationLog.current();
        events.record(username, principal == null ? "manage" : "manage:" + principal.keyId() + (principal.operator() == null ? "" : ":" + principal.operator()), action, detail);
    }

    public Page list(Query query) {
        var where = new StringBuilder(" WHERE 1=1");
        var args = new ArrayList<Object>();
        if (query.q() != null && !query.q().isBlank()) {
            String pattern = "%" + like(query.q().strip().toLowerCase(java.util.Locale.ROOT)) + "%";
            where.append(" AND (LOWER(u.username) LIKE ? ESCAPE '\\' OR LOWER(COALESCE(u.display_name,'')) LIKE ? ESCAPE '\\')"); args.add(pattern); args.add(pattern);
        }
        if (query.status() != null) { where.append(" AND u.enabled=?"); args.add("ENABLED".equals(query.status()) ? 1 : 0); }
        if (query.role() != null) { where.append(" AND u.role=?"); args.add(query.role()); }
        if (query.registeredVia() != null) { where.append(" AND u.registered_via=?"); args.add(query.registeredVia()); }
        if (query.deactivated() != null) where.append(query.deactivated() ? " AND u.deactivated_at_ms IS NOT NULL" : " AND u.deactivated_at_ms IS NULL");
        String from = " FROM closet_user u LEFT JOIN (SELECT PRINCIPAL_NAME AS principal, MAX(LAST_ACCESS_TIME) AS last_access, COUNT(*) AS sessions"
                + " FROM SPRING_SESSION WHERE EXPIRY_TIME>? GROUP BY PRINCIPAL_NAME) s ON s.principal=u.username";
        var countArgs = new ArrayList<Object>(); countArgs.add(System.currentTimeMillis()); countArgs.addAll(args);
        long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, Long.class, countArgs.toArray());
        String order = "asc".equals(query.order()) ? "ASC" : "DESC";
        String sql = "SELECT u.username,u.role,u.enabled,u.must_change_password,u.created_at_ms,s.last_access,COALESCE(s.sessions,0),"
                + "u.display_name,u.registered_via,u.last_login_at_ms,u.deactivated_at_ms" + from + where
                + " ORDER BY " + SORTS.get(query.sort()) + " " + order + " NULLS LAST, u.username ASC LIMIT ? OFFSET ?";
        var pageArgs = new ArrayList<Object>(countArgs); pageArgs.add(query.size()); pageArgs.add((long) (query.page() - 1) * query.size());
        var rows = jdbc.query(sql, (r, n) -> new Object[]{r.getString(1), r.getString(2), r.getInt(3) != 0, r.getInt(4) != 0, r.getLong(5),
                (Long) r.getObject(6, Long.class), r.getInt(7), r.getString(8), r.getString(9), r.getObject(10, Long.class), r.getObject(11, Long.class)},
                pageArgs.toArray());
        var names = rows.stream().map(r -> (String) r[0]).toList();
        var garments = countBy("SELECT owner_username, COUNT(*) FROM garment WHERE closet_status='ACTIVE' AND owner_username IN (%s) GROUP BY owner_username", names);
        var outfits = countBy("SELECT owner_username, COUNT(*) FROM outfit WHERE owner_username IN (%s) GROUP BY owner_username", names);
        var items = rows.stream().map(r -> new Summary((String) r[0], (String) r[1], (Boolean) r[2], (Boolean) r[3], (Long) r[4], (Long) r[5],
                (Integer) r[6], garments.getOrDefault((String) r[0], 0), outfits.getOrDefault((String) r[0], 0), (String) r[7], (String) r[8],
                (Long) r[9], (Long) r[10])).toList();
        return new Page(items, total, query.page(), query.size());
    }

    public Detail detail(String username) {
        var account = require(username);
        var profile = accounts.profile(username);
        long now = System.currentTimeMillis();
        var session = jdbc.queryForMap("SELECT MAX(LAST_ACCESS_TIME) AS last_access, COUNT(*) AS sessions FROM SPRING_SESSION WHERE PRINCIPAL_NAME=? AND EXPIRY_TIME>?", username, now);
        var garments = new LinkedHashMap<String, Integer>();
        jdbc.query("SELECT closet_status, COUNT(*) FROM garment WHERE owner_username=? GROUP BY closet_status ORDER BY closet_status",
                r -> { garments.put(r.getString(1), r.getInt(2)); }, username);
        long activeLinks = portableLinks.list(username).stream().filter(l -> Set.of("PENDING", "USED").contains(l.status())).count();
        Number last = (Number) session.get("LAST_ACCESS");
        return new Detail(account.username(), account.role(), account.enabled(), account.mustChangePassword(), createdAt(username),
                last == null ? null : last.longValue(), ((Number) session.get("SESSIONS")).intValue(), garments,
                count("SELECT COUNT(*) FROM outfit WHERE owner_username=?", username),
                count("SELECT COUNT(*) FROM outfit_plan WHERE owner_username=?", username),
                count("SELECT COUNT(*) FROM quick_intake WHERE owner_username=?", username),
                count("SELECT COUNT(*) FROM wearer_profile WHERE owner_username=?", username) > 0,
                (int) activeLinks, quotas.snapshot(username), profile.displayName(), profile.registeredVia(), profile.lastLoginAtMs(),
                profile.deactivatedAtMs(), jdbc.queryForObject("SELECT invite_id FROM closet_user WHERE username=?", String.class, username),
                throttle.userLock(username));
    }

    @Transactional
    public Credential create(String username, String role, String password, String displayName) {
        if (accounts.taken(username))
            throw new ManagementException(HttpStatus.CONFLICT, "USER_ALREADY_EXISTS", "用户已存在", "用户 " + username + " 已存在。");
        String temporary = password == null ? temporaryPassword() : null;
        try { accounts.create(username, password == null ? temporary : password, role, displayName, "ADMIN", null, true); }
        catch (IllegalArgumentException e) { throw ManagementException.invalid(e.getMessage()); }
        operations.record("USER_CREATED", username, "SUCCESS", "role=" + role + ",generatedPassword=" + (temporary != null));
        accountEvent(username, "MANAGE_USER_CREATED", "role=" + role);
        return new Credential(username, temporary);
    }

    @Transactional
    public StatusResult setEnabled(String username, boolean enabled) {
        require(username);
        try {
            int revoked = accounts.setEnabled(username, enabled);
            operations.record(enabled ? "USER_ENABLED" : "USER_DISABLED", username, "SUCCESS", "revokedSessions=" + revoked);
            accountEvent(username, enabled ? "MANAGE_USER_ENABLED" : "MANAGE_USER_DISABLED", "revokedSessions=" + revoked);
            return new StatusResult(username, enabled, revoked);
        } catch (ClosetAccountService.LastAdminException e) { throw lastAdmin(e); }
    }

    @Transactional
    public RoleResult changeRole(String username, String role) {
        require(username);
        try {
            int revoked = accounts.changeRole(username, role);
            operations.record("USER_ROLE_CHANGED", username, "SUCCESS", "role=" + role + ",revokedSessions=" + revoked);
        accountEvent(username, "MANAGE_ROLE_CHANGED", "role=" + role + ",revokedSessions=" + revoked);
            return new RoleResult(username, role, revoked);
        } catch (ClosetAccountService.LastAdminException e) { throw lastAdmin(e); }
    }

    @Transactional
    public Reset resetPassword(String username, String password) {
        require(username);
        String temporary = password == null ? temporaryPassword() : null;
        int revoked;
        try { revoked = accounts.resetPassword(username, password == null ? temporary : password); }
        catch (IllegalArgumentException e) { throw ManagementException.invalid(e.getMessage()); }
        operations.record("USER_PASSWORD_RESET", username, "SUCCESS", "generatedPassword=" + (temporary != null) + ",revokedSessions=" + revoked);
        accountEvent(username, "MANAGE_PASSWORD_RESET", "revokedSessions=" + revoked);
        return new Reset(username, temporary, revoked);
    }

    public List<Session> sessions(String username) {
        require(username);
        return jdbc.query("SELECT s.PRIMARY_ID, s.CREATION_TIME, s.LAST_ACCESS_TIME, s.EXPIRY_TIME, (SELECT COUNT(*) FROM SPRING_SESSION_ATTRIBUTES a"
                + " WHERE a.SESSION_PRIMARY_ID=s.PRIMARY_ID AND a.ATTRIBUTE_NAME=?) FROM SPRING_SESSION s WHERE s.PRINCIPAL_NAME=? AND s.EXPIRY_TIME>?"
                + " ORDER BY s.LAST_ACCESS_TIME DESC",
                (r, n) -> new Session(reference(r.getString(1)), r.getLong(2), r.getLong(3), r.getLong(4), r.getInt(5) > 0),
                PortableLoginService.SESSION_LINK, username, System.currentTimeMillis());
    }

    @Transactional
    public int revokeSessions(String username) {
        require(username);
        int revoked = accounts.revokeSessions(username);
        operations.record("USER_SESSIONS_REVOKED", username, "SUCCESS", "revokedSessions=" + revoked);
        accountEvent(username, "MANAGE_SESSIONS_REVOKED", "revokedSessions=" + revoked);
        return revoked;
    }

    public List<PortableLink> portableLinks(String username) {
        require(username);
        return portableLinks.list(username).stream()
                .map(l -> new PortableLink(l.id(), l.createdAt(), l.expiresAt(), l.sessionExpiresAt(), l.status())).toList();
    }

    @Transactional
    public void revokePortableLink(String username, String linkId) {
        require(username);
        if (count("SELECT COUNT(*) FROM portable_login_link WHERE id=? AND owner_username=?", linkId, username) == 0)
            throw new ManagementException(HttpStatus.NOT_FOUND, "PORTABLE_LINK_NOT_FOUND", "便携登录链接不存在", "该用户没有这个便携登录链接。");
        portableLinks.revoke(username, linkId);
        operations.record("PORTABLE_LINK_REVOKED", username, "SUCCESS", "linkId=" + linkId);
    }

    public QuotaPolicy.Snapshot quota(String username) { require(username); return quotas.snapshot(username); }

    @Transactional
    public QuotaPolicy.Snapshot updateQuota(String username, Integer imageDailyLimit, Integer agentDailyLimit) {
        require(username);
        quotas.setOverrides(username, new QuotaPolicy.Overrides(imageDailyLimit, agentDailyLimit));
        operations.record("USER_QUOTA_CHANGED", username, "SUCCESS", "image=" + imageDailyLimit + ",agent=" + agentDailyLimit);
        accountEvent(username, "MANAGE_QUOTA_CHANGED", "image=" + imageDailyLimit + ",agent=" + agentDailyLimit);
        return quotas.snapshot(username);
    }

    private ClosetAccountService.Account require(String username) {
        var account = accounts.find(username);
        if (account == null) throw ManagementException.userNotFound(username);
        return account;
    }

    private static ManagementException lastAdmin(RuntimeException e) {
        return new ManagementException(HttpStatus.CONFLICT, "LAST_ADMIN_PROTECTED", "不能移除最后一个管理员", e.getMessage());
    }

    private long createdAt(String username) {
        return jdbc.queryForObject("SELECT created_at_ms FROM closet_user WHERE username=?", Long.class, username);
    }

    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }

    private Map<String, Integer> countBy(String template, List<String> owners) {
        var result = new LinkedHashMap<String, Integer>();
        if (owners.isEmpty()) return result;
        String marks = String.join(",", java.util.Collections.nCopies(owners.size(), "?"));
        jdbc.query(template.formatted(marks), r -> { result.put(r.getString(1), r.getInt(2)); }, owners.toArray());
        return result;
    }

    private static String like(String value) { return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"); }

    static String temporaryPassword() {
        var out = new StringBuilder();
        for (int i = 0; i < 20; i++) out.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return out.toString();
    }

    /** Irreversible reference so operators can tell sessions apart without learning a usable session id. */
    static String reference(String primaryId) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(primaryId.getBytes(java.nio.charset.StandardCharsets.US_ASCII))).substring(0, 16); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
