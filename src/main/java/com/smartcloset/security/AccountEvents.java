package com.smartcloset.security;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Account-level history (register, login, password, profile, admin actions). Never stores passwords, codes, IPs or user agents. */
@Component
public class AccountEvents {
    static final long RETENTION_MS = 180L * 24 * 60 * 60 * 1000;
    public record Event(long occurredAtMs, String actor, String action, String detail) {}
    private final JdbcTemplate jdbc;
    public AccountEvents(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void record(String username, String actor, String action, String detail) {
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO account_event(id,occurred_at_ms,username,actor,action,detail) VALUES(?,?,?,?,?,?)",
                UUID.randomUUID().toString(), now, username, actor == null ? username : truncate(actor, 200), action, detail == null ? null : truncate(detail, 500));
        if (ThreadLocalRandom.current().nextInt(100) == 0) jdbc.update("DELETE FROM account_event WHERE occurred_at_ms<?", now - RETENTION_MS);
    }

    public List<Event> recent(String username, int limit) {
        return jdbc.query("SELECT occurred_at_ms,actor,action,detail FROM account_event WHERE username=? ORDER BY occurred_at_ms DESC, id LIMIT ?",
                (r, n) -> new Event(r.getLong(1), r.getString(2), r.getString(3), r.getString(4)), username, limit);
    }

    public record UserEvent(String id, long occurredAtMs, String username, String actor, String action, String detail) {}
    public record Page(List<UserEvent> items, long total, int page, int size) {}
    /** Site-wide audit feed for administrators, newest first; time bounds are [fromMs, toMsExclusive). */
    public Page search(String username, String action, String actor, long fromMs, long toMsExclusive, int page, int size) {
        var where = new StringBuilder(" WHERE occurred_at_ms>=? AND occurred_at_ms<?");
        var args = new java.util.ArrayList<Object>(List.of(fromMs, toMsExclusive));
        if (username != null && !username.isBlank()) { where.append(" AND username=?"); args.add(username); }
        if (action != null && !action.isBlank()) { where.append(" AND action=?"); args.add(action.strip()); }
        if (actor != null && !actor.isBlank()) { where.append(" AND actor=?"); args.add(actor.strip()); }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM account_event" + where, Long.class, args.toArray());
        var pageArgs = new java.util.ArrayList<>(args); pageArgs.add(size); pageArgs.add((long) (page - 1) * size);
        var items = jdbc.query("SELECT id,occurred_at_ms,username,actor,action,detail FROM account_event" + where + " ORDER BY occurred_at_ms DESC, id LIMIT ? OFFSET ?",
                (r, n) -> new UserEvent(r.getString(1), r.getLong(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6)), pageArgs.toArray());
        return new Page(items, total, page, size);
    }

    private static String truncate(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }
}
