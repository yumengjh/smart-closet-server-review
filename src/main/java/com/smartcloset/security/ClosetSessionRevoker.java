package com.smartcloset.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/** Lists and deletes persisted Spring Sessions of one account so credential and status changes take effect immediately. */
@Component
public class ClosetSessionRevoker {
    public static final String DEVICE_ATTRIBUTE = "closet.device";
    public record OwnSession(String sessionRef, String device, long createdAtMs, long lastAccessAtMs, long expiresAtMs, boolean portable, boolean current) {}
    private final JdbcTemplate jdbc;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;
    public ClosetSessionRevoker(JdbcTemplate jdbc, FindByIndexNameSessionRepository<? extends Session> sessions) { this.jdbc = jdbc; this.sessions = sessions; }

    /** Attributes cascade via SPRING_SESSION_ATTRIBUTES_FK. Returns the number of removed sessions. */
    public int revokeAll(String username) {
        return jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?", username);
    }

    /** Keeps the session the request is using; {@code currentSessionId} may be null to remove all. */
    public int revokeOthers(String username, String currentSessionId) {
        if (currentSessionId == null) return revokeAll(username);
        return jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME=? AND SESSION_ID<>?", username, currentSessionId);
    }

    public List<OwnSession> list(String username, String currentSessionId) {
        long now = System.currentTimeMillis();
        return sessions.findByPrincipalName(username).values().stream().filter(s -> !s.isExpired())
                .map(s -> new OwnSession(reference(s.getId()), s.getAttribute(DEVICE_ATTRIBUTE) instanceof String d ? d : "未知设备",
                        s.getCreationTime().toEpochMilli(), s.getLastAccessedTime().toEpochMilli(),
                        s.getMaxInactiveInterval().isNegative() ? Long.MAX_VALUE : s.getLastAccessedTime().plus(s.getMaxInactiveInterval()).toEpochMilli(),
                        s.getAttribute(PortableLoginService.SESSION_LINK) != null, s.getId().equals(currentSessionId)))
                .filter(s -> s.expiresAtMs() > now)
                .sorted(Comparator.comparing(OwnSession::current).reversed().thenComparing(OwnSession::lastAccessAtMs, Comparator.reverseOrder()))
                .toList();
    }

    /** Irreversible reference so a session can be told apart without revealing a usable session id. */
    static String reference(String id) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.US_ASCII))).substring(0, 16); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** "浏览器 · 系统" summary of a User-Agent; the raw header is never stored. */
    public static String device(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return "未知设备";
        String ua = userAgent;
        String browser = ua.contains("Edg/") ? "Edge" : ua.contains("OPR/") ? "Opera" : ua.contains("MicroMessenger") ? "微信"
                : ua.contains("Firefox/") ? "Firefox" : ua.contains("Chrome/") || ua.contains("CriOS/") ? "Chrome"
                : ua.contains("Safari/") ? "Safari" : "浏览器";
        String system = ua.contains("iPhone") ? "iPhone" : ua.contains("iPad") ? "iPad" : ua.contains("Android") ? "Android"
                : ua.contains("Windows") ? "Windows" : ua.contains("Mac OS X") || ua.contains("Macintosh") ? "macOS"
                : ua.contains("Linux") ? "Linux" : "未知系统";
        return browser + " · " + system;
    }
}
