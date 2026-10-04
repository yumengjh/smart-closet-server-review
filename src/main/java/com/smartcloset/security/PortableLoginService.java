package com.smartcloset.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PortableLoginService {
    public static final long LIFETIME_MS = 10 * 60 * 1000L;
    public record Created(String id, String token, long expiresAt) {}
    public record Link(String id, long createdAt, long expiresAt, Long sessionExpiresAt, String status) {}
    public record Grant(String id, String owner) {}
    public static final String SESSION_LINK = "portableLoginLinkId";

    private final JdbcTemplate jdbc;
    private final ClosetAccountService accounts;
    private final SecureRandom random = new SecureRandom();

    public PortableLoginService(JdbcTemplate jdbc, ClosetAccountService accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    @Transactional
    public Created create(String owner, String currentPassword) {
        jdbc.queryForObject("SELECT username FROM closet_user WHERE username=? FOR UPDATE", String.class, owner);
        if (accounts.mustChange(owner)) throw new IllegalStateException("请先完成初始密码修改，再创建便携登录链接。");
        if (!accounts.matches(owner, currentPassword)) throw new IllegalArgumentException("当前密码不正确。");
        long now = System.currentTimeMillis();
        jdbc.update("DELETE FROM portable_login_link WHERE expires_at_ms<? OR consumed_at_ms<? OR revoked_at_ms<?", now - 7L * 24 * 60 * 60 * 1000, now - 7L * 24 * 60 * 60 * 1000, now - 7L * 24 * 60 * 60 * 1000);
        Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM portable_login_link WHERE owner_username=? AND consumed_at_ms IS NULL AND revoked_at_ms IS NULL AND expires_at_ms>?", Integer.class, owner, now);
        if (pending != null && pending >= 5) throw new IllegalStateException("待使用链接已达到 5 个上限，请先撤销未使用的链接。");
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String id = UUID.randomUUID().toString(); long expiresAt = now + LIFETIME_MS;
        jdbc.update("INSERT INTO portable_login_link(id,owner_username,token_hash,created_at_ms,expires_at_ms) VALUES(?,?,?,?,?)",
                id, owner, hash(token), now, expiresAt);
        return new Created(id, token, expiresAt);
    }

    public List<Link> list(String owner) {
        long now = System.currentTimeMillis();
        return jdbc.query("SELECT id,created_at_ms,expires_at_ms,consumed_at_ms,revoked_at_ms FROM portable_login_link WHERE owner_username=? ORDER BY created_at_ms DESC LIMIT 20",
                (r, n) -> {
                    long expires = r.getLong("expires_at_ms"); long consumed = r.getLong("consumed_at_ms");
                    long revoked = r.getLong("revoked_at_ms");
                    String status = revoked != 0 ? "REVOKED" : consumed != 0 ? (consumed + 24 * 60 * 60 * 1000L <= now ? "EXPIRED" : "USED") : expires <= now ? "EXPIRED" : "PENDING";
                    return new Link(r.getString("id"), r.getLong("created_at_ms"), expires, consumed == 0 ? null : consumed + 24 * 60 * 60 * 1000L, status);
                }, owner);
    }

    public boolean revoke(String owner, String id) {
        return jdbc.update("UPDATE portable_login_link SET revoked_at_ms=? WHERE id=? AND owner_username=? AND revoked_at_ms IS NULL",
                System.currentTimeMillis(), id, owner) == 1;
    }

    @Transactional
    public Grant consume(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        long now = System.currentTimeMillis(); String tokenHash = hash(token);
        int consumed = jdbc.update("UPDATE portable_login_link SET consumed_at_ms=? WHERE token_hash=? AND consumed_at_ms IS NULL AND revoked_at_ms IS NULL AND expires_at_ms>?",
                now, tokenHash, now);
        if (consumed != 1) return null;
        String owner = jdbc.query("SELECT owner_username FROM portable_login_link WHERE token_hash=?", (r, n) -> r.getString(1), tokenHash).stream().findFirst().orElse(null);
        var account = owner == null ? null : accounts.find(owner);
        String id = jdbc.queryForObject("SELECT id FROM portable_login_link WHERE token_hash=?", String.class, tokenHash);
        return account != null && account.enabled() && !account.mustChangePassword() ? new Grant(id, owner) : null;
    }

    public boolean validSession(String id, String owner) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM portable_login_link l JOIN closet_user u ON u.username=l.owner_username WHERE l.id=? AND l.owner_username=? AND l.revoked_at_ms IS NULL AND l.consumed_at_ms>? AND u.enabled=1 AND u.must_change_password=0", Integer.class, id, owner, System.currentTimeMillis() - 24 * 60 * 60 * 1000L);
        return count != null && count == 1;
    }

    private static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII))); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }
}
