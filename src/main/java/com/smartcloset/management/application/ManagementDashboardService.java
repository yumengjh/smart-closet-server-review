package com.smartcloset.management.application;

import com.smartcloset.garment.ClosetClassification;
import com.smartcloset.management.ConditionalOnManagementEnabled;
import com.smartcloset.management.ManagementProperties;
import com.smartcloset.security.QuotaPolicy;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Big-screen snapshot (design 管理接口二期 §7.1, M7): everything a wall display needs in one request, aggregates only — no
 * user names, garment names or images. Cached for dashboard-cache-ttl (15 s) so any number of screens cost one computation.
 */
@Service
@ConditionalOnManagementEnabled
public class ManagementDashboardService {
    static final int HOURS = 24, DAYS = 30;
    static final List<String> HOURLY = List.of("garments_created", "outfits_created", "recommendations_shown", "model_calls", "active_users");
    static final Map<String, String> HOURLY_SQL = Map.of(
        "garments_created", "SELECT created_at_ms FROM garment WHERE created_at_ms>=? AND created_at_ms<?",
        "outfits_created", "SELECT created_at_ms FROM outfit WHERE created_at_ms>=? AND created_at_ms<?",
        "recommendations_shown", "SELECT created_at_ms FROM recommendation_log WHERE created_at_ms>=? AND created_at_ms<?",
        "model_calls", "SELECT created_at_ms FROM model_usage WHERE created_at_ms>=? AND created_at_ms<?");

    private final JdbcTemplate jdbc;
    private final ManagementActivity activity;
    private final ManagementInsightService insights;
    private final ManagementKpiService kpi;
    private final ManagementOpsService ops;
    private final ObjectMapper json;
    private final long ttlMs;
    private volatile Map<String, Object> cached;
    private volatile long cachedAt;

    public ManagementDashboardService(JdbcTemplate jdbc, ManagementActivity activity, ManagementInsightService insights, ManagementKpiService kpi,
                                      ManagementOpsService ops, ObjectMapper json, ManagementProperties properties) {
        this.jdbc = jdbc; this.activity = activity; this.insights = insights; this.kpi = kpi; this.ops = ops; this.json = json;
        this.ttlMs = properties.dashboardCacheTtl().toMillis();
    }

    public synchronized Map<String, Object> snapshot() {
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedAt < ttlMs) return cached;
        LocalDate today = QuotaPolicy.today();
        long todayMs = ManagementActivity.startMs(today), tomorrowMs = ManagementActivity.startMs(today.plusDays(1));
        var tiles = new LinkedHashMap<String, Object>();
        tiles.put("usersTotal", count("SELECT COUNT(*) FROM closet_user"));
        tiles.put("usersNewToday", count("SELECT COUNT(*) FROM closet_user WHERE created_at_ms>=? AND created_at_ms<?", todayMs, tomorrowMs));
        tiles.put("activeToday", activity.distinct(today, today));
        tiles.put("active7d", activity.distinct(today.minusDays(6), today));
        tiles.put("active30d", activity.distinct(today.minusDays(29), today));
        tiles.put("onlineNow", count("SELECT COUNT(DISTINCT PRINCIPAL_NAME) FROM SPRING_SESSION WHERE PRINCIPAL_NAME IS NOT NULL AND LAST_ACCESS_TIME>=?", now - 300_000));
        tiles.put("garmentsActive", count("SELECT COUNT(*) FROM garment WHERE closet_status='ACTIVE' AND owner_username IS NOT NULL"));
        tiles.put("garmentsToday", count("SELECT COUNT(*) FROM garment WHERE created_at_ms>=? AND created_at_ms<?", todayMs, tomorrowMs));
        tiles.put("outfitsTotal", count("SELECT COUNT(*) FROM outfit"));
        tiles.put("outfitsToday", count("SELECT COUNT(*) FROM outfit WHERE created_at_ms>=? AND created_at_ms<?", todayMs, tomorrowMs));
        tiles.put("recommendationsToday", count("SELECT COUNT(*) FROM recommendation_log WHERE created_at_ms>=? AND created_at_ms<?", todayMs, tomorrowMs));
        var recent = kpi.recommendations(null, ManagementInsightService.range(today.minusDays(6), today));
        tiles.put("adoptionRate7d", recent.get("adoptionRate"));
        tiles.put("wearLogsToday", count("SELECT COUNT(*) FROM wear_log WHERE created_at_ms>=? AND created_at_ms<?", todayMs, tomorrowMs));
        tiles.put("agentMessagesToday", count("SELECT COALESCE(SUM(used),0) FROM agent_daily_quota WHERE quota_date=?", java.sql.Date.valueOf(today)));
        tiles.put("imageUnitsToday", count("SELECT COALESCE(SUM(used),0) FROM ai_daily_quota WHERE quota_date=?", java.sql.Date.valueOf(today)));
        tiles.put("modelTokensToday", count("SELECT COALESCE(SUM(COALESCE(input_tokens,0)+COALESCE(output_tokens,0)),0) FROM model_usage WHERE created_at_ms>=? AND created_at_ms<?", todayMs, tomorrowMs));

        // Last 24 whole hours including the current one, in the business time zone.
        var hourStart = ZonedDateTime.now(QuotaPolicy.ZONE).truncatedTo(ChronoUnit.HOURS).minusHours(HOURS - 1);
        long fromMs = hourStart.toInstant().toEpochMilli();
        var labels = new ArrayList<String>(); var format = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmXXX");
        for (int i = 0; i < HOURS; i++) labels.add(hourStart.plusHours(i).format(format));
        var hourly = new ArrayList<Map<String, Object>>();
        for (String metric : HOURLY) {
            long[] values;
            if ("active_users".equals(metric)) values = activity.hourly(fromMs, HOURS);
            else {
                values = new long[HOURS];
                for (Long at : jdbc.queryForList(HOURLY_SQL.get(metric), Long.class, fromMs, fromMs + HOURS * 3_600_000L)) {
                    int i = (int) ((at - fromMs) / 3_600_000L); if (i >= 0 && i < HOURS) values[i]++;
                }
            }
            hourly.add(Map.of("metric", metric, "values", Arrays.stream(values).boxed().toList()));
        }
        var daily = insights.trends(null, ManagementInsightService.range(today.minusDays(DAYS - 1), today),
            List.of("active_users", "garments_created", "outfits_created", "recommendations_shown", "model_tokens"), "day");

        var wardrobe = insights.wardrobe(null);
        @SuppressWarnings("unchecked") var colors = (List<ManagementInsightService.KeyCount>) wardrobe.get("colors");
        var distributions = new LinkedHashMap<String, Object>();
        distributions.put("categories", wardrobe.get("categories"));
        distributions.put("colors", colors.stream().filter(c -> !"其他".equals(c.key())).limit(10).toList());
        distributions.put("seasons", wardrobe.get("seasons"));
        distributions.put("recommendationSources7d", recent.get("bySource"));
        distributions.put("styles", wardrobe.get("styles"));
        distributions.put("genderFits", wardrobe.get("genderFits"));
        distributions.put("closetSizes", closetSizes());
        distributions.put("registeredVia", keyCounts("SELECT registered_via, COUNT(*) FROM closet_user GROUP BY registered_via ORDER BY 2 DESC"));
        distributions.put("aiFeatures30d", aiFeatures(ManagementActivity.startMs(today.minusDays(DAYS - 1)), tomorrowMs));

        var out = new LinkedHashMap<String, Object>();
        out.put("generatedAtMs", now);
        out.put("refreshAfterMs", Math.max(30_000, ttlMs));
        out.put("tiles", tiles);
        out.put("hourly", Map.of("hours", labels, "series", hourly));
        out.put("daily", Map.of("days", daily.get("buckets"), "series", daily.get("series")));
        out.put("distributions", distributions);
        out.put("growth", growth(today));
        out.put("heatmap", heatmap(today));
        out.put("feed", feed());
        out.put("ops", ops.summary());
        cached = Collections.unmodifiableMap(out); cachedAt = now;
        return cached;
    }

    /** Users by number of active garments; users with none are counted too. */
    private List<Map<String, Object>> closetSizes() {
        var buckets = new LinkedHashMap<String, Long>();
        for (String key : List.of("0", "1–9", "10–29", "30–59", "60+")) buckets.put(key, 0L);
        jdbc.query("SELECT COALESCE(g.n,0) FROM closet_user u LEFT JOIN (SELECT owner_username, COUNT(*) n FROM garment WHERE closet_status='ACTIVE' GROUP BY owner_username) g ON g.owner_username=u.username",
            r -> { long n = r.getLong(1); buckets.merge(n == 0 ? "0" : n < 10 ? "1–9" : n < 30 ? "10–29" : n < 60 ? "30–59" : "60+", 1L, Long::sum); });
        var out = new ArrayList<Map<String, Object>>();
        buckets.forEach((k, v) -> out.add(Map.of("key", k, "count", v)));
        return out;
    }

    private List<Map<String, Object>> keyCounts(String sql) {
        return jdbc.query(sql, (r, n) -> Map.<String, Object>of("key", r.getString(1), "count", r.getLong(2)));
    }

    private List<Map<String, Object>> aiFeatures(long fromMs, long toMs) {
        return jdbc.query("SELECT feature, COUNT(*), COALESCE(SUM(COALESCE(input_tokens,0)+COALESCE(output_tokens,0)),0) FROM model_usage WHERE created_at_ms>=? AND created_at_ms<? GROUP BY feature ORDER BY 3 DESC",
            (r, n) -> Map.<String, Object>of("key", r.getString(1), "calls", r.getLong(2), "tokens", r.getLong(3)), fromMs, toMs);
    }

    /** New users per day over the last 30 days and the running total at the end of each day. */
    private Map<String, Object> growth(LocalDate today) {
        LocalDate from = today.minusDays(DAYS - 1);
        long fromMs = ManagementActivity.startMs(from);
        long before = count("SELECT COUNT(*) FROM closet_user WHERE created_at_ms<?", fromMs);
        long[] added = new long[DAYS];
        for (Long at : jdbc.queryForList("SELECT created_at_ms FROM closet_user WHERE created_at_ms>=?", Long.class, fromMs)) {
            int i = (int) java.time.temporal.ChronoUnit.DAYS.between(from, ManagementActivity.day(at));
            if (i >= 0 && i < DAYS) added[i]++;
        }
        var days = new ArrayList<String>(); var cumulative = new ArrayList<Long>(); long total = before;
        for (int i = 0; i < DAYS; i++) { days.add(from.plusDays(i).toString()); total += added[i]; cumulative.add(total); }
        return Map.of("days", days, "newUsers", Arrays.stream(added).boxed().toList(), "cumulative", cumulative);
    }

    /** Recorded actions over the last 28 days by weekday (1 = Monday) and hour, in the business time zone. */
    private Map<String, Object> heatmap(LocalDate today) {
        int days = 28;
        long[][] cells = new long[7][24];
        for (var e : activity.events(ManagementActivity.startMs(today.minusDays(days - 1)), ManagementActivity.startMs(today.plusDays(1)), null)) {
            var at = java.time.Instant.ofEpochMilli(e.atMs()).atZone(QuotaPolicy.ZONE);
            cells[at.getDayOfWeek().getValue() - 1][at.getHour()]++;
        }
        var out = new ArrayList<List<Long>>();
        for (int d = 0; d < 7; d++) for (int h = 0; h < 24; h++) out.add(List.of((long) d + 1, (long) h, cells[d][h]));
        return Map.of("days", days, "cells", out);
    }

    /** The latest actions across the site, de-identified: kind, time and at most a garment family — never who or what exactly. */
    private List<Map<String, Object>> feed() {
        record Item(long at, String kind, String label) {}
        var items = new ArrayList<Item>();
        jdbc.query("SELECT created_at_ms, automatic_json, overrides_json FROM garment WHERE owner_username IS NOT NULL ORDER BY created_at_ms DESC LIMIT 12",
            r -> { items.add(new Item(r.getLong(1), "GARMENT_ADDED", family(r.getString(2), r.getString(3)))); });
        jdbc.query("SELECT created_at_ms FROM outfit ORDER BY created_at_ms DESC LIMIT 8", r -> { items.add(new Item(r.getLong(1), "OUTFIT_SAVED", null)); });
        jdbc.query("SELECT created_at_ms FROM wear_log ORDER BY created_at_ms DESC LIMIT 8", r -> { items.add(new Item(r.getLong(1), "WEAR_LOGGED", null)); });
        jdbc.query("SELECT created_at_ms FROM recommendation_feedback WHERE verdict='UP' ORDER BY created_at_ms DESC LIMIT 8", r -> { items.add(new Item(r.getLong(1), "RECOMMENDATION_LIKED", null)); });
        jdbc.query("SELECT created_at_ms, registered_via FROM closet_user ORDER BY created_at_ms DESC LIMIT 6", r -> { items.add(new Item(r.getLong(1), "USER_REGISTERED", r.getString(2))); });
        return items.stream().sorted(Comparator.comparingLong(Item::at).reversed()).limit(20).map(i -> {
            var m = new LinkedHashMap<String, Object>(); m.put("atMs", i.at()); m.put("kind", i.kind()); m.put("label", i.label()); return (Map<String, Object>) m;
        }).toList();
    }

    private String family(String automatic, String overrides) {
        try {
            JsonNode own = overrides == null ? null : json.readTree(overrides).path("category");
            var values = new ArrayList<String>();
            if (own != null && own.isArray() && !own.isEmpty()) own.forEach(v -> values.add(v.asText()));
            else if (automatic != null) json.readTree(automatic).path("attributes").path("category").path("values").forEach(v -> values.add(v.asText()));
            return values.isEmpty() ? "UNKNOWN" : ClosetClassification.family(values);
        } catch (RuntimeException e) { return "UNKNOWN"; }
    }

    private long count(String sql, Object... args) { Long v = jdbc.queryForObject(sql, Long.class, args); return v == null ? 0 : v; }
}
