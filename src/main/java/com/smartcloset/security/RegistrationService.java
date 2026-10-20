package com.smartcloset.security;

import com.smartcloset.image.ProcessingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Self registration and invitation codes. Codes are shown once; only their SHA-256 is stored. */
@Service
public class RegistrationService {
    public record Invite(String id, String note, int maxUses, int usedCount, String createdBy, long createdAtMs, long expiresAtMs,
            Long revokedAtMs, String status) {}
    public record CreatedInvite(String id, String note, int maxUses, int usedCount, String createdBy, long createdAtMs, long expiresAtMs,
            Long revokedAtMs, String status, String code) {}
    public record Registered(String username, String registeredVia) {}
    public record ModeState(AccountProperties.Registration mode, AccountProperties.Registration configuredMode, boolean overridden,
            String updatedBy, Long updatedAtMs, int activeInviteCount) {}
    public record InvitePage(List<Invite> items, long total, int page, int size) {}
    public record InviteUser(String username, long createdAtMs, boolean enabled) {}
    public record InviteDetail(Invite invite, List<InviteUser> registeredUsers) {}
    static final String MODE_KEY = "registration.mode";

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final long DAY_MS = 24 * 60 * 60 * 1000L;
    private final SecureRandom random = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final ClosetAccountService accounts;
    private final AccountProperties properties;

    public RegistrationService(JdbcTemplate jdbc, ClosetAccountService accounts, AccountProperties properties) {
        this.jdbc = jdbc; this.accounts = accounts; this.properties = properties;
    }

    /** The administrator override when present, else the deployment configuration (CLOSET_REGISTRATION). */
    public AccountProperties.Registration mode() {
        var rows = jdbc.query("SELECT setting_value FROM closet_setting WHERE setting_key=?", (r, n) -> r.getString(1), MODE_KEY);
        if (rows.isEmpty()) return properties.registration();
        try { return AccountProperties.Registration.valueOf(rows.getFirst()); } catch (IllegalArgumentException e) { return properties.registration(); }
    }

    public ModeState modeState() {
        var rows = jdbc.query("SELECT setting_value,updated_by,updated_at_ms FROM closet_setting WHERE setting_key=?",
                (r, n) -> new Object[]{r.getString(1), r.getString(2), r.getLong(3)}, MODE_KEY);
        var override = rows.isEmpty() ? null : rows.getFirst();
        return new ModeState(mode(), properties.registration(), override != null, override == null ? null : (String) override[1],
                override == null ? null : (Long) override[2], activeInviteCount());
    }

    /** {@code null} removes the override so the deployment configuration applies again. */
    @Transactional
    public ModeState setMode(AccountProperties.Registration mode, String actor) {
        jdbc.update("DELETE FROM closet_setting WHERE setting_key=?", MODE_KEY);
        if (mode != null) jdbc.update("INSERT INTO closet_setting(setting_key,setting_value,updated_by,updated_at_ms) VALUES(?,?,?,?)",
                MODE_KEY, mode.name(), actor, System.currentTimeMillis());
        return modeState();
    }

    public int activeInviteCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM registration_invite WHERE revoked_at_ms IS NULL AND expires_at_ms>? AND used_count<max_uses",
                Integer.class, System.currentTimeMillis());
    }

    /** Creates a USER that can work right away; the invite (when given or required) is consumed in the same transaction. */
    @Transactional
    public Registered register(String username, String password, String displayName, String inviteCode) {
        var mode = mode();
        if (mode == AccountProperties.Registration.CLOSED)
            throw new ProcessingException(HttpStatus.FORBIDDEN, "REGISTRATION_CLOSED", "当前未开放注册，请联系管理员开通账号。");
        boolean hasCode = inviteCode != null && !inviteCode.isBlank();
        if (mode == AccountProperties.Registration.INVITE && !hasCode)
            throw new ProcessingException(HttpStatus.BAD_REQUEST, "INVITE_REQUIRED", "注册需要邀请码。");
        try { ClosetAccountService.normalizeDisplayName(displayName); }
        catch (IllegalArgumentException e) { throw invalid(e.getMessage()); }
        if (accounts.taken(username)) throw taken();
        String inviteId = null;
        if (hasCode) {
            String hash = hash(normalize(inviteCode));
            long now = System.currentTimeMillis();
            int consumed = hash == null ? 0 : jdbc.update("UPDATE registration_invite SET used_count=used_count+1 WHERE code_hash=? AND revoked_at_ms IS NULL"
                    + " AND expires_at_ms>? AND used_count<max_uses", hash, now);
            if (consumed != 1) throw new ProcessingException(HttpStatus.BAD_REQUEST, "INVITE_INVALID", "邀请码无效、已用完或已过期。");
            inviteId = jdbc.queryForObject("SELECT id FROM registration_invite WHERE code_hash=?", String.class, hash);
        }
        String via = inviteId != null ? "INVITE" : "OPEN";
        try { accounts.create(username, password, "USER", displayName, via, inviteId, false); }
        catch (ClosetAccountService.UsernameTakenException e) { throw taken(); }
        catch (IllegalArgumentException e) { throw invalid(e.getMessage()); }
        return new Registered(username, via);
    }

    @Transactional
    public CreatedInvite createInvite(String createdBy, String note, Integer maxUses, Integer expiresInDays) {
        int uses = maxUses == null ? 1 : maxUses, days = expiresInDays == null ? 7 : expiresInDays;
        if (uses < 1 || uses > 100) throw invalid("可用次数需在 1 到 100 之间。");
        if (days < 1 || days > 90) throw invalid("有效天数需在 1 到 90 之间。");
        String cleanNote = note == null || note.isBlank() ? null : note.strip();
        if (cleanNote != null && cleanNote.length() > 100) throw invalid("备注最多 100 个字符。");
        var raw = new StringBuilder();
        for (int i = 0; i < 12; i++) raw.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        String code = raw.substring(0, 4) + "-" + raw.substring(4, 8) + "-" + raw.substring(8);
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO registration_invite(id,code_hash,note,max_uses,used_count,created_by,created_at_ms,expires_at_ms) VALUES(?,?,?,?,0,?,?,?)",
                id, hash(raw.toString()), cleanNote, uses, createdBy, now, now + days * DAY_MS);
        var invite = invite(id);
        return new CreatedInvite(invite.id(), invite.note(), invite.maxUses(), invite.usedCount(), invite.createdBy(), invite.createdAtMs(),
                invite.expiresAtMs(), invite.revokedAtMs(), invite.status(), code);
    }

    public List<Invite> invites(int limit) {
        long now = System.currentTimeMillis();
        return jdbc.query("SELECT id,note,max_uses,used_count,created_by,created_at_ms,expires_at_ms,revoked_at_ms FROM registration_invite"
                + " ORDER BY created_at_ms DESC, id LIMIT ?", (r, n) -> row(r, now), limit);
    }

    /** {@code status} filters by the derived status; ACTIVE / USED_UP / EXPIRED / REVOKED. */
    public InvitePage invitePage(String status, int page, int size) {
        long now = System.currentTimeMillis();
        String where = switch (status == null ? "" : status) {
            case "REVOKED" -> " WHERE revoked_at_ms IS NOT NULL";
            case "USED_UP" -> " WHERE revoked_at_ms IS NULL AND used_count>=max_uses";
            case "EXPIRED" -> " WHERE revoked_at_ms IS NULL AND used_count<max_uses AND expires_at_ms<=?";
            case "ACTIVE" -> " WHERE revoked_at_ms IS NULL AND used_count<max_uses AND expires_at_ms>?";
            default -> "";
        };
        var args = new java.util.ArrayList<Object>();
        if (where.contains("?")) args.add(now);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM registration_invite" + where, Long.class, args.toArray());
        var pageArgs = new java.util.ArrayList<>(args); pageArgs.add(size); pageArgs.add((long) (page - 1) * size);
        var items = jdbc.query("SELECT id,note,max_uses,used_count,created_by,created_at_ms,expires_at_ms,revoked_at_ms FROM registration_invite" + where
                + " ORDER BY created_at_ms DESC, id LIMIT ? OFFSET ?", (r, n) -> row(r, now), pageArgs.toArray());
        return new InvitePage(items, total, page, size);
    }

    public InviteDetail inviteDetail(String id) {
        var invite = invite(id);
        if (invite == null) return null;
        var users = jdbc.query("SELECT username,created_at_ms,enabled FROM closet_user WHERE invite_id=? ORDER BY created_at_ms",
                (r, n) -> new InviteUser(r.getString(1), r.getLong(2), r.getInt(3) != 0), id);
        return new InviteDetail(invite, users);
    }

    /** Changes note, capacity or deadline of an unrevoked invite; capacity cannot drop below what was used. Null fields stay. */
    @Transactional
    public Invite updateInvite(String id, String note, boolean clearNote, Integer maxUses, Long expiresAtMs) {
        var invite = invite(id);
        if (invite == null) return null;
        if (invite.revokedAtMs() != null) throw new ProcessingException(HttpStatus.CONFLICT, "INVITE_REVOKED", "已撤销的邀请码不能修改。");
        if (maxUses != null && (maxUses < Math.max(1, invite.usedCount()) || maxUses > 100)) throw invalid("可用次数需在已用次数（" + invite.usedCount() + "）到 100 之间。");
        long now = System.currentTimeMillis();
        if (expiresAtMs != null && (expiresAtMs <= now || expiresAtMs > now + 90 * DAY_MS)) throw invalid("到期时间需在现在之后 90 天以内。");
        String cleanNote = note == null || note.isBlank() ? null : note.strip();
        if (cleanNote != null && cleanNote.length() > 100) throw invalid("备注最多 100 个字符。");
        if (cleanNote != null || clearNote) jdbc.update("UPDATE registration_invite SET note=? WHERE id=?", cleanNote, id);
        if (maxUses != null) jdbc.update("UPDATE registration_invite SET max_uses=? WHERE id=?", maxUses, id);
        if (expiresAtMs != null) jdbc.update("UPDATE registration_invite SET expires_at_ms=? WHERE id=?", expiresAtMs, id);
        return invite(id);
    }

    public Invite invite(String id) {
        long now = System.currentTimeMillis();
        var rows = jdbc.query("SELECT id,note,max_uses,used_count,created_by,created_at_ms,expires_at_ms,revoked_at_ms FROM registration_invite WHERE id=?",
                (r, n) -> row(r, now), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Idempotent; returns false when the invite does not exist. */
    public boolean revokeInvite(String id) {
        if (invite(id) == null) return false;
        jdbc.update("UPDATE registration_invite SET revoked_at_ms=? WHERE id=? AND revoked_at_ms IS NULL", System.currentTimeMillis(), id);
        return true;
    }

    private static Invite row(java.sql.ResultSet r, long now) throws java.sql.SQLException {
        int max = r.getInt(3), used = r.getInt(4); long expires = r.getLong(7); Long revoked = r.getObject(8, Long.class);
        String status = revoked != null ? "REVOKED" : used >= max ? "USED_UP" : expires <= now ? "EXPIRED" : "ACTIVE";
        return new Invite(r.getString(1), r.getString(2), max, used, r.getString(5), r.getLong(6), expires, revoked, status);
    }

    /** Accepts lower case, spaces and dashes; returns the 12 code characters or null when the shape is wrong. */
    static String normalize(String code) {
        String value = code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        return value.length() == 12 && value.chars().allMatch(c -> ALPHABET.indexOf(c) >= 0) ? value : null;
    }

    private static String hash(String code) {
        if (code == null) return null;
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.US_ASCII))); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }

    private static ProcessingException invalid(String message) { return new ProcessingException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message); }
    private static ProcessingException taken() { return new ProcessingException(HttpStatus.CONFLICT, "USERNAME_TAKEN", "这个用户名已被使用，请换一个。"); }
}
