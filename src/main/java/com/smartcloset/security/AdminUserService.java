package com.smartcloset.security;

import com.smartcloset.image.ProcessingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** User administration inside 鱼搭 (我的 → 用户管理). Every write is recorded as an ADMIN_* account event. */
@Service
public class AdminUserService {
    public record Summary(String username, String displayName, String role, boolean enabled, boolean mustChangePassword, String registeredVia,
            long createdAtMs, Long lastLoginAtMs, Long deactivatedAtMs, int garmentCount) {}
    public record Page(List<Summary> items, long total, int page, int size) {}
    public record Credential(String username, String temporaryPassword) {}
    public record Reset(String username, String temporaryPassword, int revokedSessions) {}
    public record StatusResult(String username, boolean enabled, int revokedSessions) {}
    public record RoleResult(String username, String role, int revokedSessions) {}

    private final JdbcTemplate jdbc;
    private final ClosetAccountService accounts;
    private final QuotaPolicy quotas;
    private final AccountEvents events;
    public AdminUserService(JdbcTemplate jdbc, ClosetAccountService accounts, QuotaPolicy quotas, AccountEvents events) {
        this.jdbc = jdbc; this.accounts = accounts; this.quotas = quotas; this.events = events;
    }

    /** {@code q} matches username or display name. */
    public Page list(String q, String status, String role, int page, int size) {
        var where = new StringBuilder(" WHERE 1=1");
        var args = new ArrayList<Object>();
        if (q != null && !q.isBlank()) {
            String like = "%" + q.strip().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" AND (LOWER(username) LIKE ? ESCAPE '\\' OR LOWER(COALESCE(display_name,'')) LIKE ? ESCAPE '\\')"); args.add(like); args.add(like);
        }
        if ("ENABLED".equals(status) || "DISABLED".equals(status)) { where.append(" AND enabled=?"); args.add("ENABLED".equals(status) ? 1 : 0); }
        if ("ADMIN".equals(role) || "USER".equals(role)) { where.append(" AND role=?"); args.add(role); }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM closet_user" + where, Long.class, args.toArray());
        var pageArgs = new ArrayList<>(args); pageArgs.add(size); pageArgs.add((long) (page - 1) * size);
        var rows = jdbc.query("SELECT username,display_name,role,enabled,must_change_password,registered_via,created_at_ms,last_login_at_ms,deactivated_at_ms FROM closet_user"
                + where + " ORDER BY created_at_ms DESC, username LIMIT ? OFFSET ?",
                (r, n) -> new Summary(r.getString(1), r.getString(2), r.getString(3), r.getInt(4) != 0, r.getInt(5) != 0, r.getString(6), r.getLong(7),
                        r.getObject(8, Long.class), r.getObject(9, Long.class), 0), pageArgs.toArray());
        var garments = garmentCounts(rows.stream().map(Summary::username).toList());
        var items = rows.stream().map(s -> new Summary(s.username(), s.displayName(), s.role(), s.enabled(), s.mustChangePassword(), s.registeredVia(),
                s.createdAtMs(), s.lastLoginAtMs(), s.deactivatedAtMs(), garments.getOrDefault(s.username(), 0))).toList();
        return new Page(items, total, page, size);
    }

    @Transactional
    public Credential create(String actor, String username, String role, String displayName) {
        String temporary = ClosetAccountService.temporaryPassword();
        try { accounts.create(username, temporary, role == null ? "USER" : role, displayName, "ADMIN", null, true); }
        catch (ClosetAccountService.UsernameTakenException e) { throw new ProcessingException(HttpStatus.CONFLICT, "USERNAME_TAKEN", "这个用户名已被使用。"); }
        catch (IllegalArgumentException e) { throw invalid(e.getMessage()); }
        events.record(username, actor, "ADMIN_USER_CREATED", "role=" + role);
        return new Credential(username, temporary);
    }

    @Transactional
    public StatusResult setEnabled(String actor, String username, boolean enabled) {
        require(username);
        if (!enabled && username.equals(actor)) throw new ProcessingException(HttpStatus.CONFLICT, "SELF_PROTECTED", "不能停用自己的账号。");
        try {
            int revoked = accounts.setEnabled(username, enabled);
            events.record(username, actor, enabled ? "ADMIN_USER_ENABLED" : "ADMIN_USER_DISABLED", "revokedSessions=" + revoked);
            return new StatusResult(username, enabled, revoked);
        } catch (ClosetAccountService.LastAdminException e) { throw lastAdmin(e); }
    }

    @Transactional
    public RoleResult changeRole(String actor, String username, String role) {
        require(username);
        if (username.equals(actor)) throw new ProcessingException(HttpStatus.CONFLICT, "SELF_PROTECTED", "不能修改自己的角色。");
        try {
            int revoked = accounts.changeRole(username, role);
            events.record(username, actor, "ADMIN_ROLE_CHANGED", "role=" + role + ",revokedSessions=" + revoked);
            return new RoleResult(username, role, revoked);
        } catch (ClosetAccountService.LastAdminException e) { throw lastAdmin(e); }
        catch (IllegalArgumentException e) { throw invalid(e.getMessage()); }
    }

    @Transactional
    public Reset resetPassword(String actor, String username) {
        require(username);
        String temporary = ClosetAccountService.temporaryPassword();
        int revoked = accounts.resetPassword(username, temporary);
        events.record(username, actor, "ADMIN_PASSWORD_RESET", "revokedSessions=" + revoked);
        return new Reset(username, temporary, revoked);
    }

    @Transactional
    public int revokeSessions(String actor, String username) {
        require(username);
        int revoked = accounts.revokeSessions(username);
        accounts.revokePortableLinks(username);
        events.record(username, actor, "ADMIN_SESSIONS_REVOKED", "revokedSessions=" + revoked);
        return revoked;
    }

    public QuotaPolicy.Snapshot quota(String username) { require(username); return quotas.snapshot(username); }

    @Transactional
    public QuotaPolicy.Snapshot updateQuota(String actor, String username, Integer image, Integer agent) {
        require(username);
        try { quotas.setOverrides(username, new QuotaPolicy.Overrides(image, agent)); }
        catch (IllegalArgumentException e) { throw invalid(e.getMessage()); }
        events.record(username, actor, "ADMIN_QUOTA_CHANGED", "image=" + image + ",agent=" + agent);
        return quotas.snapshot(username);
    }

    public List<AccountEvents.Event> events(String username) { require(username); return events.recent(username, 50); }

    private void require(String username) {
        if (username == null || accounts.find(username) == null) throw new ProcessingException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "没有找到这个用户。");
    }

    private Map<String, Integer> garmentCounts(List<String> owners) {
        var result = new LinkedHashMap<String, Integer>();
        if (owners.isEmpty()) return result;
        String marks = String.join(",", Collections.nCopies(owners.size(), "?"));
        jdbc.query("SELECT owner_username, COUNT(*) FROM garment WHERE closet_status='ACTIVE' AND owner_username IN (" + marks + ") GROUP BY owner_username",
                r -> { result.put(r.getString(1), r.getInt(2)); }, owners.toArray());
        return result;
    }

    private static ProcessingException invalid(String message) { return new ProcessingException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message); }
    private static ProcessingException lastAdmin(RuntimeException e) { return new ProcessingException(HttpStatus.CONFLICT, "LAST_ADMIN_PROTECTED", e.getMessage()); }
}
