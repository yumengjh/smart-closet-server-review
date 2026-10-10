package com.smartcloset.management.application;

import com.smartcloset.garment.ClosetClassification;
import com.smartcloset.garment.GarmentRepository;
import com.smartcloset.management.ConditionalOnManagementEnabled;
import com.smartcloset.management.ManagementException;
import com.smartcloset.management.ManagementProperties;
import com.smartcloset.security.QuotaPolicy;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Read-only aggregates over closet data. "Usage" means appearing in saved outfits: the closet keeps no wear log.
 * Scale assumption: up to ~200 users / 20k garments; results are cached briefly per parameter set.
 */
@Service
@ConditionalOnManagementEnabled
public class ManagementInsightService {
    public static final Set<String> METRICS = new LinkedHashSet<>(List.of("garments_created", "garments_adopted", "outfits_created",
            "quick_intakes", "image_units", "agent_messages", "users_created",
            // 二期 (design 管理接口二期 §6, M6)
            "active_users", "recommendations_shown", "recommendation_likes", "recommendation_dislikes", "wear_logs", "model_calls", "model_tokens", "ootd_samples"));
    /** Columns of /exports/daily, in order (input/output tokens are split there). */
    public static final List<String> DAILY_COLUMNS = List.of("users_created", "active_users", "garments_created", "garments_adopted", "outfits_created", "quick_intakes",
            "recommendations_shown", "recommendation_likes", "recommendation_dislikes", "wear_logs", "agent_messages", "image_units", "model_calls", "input_tokens", "output_tokens", "ootd_samples");
    private static final Set<String> SEASONS = Set.of("春", "夏", "秋", "冬");
    private static final int MAX_CACHE = 500;

    public record Range(LocalDate from, LocalDate to) {
        long fromMs() { return from.atStartOfDay(QuotaPolicy.ZONE).toInstant().toEpochMilli(); }
        long toMsExclusive() { return to.plusDays(1).atStartOfDay(QuotaPolicy.ZONE).toInstant().toEpochMilli(); }
    }
    public record Scope(String username, LocalDate from, LocalDate to) {}
    public record KeyCount(String key, long count) {}
    public record GarmentRef(UUID garmentId, String owner, String name, String family, long createdAtMs, int outfitCount) {}

    private record Cached(long expiresAt, Object value) {}
    private record Garment(UUID id, String owner, String name, String family, long createdAtMs, boolean favorite,
            boolean wearingConfirmed, Map<String, List<String>> fields) {}

    private final JdbcTemplate jdbc;
    private final ManagementActivity activity;
    private final GarmentRepository garments;
    private final ObjectMapper json;
    private final long ttlMs;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public ManagementInsightService(JdbcTemplate jdbc, ManagementActivity activity, GarmentRepository garments, ObjectMapper json, ManagementProperties properties) {
        this.jdbc = jdbc; this.activity = activity; this.garments = garments; this.json = json; this.ttlMs = properties.insightsCacheTtl().toMillis();
    }

    public static Range range(LocalDate from, LocalDate to) {
        LocalDate end = to == null ? QuotaPolicy.today() : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end)) throw ManagementException.invalid("from 不能晚于 to。");
        if (ChronoUnit.DAYS.between(start, end) + 1 > 366) throw ManagementException.invalid("时间范围最长 366 天。");
        return new Range(start, end);
    }

    public Map<String, Object> overview(String username, Range range) {
        return cached("overview", username, range, () -> {
            requireUser(username);
            var result = new LinkedHashMap<String, Object>();
            result.put("scope", new Scope(username, range.from(), range.to()));
            if (username == null) result.put("users", Map.of(
                    "total", count("SELECT COUNT(*) FROM closet_user"),
                    "enabled", count("SELECT COUNT(*) FROM closet_user WHERE enabled=1"),
                    "activeInRange", count("SELECT COUNT(DISTINCT PRINCIPAL_NAME) FROM SPRING_SESSION WHERE PRINCIPAL_NAME IS NOT NULL AND LAST_ACCESS_TIME>=? AND LAST_ACCESS_TIME<?",
                            range.fromMs(), range.toMsExclusive()),
                    "createdInRange", count("SELECT COUNT(*) FROM closet_user WHERE created_at_ms>=? AND created_at_ms<?", range.fromMs(), range.toMsExclusive())));
            String owner = username == null ? "" : " AND owner_username=?";
            long created = scoped("SELECT COUNT(*) FROM garment WHERE created_at_ms>=? AND created_at_ms<?" + owner, username, range.fromMs(), range.toMsExclusive());
            long adopted = scoped("SELECT COUNT(*) FROM garment WHERE closet_status='ACTIVE' AND created_at_ms>=? AND created_at_ms<?" + owner, username, range.fromMs(), range.toMsExclusive());
            var garmentStats = new LinkedHashMap<String, Object>();
            garmentStats.put("active", scoped("SELECT COUNT(*) FROM garment WHERE closet_status='ACTIVE'" + owner, username));
            garmentStats.put("draft", scoped("SELECT COUNT(*) FROM garment WHERE closet_status='DRAFT'" + owner, username));
            garmentStats.put("createdInRange", created);
            garmentStats.put("adoptedInRange", adopted);
            garmentStats.put("adoptionRate", created == 0 ? null : Math.round(adopted * 1000.0 / created) / 1000.0);
            result.put("garments", garmentStats);
            result.put("outfits", Map.of("total", scoped("SELECT COUNT(*) FROM outfit WHERE 1=1" + owner, username),
                    "createdInRange", scoped("SELECT COUNT(*) FROM outfit WHERE created_at_ms>=? AND created_at_ms<?" + owner, username, range.fromMs(), range.toMsExclusive())));
            result.put("outfitPlans", Map.of("createdInRange", scoped("SELECT COUNT(*) FROM outfit_plan WHERE created_at_ms>=? AND created_at_ms<?" + owner, username, range.fromMs(), range.toMsExclusive())));
            result.put("quickIntakes", Map.of("createdInRange", scoped("SELECT COUNT(*) FROM quick_intake WHERE created_at_ms>=? AND created_at_ms<?" + owner, username, range.fromMs(), range.toMsExclusive())));
            var from = java.sql.Date.valueOf(range.from()); var to = java.sql.Date.valueOf(range.to());
            result.put("ai", Map.of(
                    "imageUnitsInRange", scoped("SELECT COALESCE(SUM(used),0) FROM ai_daily_quota WHERE quota_date>=? AND quota_date<=?" + owner, username, from, to),
                    "agentMessagesInRange", scoped("SELECT COALESCE(SUM(used),0) FROM agent_daily_quota WHERE quota_date>=? AND quota_date<=?" + owner, username, from, to)));
            result.put("generatedAtMs", System.currentTimeMillis());
            result.put("mappingVersion", ClosetClassification.VERSION);
            return result;
        });
    }

    public Map<String, Object> wardrobe(String username) {
        return cached("wardrobe", username, null, () -> {
            requireUser(username);
            var active = activeGarments(username);
            var categories = new TreeMap<String, Long>(); var colors = new HashMap<String, Long>(); var seasons = new TreeMap<String, Long>();
            long unknownSeason = 0, favorites = 0, confirmed = 0;
            for (var g : active) {
                categories.merge(g.family(), 1L, Long::sum);
                for (String color : new LinkedHashSet<>(g.fields().getOrDefault("primaryColor", List.of()))) if (!color.isBlank()) colors.merge(color.strip(), 1L, Long::sum);
                var own = g.fields().getOrDefault("seasons", List.of());
                for (String season : new HashSet<>(own)) if (SEASONS.contains(season)) seasons.merge(season, 1L, Long::sum);
                if (own.isEmpty() || own.stream().anyMatch(s -> !SEASONS.contains(s))) unknownSeason++;
                if (g.favorite()) favorites++;
                if (g.wearingConfirmed()) confirmed++;
            }
            var result = new LinkedHashMap<String, Object>();
            result.put("scope", new Scope(username, null, null));
            result.put("totalActive", active.size());
            result.put("categories", ranked(categories, Integer.MAX_VALUE));
            result.put("colors", ranked(colors, 20));
            result.put("seasons", List.of("春", "夏", "秋", "冬").stream().map(s -> new KeyCount(s, seasons.getOrDefault(s, 0L))).toList());
            result.put("unknownCategoryCount", categories.getOrDefault("UNKNOWN", 0L));
            result.put("unknownSeasonCount", unknownSeason);
            result.put("favoriteCount", favorites);
            result.put("wearingConfirmedCount", confirmed);
            result.put("mappingVersion", ClosetClassification.VERSION);
            return result;
        });
    }

    public Map<String, Object> usage(String username) {
        return cached("usage", username, null, () -> {
            requireUser(username);
            var active = activeGarments(username);
            var uses = new HashMap<UUID, Integer>();
            var outfits = username == null ? jdbc.queryForList("SELECT scene_json FROM outfit", String.class)
                    : jdbc.queryForList("SELECT scene_json FROM outfit WHERE owner_username=?", String.class, username);
            for (String scene : outfits) for (UUID id : garmentIds(scene)) uses.merge(id, 1, Integer::sum);
            var refs = active.stream().map(g -> new GarmentRef(g.id(), g.owner(), g.name(), g.family(), g.createdAtMs(), uses.getOrDefault(g.id(), 0))).toList();
            var idle = refs.stream().filter(r -> r.outfitCount() == 0).sorted(Comparator.comparingLong(GarmentRef::createdAtMs)).toList();
            var byFamily = new TreeMap<String, long[]>();
            for (var r : refs) { var c = byFamily.computeIfAbsent(r.family(), k -> new long[2]); c[0]++; if (r.outfitCount() > 0) c[1]++; }
            var result = new LinkedHashMap<String, Object>();
            result.put("scope", new Scope(username, null, null));
            result.put("outfitCount", outfits.size());
            result.put("topUsed", refs.stream().filter(r -> r.outfitCount() > 0)
                    .sorted(Comparator.comparingInt(GarmentRef::outfitCount).reversed().thenComparing(r -> r.garmentId().toString())).limit(10).toList());
            var idleSummary = new LinkedHashMap<String, Object>();
            idleSummary.put("count", idle.size());
            idleSummary.put("ratio", refs.isEmpty() ? null : Math.round(idle.size() * 1000.0 / refs.size()) / 1000.0);
            idleSummary.put("sample", idle.stream().limit(20).toList());
            result.put("idle", idleSummary);
            result.put("byFamily", byFamily.entrySet().stream().map(e -> Map.of("key", e.getKey(), "active", e.getValue()[0], "usedInOutfits", e.getValue()[1])).toList());
            return result;
        });
    }

    public Map<String, Object> trends(String username, Range range, List<String> metrics, String granularity) {
        if (metrics == null || metrics.isEmpty()) throw ManagementException.invalid("至少需要一个 metric。");
        if (metrics.size() > METRICS.size() || !METRICS.containsAll(metrics)) throw ManagementException.invalid("metric 只能是 " + String.join("、", METRICS) + "。");
        if (!List.of("day", "week", "month").contains(granularity)) throw ManagementException.invalid("granularity 只能是 day、week 或 month。");
        var distinct = new ArrayList<>(new LinkedHashSet<>(metrics));
        return cached("trends:" + distinct + ":" + granularity, username, range, () -> {
            requireUser(username);
            var buckets = new ArrayList<LocalDate>();
            for (LocalDate d = bucket(range.from(), granularity); !d.isAfter(range.to()); d = "week".equals(granularity) ? d.plusWeeks(1) : "month".equals(granularity) ? d.plusMonths(1) : d.plusDays(1)) buckets.add(d);
            var series = new ArrayList<Map<String, Object>>();
            for (String metric : distinct) {
                var counts = new HashMap<LocalDate, Long>();
                // Active users are distinct per bucket (a weekly value is not the sum of seven daily values).
                if ("active_users".equals(metric)) activity.byDay(range.from(), range.to(), username, d -> bucket(d, granularity)).forEach((b, users) -> counts.put(b, (long) users.size()));
                else for (var point : points(metric, username, range)) counts.merge(bucket(point.getKey(), granularity), point.getValue(), Long::sum);
                series.add(Map.of("metric", metric, "values", buckets.stream().map(b -> counts.getOrDefault(b, 0L)).toList()));
            }
            var result = new LinkedHashMap<String, Object>();
            result.put("granularity", granularity);
            result.put("buckets", buckets.stream().map(LocalDate::toString).toList());
            result.put("series", series);
            return result;
        });
    }

    private List<Map.Entry<LocalDate, Long>> points(String metric, String username, Range range) {
        String owner = username == null ? "" : " AND owner_username=?";
        return switch (metric) {
            case "garments_created" -> timestamps("SELECT created_at_ms FROM garment WHERE created_at_ms>=? AND created_at_ms<?" + owner, username, range);
            case "garments_adopted" -> timestamps("SELECT created_at_ms FROM garment WHERE closet_status='ACTIVE' AND created_at_ms>=? AND created_at_ms<?" + owner, username, range);
            case "outfits_created" -> timestamps("SELECT created_at_ms FROM outfit WHERE created_at_ms>=? AND created_at_ms<?" + owner, username, range);
            case "quick_intakes" -> timestamps("SELECT created_at_ms FROM quick_intake WHERE created_at_ms>=? AND created_at_ms<?" + owner, username, range);
            case "users_created" -> timestamps("SELECT created_at_ms FROM closet_user WHERE created_at_ms>=? AND created_at_ms<?" + (username == null ? "" : " AND username=?"), username, range);
            case "image_units" -> daily("ai_daily_quota", owner, username, range);
            case "agent_messages" -> daily("agent_daily_quota", owner, username, range);
            case "recommendations_shown" -> timestamps("SELECT created_at_ms FROM recommendation_log WHERE created_at_ms>=? AND created_at_ms<?" + owner, username, range);
            case "recommendation_likes" -> timestamps("SELECT created_at_ms FROM recommendation_feedback WHERE verdict='UP' AND created_at_ms>=? AND created_at_ms<?" + owner, username, range);
            case "recommendation_dislikes" -> timestamps("SELECT created_at_ms FROM recommendation_feedback WHERE verdict='DOWN' AND created_at_ms>=? AND created_at_ms<?" + owner, username, range);
            case "wear_logs" -> timestamps("SELECT created_at_ms FROM wear_log WHERE created_at_ms>=? AND created_at_ms<?" + owner, username, range);
            case "model_calls" -> timestamps("SELECT created_at_ms FROM model_usage WHERE created_at_ms>=? AND created_at_ms<?" + owner, username, range);
            case "model_tokens" -> weighted("SELECT created_at_ms, COALESCE(input_tokens,0)+COALESCE(output_tokens,0) FROM model_usage WHERE created_at_ms>=? AND created_at_ms<?" + owner, username, range);
            case "input_tokens" -> weighted("SELECT created_at_ms, COALESCE(input_tokens,0) FROM model_usage WHERE created_at_ms>=? AND created_at_ms<?" + owner, username, range);
            case "output_tokens" -> weighted("SELECT created_at_ms, COALESCE(output_tokens,0) FROM model_usage WHERE created_at_ms>=? AND created_at_ms<?" + owner, username, range);
            case "ootd_samples" -> timestamps("SELECT created_at_ms FROM ootd_sample WHERE created_at_ms>=? AND created_at_ms<?" + (username == null ? "" : " AND created_by=?"), username, range);
            default -> throw ManagementException.invalid("未知 metric：" + metric);
        };
    }

    private List<Map.Entry<LocalDate, Long>> timestamps(String sql, String username, Range range) {
        var args = new ArrayList<Object>(List.of(range.fromMs(), range.toMsExclusive()));
        if (username != null) args.add(username);
        return jdbc.query(sql, (r, n) -> Map.entry(java.time.Instant.ofEpochMilli(r.getLong(1)).atZone(QuotaPolicy.ZONE).toLocalDate(), 1L), args.toArray());
    }

    private List<Map.Entry<LocalDate, Long>> weighted(String sql, String username, Range range) {
        var args = new ArrayList<Object>(List.of(range.fromMs(), range.toMsExclusive()));
        if (username != null) args.add(username);
        return jdbc.query(sql, (r, n) -> Map.entry(java.time.Instant.ofEpochMilli(r.getLong(1)).atZone(QuotaPolicy.ZONE).toLocalDate(), r.getLong(2)), args.toArray());
    }

    /**
     * /exports/daily (design 管理接口二期 §7.2): one row per day with every daily metric, platform-wide. Not cached (BI pulls it
     * rarely); a full year is a handful of grouped queries.
     */
    public List<Map<String, Object>> dailyTable(Range range) {
        var columns = new LinkedHashMap<String, Map<LocalDate, Long>>();
        for (String column : DAILY_COLUMNS) {
            var counts = new HashMap<LocalDate, Long>();
            if ("active_users".equals(column)) activity.byDay(range.from(), range.to(), null, d -> d).forEach((d, users) -> counts.put(d, (long) users.size()));
            else for (var point : points(column, null, range)) counts.merge(point.getKey(), point.getValue(), Long::sum);
            columns.put(column, counts);
        }
        var rows = new ArrayList<Map<String, Object>>();
        for (LocalDate d = range.from(); !d.isAfter(range.to()); d = d.plusDays(1)) {
            var row = new LinkedHashMap<String, Object>(); row.put("date", d.toString());
            for (var e : columns.entrySet()) row.put(e.getKey(), e.getValue().getOrDefault(d, 0L));
            rows.add(row);
        }
        return rows;
    }

    private List<Map.Entry<LocalDate, Long>> daily(String table, String owner, String username, Range range) {
        var args = new ArrayList<Object>(List.of(java.sql.Date.valueOf(range.from()), java.sql.Date.valueOf(range.to())));
        if (username != null) args.add(username);
        return jdbc.query("SELECT quota_date, SUM(used) FROM " + table + " WHERE quota_date>=? AND quota_date<=?" + owner + " GROUP BY quota_date",
                (r, n) -> Map.entry(r.getDate(1).toLocalDate(), r.getLong(2)), args.toArray());
    }

    private static LocalDate bucket(LocalDate day, String granularity) {
        return "week".equals(granularity) ? day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) : "month".equals(granularity) ? day.withDayOfMonth(1) : day;
    }

    private List<Garment> activeGarments(String username) {
        var owners = username != null ? List.of(username)
                : jdbc.queryForList("SELECT DISTINCT owner_username FROM garment WHERE owner_username IS NOT NULL AND closet_status='ACTIVE' ORDER BY owner_username", String.class);
        var result = new ArrayList<Garment>();
        for (String owner : owners) {
            var created = new HashMap<String, Long>();
            jdbc.query("SELECT id, created_at_ms FROM garment WHERE owner_username=? AND closet_status='ACTIVE'", r -> { created.put(r.getString(1), r.getLong(2)); }, owner);
            for (var p : garments.projection(owner)) if ("ACTIVE".equals(p.status()))
                result.add(new Garment(p.id(), owner, p.name(), p.family(), created.getOrDefault(p.id().toString(), 0L), p.favorite(), p.wearingConfirmed(), p.fields()));
        }
        return result;
    }

    private Set<UUID> garmentIds(String scene) {
        var ids = new HashSet<UUID>();
        if (scene == null) return ids;
        try { collect(json.readTree(scene), ids); } catch (Exception ignored) { }
        return ids;
    }

    private static void collect(JsonNode node, Set<UUID> ids) {
        if (node.isObject()) {
            var id = node.get("garmentId");
            if (id != null && id.isString()) try { ids.add(UUID.fromString(id.asString())); } catch (IllegalArgumentException ignored) { }
            for (var child : node.values()) collect(child, ids);
        } else if (node.isArray()) for (var child : node) collect(child, ids);
    }

    private static List<KeyCount> ranked(Map<String, Long> counts, int limit) {
        var sorted = counts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).toList();
        var result = new ArrayList<KeyCount>();
        long rest = 0;
        for (int i = 0; i < sorted.size(); i++) {
            if (i < limit) result.add(new KeyCount(sorted.get(i).getKey(), sorted.get(i).getValue()));
            else rest += sorted.get(i).getValue();
        }
        if (rest > 0) result.add(new KeyCount("其他", rest));
        return result;
    }

    private void requireUser(String username) {
        if (username != null && count("SELECT COUNT(*) FROM closet_user WHERE username=?", username) == 0)
            throw ManagementException.userNotFound(username);
    }

    private long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }

    private long scoped(String sql, String username, Object... args) {
        var all = new ArrayList<>(Arrays.asList(args));
        if (username != null) all.add(username);
        return jdbc.queryForObject(sql, Long.class, all.toArray());
    }

    @SuppressWarnings("unchecked")
    private <T> T cached(String kind, String username, Range range, Supplier<T> loader) {
        String key = kind + "|" + username + "|" + (range == null ? "" : range.from() + ".." + range.to());
        long now = System.currentTimeMillis();
        var hit = cache.get(key);
        if (hit != null && hit.expiresAt() > now) return (T) hit.value();
        T value = loader.get();
        if (ttlMs > 0) {
            if (cache.size() >= MAX_CACHE) cache.values().removeIf(c -> c.expiresAt() <= now);
            if (cache.size() < MAX_CACHE) cache.put(key, new Cached(now + ttlMs, value));
        }
        return value;
    }
}
