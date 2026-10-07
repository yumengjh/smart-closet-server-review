package com.smartcloset.security;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClosetAccountService {
    public record Account(String username, String role, boolean mustChangePassword, boolean enabled) {}
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final ClosetSessionRevoker sessions;
    public ClosetAccountService(JdbcTemplate jdbc, PasswordEncoder passwords, ClosetSessionRevoker sessions) {
        this.jdbc = jdbc; this.passwords = passwords; this.sessions = sessions;
    }

    public Account find(String username) {
        var rows = jdbc.query("SELECT username,role,must_change_password,enabled FROM closet_user WHERE username=?",
                (r,n) -> new Account(r.getString(1), r.getString(2), r.getInt(3) != 0, r.getInt(4) != 0), username);
        if (rows.isEmpty()) return null;
        return rows.getFirst();
    }
    public List<Account> list() {
        return jdbc.query("SELECT username,role,must_change_password,enabled FROM closet_user ORDER BY username",
                (r,n) -> new Account(r.getString(1), r.getString(2), r.getInt(3) != 0, r.getInt(4) != 0));
    }
    public void create(String username, String rawPassword, String role) {
        validate(username, rawPassword);
        if (!List.of("ADMIN", "USER").contains(role)) throw new IllegalArgumentException("role must be ADMIN or USER");
        if (find(username) != null) throw new IllegalArgumentException("账号已存在：" + username);
        jdbc.update("INSERT INTO closet_user(username,password_hash,role,must_change_password,enabled,created_at_ms) VALUES(?,?,?,1,1,?)",
                username, passwords.encode(rawPassword), role, System.currentTimeMillis());
    }
    public void bootstrap(String username, String rawPassword) {
        if (find(username) == null) create(username, rawPassword, "ADMIN");
    }
    public boolean matches(String username, String rawPassword) {
        var hashes = jdbc.query("SELECT password_hash FROM closet_user WHERE username=? AND enabled=1", (r,n) -> r.getString(1), username);
        return !hashes.isEmpty() && passwords.matches(rawPassword, hashes.getFirst());
    }
    @Transactional
    public void changePassword(String username, String currentPassword, String newPassword) {
        validate(username, newPassword);
        if (!mustChange(username) && !matches(username, currentPassword)) throw new IllegalArgumentException("当前密码不正确。");
        jdbc.update("UPDATE closet_user SET password_hash=?,must_change_password=0 WHERE username=?",
                passwords.encode(newPassword), username);
        revokePortableLinks(username);
    }
    /** Requires a password change at next login and signs the account out everywhere. Returns revoked sessions. */
    @Transactional
    public int resetPassword(String username, String rawPassword) {
        validate(username, rawPassword);
        if (find(username) == null) throw new IllegalArgumentException("账号不存在：" + username);
        jdbc.update("UPDATE closet_user SET password_hash=?,must_change_password=1 WHERE username=?", passwords.encode(rawPassword), username);
        revokePortableLinks(username);
        return sessions.revokeAll(username);
    }
    /** Disabling revokes portable links and every persisted session. Returns revoked sessions. */
    @Transactional
    public int setEnabled(String username, boolean enabled) {
        var account = find(username);
        if (account == null) throw new IllegalArgumentException("账号不存在：" + username);
        if (!enabled && account.enabled() && "ADMIN".equals(account.role()) && enabledAdmins() <= 1)
            throw new LastAdminException();
        jdbc.update("UPDATE closet_user SET enabled=? WHERE username=?", enabled ? 1 : 0, username);
        if (enabled) return 0;
        revokePortableLinks(username);
        return sessions.revokeAll(username);
    }
    /** Role changes sign the account out because authorities are cached in the session. Returns revoked sessions. */
    @Transactional
    public int changeRole(String username, String role) {
        if (!List.of("ADMIN", "USER").contains(role)) throw new IllegalArgumentException("role must be ADMIN or USER");
        var account = find(username);
        if (account == null) throw new IllegalArgumentException("账号不存在：" + username);
        if (account.role().equals(role)) return 0;
        if ("ADMIN".equals(account.role()) && account.enabled() && enabledAdmins() <= 1) throw new LastAdminException();
        jdbc.update("UPDATE closet_user SET role=? WHERE username=?", role, username);
        return sessions.revokeAll(username);
    }
    public int revokeSessions(String username) {
        if (find(username) == null) throw new IllegalArgumentException("账号不存在：" + username);
        return sessions.revokeAll(username);
    }
    private int enabledAdmins() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM closet_user WHERE role='ADMIN' AND enabled=1", Integer.class);
    }
    /** The last enabled administrator cannot be disabled or demoted. */
    public static class LastAdminException extends IllegalStateException {
        public LastAdminException() { super("不能禁用或降级最后一个启用的管理员。"); }
    }
    public boolean mustChange(String username) {
        var account = find(username);
        return account == null || account.mustChangePassword() || !account.enabled();
    }
    private void revokePortableLinks(String username) { jdbc.update("UPDATE portable_login_link SET revoked_at_ms=? WHERE owner_username=? AND revoked_at_ms IS NULL", System.currentTimeMillis(), username); }
    private void validate(String username, String password) {
        if (username == null || !username.matches("[A-Za-z0-9_.-]{2,80}"))
            throw new IllegalArgumentException("用户名需为2到80位英文字母、数字、点、下划线或短横线。");
        if (password == null || password.length() < 12 || password.length() > 200)
            throw new IllegalArgumentException("密码长度需为12到200个字符。");
    }
}
