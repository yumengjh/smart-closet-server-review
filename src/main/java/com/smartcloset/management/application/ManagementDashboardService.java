package com.smartcloset.management.application;

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
    private final long ttlMs;
    private volatile Map<String, Object> cached;
    private volatile long cachedAt;

    public ManagementDashboardService(JdbcTemplate jdbc, ManagementActivity activity, ManagementInsightService insights, ManagementKpiService kpi,
                                      ManagementOpsService ops, ManagementProperties properties) {
        this.jdbc = jdbc; this.activity = activity; this.insights = insights; this.kpi = kpi; this.ops = ops; this.ttlMs = properties.dashboardCacheTtl().toMillis();
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

        var out = new LinkedHashMap<String, Object>();
        out.put("generatedAtMs", now);
        out.put("refreshAfterMs", Math.max(30_000, ttlMs));
        out.put("tiles", tiles);
        out.put("hourly", Map.of("hours", labels, "series", hourly));
        out.put("daily", Map.of("days", daily.get("buckets"), "series", daily.get("series")));
        out.put("distributions", distributions);
        out.put("ops", ops.summary());
        cached = Collections.unmodifiableMap(out); cachedAt = now;
        return cached;
    }

    private long count(String sql, Object... args) { Long v = jdbc.queryForObject(sql, Long.class, args); return v == null ? 0 : v; }
}
