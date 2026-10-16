package com.smartcloset.notification;

import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * In-site notifications shown in the top-right processing centre (design 模型内存与图像向量 §8.2). A dedupe key makes
 * each event notify once (e.g. one pair of possible duplicates); extra channels (e-mail later) are plain beans.
 */
@Service
public class NotificationService {
    public record Notification(String id, String owner, String kind, String title, String body, String href, Map<String, Object> payload, long createdAt, Long readAt) {}
    static final int KEEP = 200;

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final List<NotificationChannel> channels;

    public NotificationService(JdbcTemplate db, ObjectMapper json, List<NotificationChannel> channels) { this.db = db; this.json = json; this.channels = channels; }

    /** Stores a notification unless one with the same dedupe key exists; returns whether a new one was created. */
    public boolean notify(String owner, String kind, String title, String body, String href, Map<String, Object> payload, String dedupeKey) {
        if (dedupeKey != null && exists(owner, dedupeKey)) return false;
        String id = UUID.randomUUID().toString(); long now = System.currentTimeMillis();
        try {
            db.update("INSERT INTO user_notification(id,owner_username,kind,title,body,href,payload_json,dedupe_key,created_at_ms) VALUES(?,?,?,?,?,?,?,?,?)",
                id, owner, kind, cut(title, 200), cut(body, 1000), href, payload == null ? null : json.writeValueAsString(payload), dedupeKey, now);
        } catch (DuplicateKeyException e) { return false; }
        // Keep each list short: the oldest read ones beyond KEEP are dropped.
        db.update("DELETE FROM user_notification WHERE owner_username=? AND read_at_ms IS NOT NULL AND id NOT IN (SELECT id FROM user_notification WHERE owner_username=? ORDER BY created_at_ms DESC LIMIT ?)", owner, owner, KEEP);
        var n = new Notification(id, owner, kind, title, body, href, payload == null ? Map.of() : payload, now, null);
        for (var channel : channels) {
            try { channel.deliver(n); } catch (RuntimeException e) { LoggerFactory.getLogger(getClass()).warn("notification channel {} failed: {}", channel.name(), e.getMessage()); }
        }
        return true;
    }

    public boolean exists(String owner, String dedupeKey) {
        return db.queryForObject("SELECT COUNT(*) FROM user_notification WHERE owner_username=? AND dedupe_key=?", Integer.class, owner, dedupeKey) > 0;
    }

    public List<Notification> list(String owner, int limit) {
        return db.query("SELECT id,kind,title,body,href,payload_json,created_at_ms,read_at_ms FROM user_notification WHERE owner_username=? AND dismissed_at_ms IS NULL ORDER BY created_at_ms DESC, id LIMIT ?",
            (r, i) -> new Notification(r.getString(1), owner, r.getString(2), r.getString(3), r.getString(4), r.getString(5),
                r.getString(6) == null ? Map.of() : json.readValue(r.getString(6), Map.class), r.getLong(7), (Long) r.getObject(8)), owner, Math.max(1, Math.min(100, limit)));
    }

    public int unread(String owner) { return db.queryForObject("SELECT COUNT(*) FROM user_notification WHERE owner_username=? AND read_at_ms IS NULL AND dismissed_at_ms IS NULL", Integer.class, owner); }

    /** Marks one notification read; false when it is not this user's. */
    public boolean read(String owner, String id) {
        return db.update("UPDATE user_notification SET read_at_ms=COALESCE(read_at_ms,?) WHERE owner_username=? AND id=?", System.currentTimeMillis(), owner, id) > 0;
    }

    public int readAll(String owner) { return db.update("UPDATE user_notification SET read_at_ms=? WHERE owner_username=? AND read_at_ms IS NULL", System.currentTimeMillis(), owner); }

    /** Hides one notification (also marks it read); false when it is not this user's. The row stays for its dedupe key. */
    public boolean dismiss(String owner, String id) {
        long now = System.currentTimeMillis();
        return db.update("UPDATE user_notification SET dismissed_at_ms=COALESCE(dismissed_at_ms,?), read_at_ms=COALESCE(read_at_ms,?) WHERE owner_username=? AND id=?", now, now, owner, id) > 0;
    }

    public int dismissAll(String owner) {
        long now = System.currentTimeMillis();
        return db.update("UPDATE user_notification SET dismissed_at_ms=?, read_at_ms=COALESCE(read_at_ms,?) WHERE owner_username=? AND dismissed_at_ms IS NULL", now, now, owner);
    }

    private static String cut(String s, int max) { return s == null ? "" : s.length() <= max ? s : s.substring(0, max - 1) + "…"; }
}
