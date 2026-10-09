package com.smartcloset.management.application;

import com.smartcloset.management.ConditionalOnManagementEnabled;
import com.smartcloset.security.QuotaPolicy;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * "Active user" (design 管理接口二期 M6): a user who did something recorded — added a garment, created or edited an outfit,
 * received a recommendation, gave feedback or a behaviour signal, logged a wear, used the assistant or image credits.
 * Viewing without acting is not recorded anywhere, so this is an approximate DAU, documented as such.
 */
@Component
@ConditionalOnManagementEnabled
public class ManagementActivity {
    /** Tables with an owner and a millisecond timestamp. */
    static final List<String[]> SOURCES = List.of(
        new String[]{"garment", "created_at_ms"}, new String[]{"outfit", "created_at_ms"}, new String[]{"outfit", "updated_at_ms"},
        new String[]{"recommendation_log", "created_at_ms"}, new String[]{"recommendation_feedback", "created_at_ms"},
        new String[]{"style_signal", "created_at_ms"}, new String[]{"wear_log", "created_at_ms"}, new String[]{"model_usage", "created_at_ms"},
        new String[]{"quick_intake", "created_at_ms"});
    /** Day-level usage counters (no time of day). */
    static final List<String> DAILY_SOURCES = List.of("ai_daily_quota", "agent_daily_quota");

    public record Event(String owner, long atMs) {}
    private final JdbcTemplate jdbc;
    public ManagementActivity(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Every timestamped action in [fromMs, toMs), optionally for one user. */
    public List<Event> events(long fromMs, long toMs, String username) {
        var sql = new StringJoiner(" UNION ALL ");
        var args = new ArrayList<Object>();
        for (var s : SOURCES) {
            sql.add("SELECT owner_username, " + s[1] + " FROM " + s[0] + " WHERE " + s[1] + ">=? AND " + s[1] + "<?" + (username == null ? "" : " AND owner_username=?"));
            args.add(fromMs); args.add(toMs); if (username != null) args.add(username);
        }
        return jdbc.query(sql.toString(), (r, n) -> new Event(r.getString(1), r.getLong(2)), args.toArray());
    }

    /** Users with day-level usage (assistant messages, image credits) per day in [from, to]. */
    public Map<LocalDate, Set<String>> dailyUsage(LocalDate from, LocalDate to, String username) {
        var out = new TreeMap<LocalDate, Set<String>>();
        for (String table : DAILY_SOURCES) {
            var args = new ArrayList<Object>(List.of(java.sql.Date.valueOf(from), java.sql.Date.valueOf(to)));
            if (username != null) args.add(username);
            jdbc.query("SELECT owner_username, quota_date FROM " + table + " WHERE used>0 AND quota_date>=? AND quota_date<=?" + (username == null ? "" : " AND owner_username=?"),
                r -> { out.computeIfAbsent(r.getDate(2).toLocalDate(), d -> new HashSet<>()).add(r.getString(1)); }, args.toArray());
        }
        return out;
    }

    /** Distinct active users per bucket; {@code bucket} maps a day to its bucket start (day, week or month). */
    public Map<LocalDate, Set<String>> byDay(LocalDate from, LocalDate to, String username, Function<LocalDate, LocalDate> bucket) {
        var out = new TreeMap<LocalDate, Set<String>>();
        for (var e : events(startMs(from), startMs(to.plusDays(1)), username))
            out.computeIfAbsent(bucket.apply(day(e.atMs())), d -> new HashSet<>()).add(e.owner());
        dailyUsage(from, to, username).forEach((d, users) -> out.computeIfAbsent(bucket.apply(d), x -> new HashSet<>()).addAll(users));
        return out;
    }

    /** Distinct active users over a whole day range (e.g. last 7 days), not the sum of daily counts. */
    public int distinct(LocalDate from, LocalDate to) {
        var users = new HashSet<String>();
        events(startMs(from), startMs(to.plusDays(1)), null).forEach(e -> users.add(e.owner()));
        dailyUsage(from, to, null).values().forEach(users::addAll);
        return users.size();
    }

    /** Distinct active users per hour starting at {@code fromMs} (hour-aligned); day-level usage has no hour and is left out. */
    public long[] hourly(long fromMs, int hours) {
        var sets = new ArrayList<Set<String>>(); for (int i = 0; i < hours; i++) sets.add(new HashSet<>());
        for (var e : events(fromMs, fromMs + hours * 3_600_000L, null)) {
            int i = (int) ((e.atMs() - fromMs) / 3_600_000L);
            if (i >= 0 && i < hours) sets.get(i).add(e.owner());
        }
        return sets.stream().mapToLong(Set::size).toArray();
    }

    static long startMs(LocalDate day) { return day.atStartOfDay(QuotaPolicy.ZONE).toInstant().toEpochMilli(); }
    static LocalDate day(long ms) { return Instant.ofEpochMilli(ms).atZone(QuotaPolicy.ZONE).toLocalDate(); }
}
