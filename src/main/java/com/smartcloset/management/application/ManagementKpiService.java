package com.smartcloset.management.application;

import com.smartcloset.management.ConditionalOnManagementEnabled;
import com.smartcloset.management.ManagementException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Recommendation and model-usage KPIs (design 管理接口二期 §5, M4/M5). Same definitions as the user's own settings card
 * (P4 D39): adopted = liked, opened in the workspace, or every garment of it logged as worn within three days — matched per
 * user, then summed. Tokens only; prices belong to whoever pays the provider.
 */
@Service
@ConditionalOnManagementEnabled
public class ManagementKpiService {
    static final long WEAR_WINDOW_MS = Duration.ofDays(3).toMillis();
    public static final String ADOPTION_DEFINITION = "采用＝点赞、在搭台打开，或展示后 3 天内打卡穿过这一套的全部衣物（按用户分别匹配后汇总）";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ManagementKpiService(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    record Log(String id, String owner, String source, int rank, String template, Set<String> garments, long at) {}

    public Map<String, Object> recommendations(String username, ManagementInsightService.Range range) {
        requireUser(username);
        String owner = username == null ? "" : " AND owner_username=?";
        var args = args(range, username);
        var logs = jdbc.query("SELECT id,owner_username,source,rank_no,template_id,garment_ids_json,created_at_ms FROM recommendation_log WHERE created_at_ms>=? AND created_at_ms<?" + owner,
            (r, n) -> new Log(r.getString(1), r.getString(2), r.getString(3), r.getInt(4), r.getString(5), new HashSet<>(strings(r.getString(6))), r.getLong(7)), args);
        String inRange = "SELECT id FROM recommendation_log WHERE created_at_ms>=? AND created_at_ms<?" + owner;
        var up = new HashSet<String>(); var down = new HashSet<String>();
        jdbc.query("SELECT log_id,verdict FROM recommendation_feedback WHERE log_id IN (" + inRange + ")", r -> { ("UP".equals(r.getString(2)) ? up : down).add(r.getString(1)); }, args);
        var opened = new HashSet<String>();
        jdbc.query("SELECT log_id FROM style_signal WHERE kind='OPEN_WORKSPACE' AND log_id IN (" + inRange + ")", r -> { opened.add(r.getString(1)); }, args);
        long swaps = jdbc.queryForObject("SELECT COUNT(*) FROM style_signal WHERE kind='SWAP_OUT' AND created_at_ms>=? AND created_at_ms<?" + owner, Long.class, args);
        // Wears are matched to the same user's recommendation, up to three days after it was shown.
        record Wear(Set<String> garments, long at) {}
        var wears = new HashMap<String, List<Wear>>();
        var wearArgs = new ArrayList<Object>(List.of(range.fromMs(), range.toMsExclusive() + WEAR_WINDOW_MS)); if (username != null) wearArgs.add(username);
        jdbc.query("SELECT owner_username,garment_ids_json,created_at_ms FROM wear_log WHERE created_at_ms>=? AND created_at_ms<?" + owner,
            r -> { wears.computeIfAbsent(r.getString(1), k -> new ArrayList<>()).add(new Wear(new HashSet<>(strings(r.getString(2))), r.getLong(3))); }, wearArgs.toArray());

        int adopted = 0, worn = 0;
        var users = new HashSet<String>();
        var bySource = new TreeMap<String, int[]>(); var byRank = new TreeMap<Integer, int[]>(); var byTemplate = new HashMap<String, int[]>(); var daily = new TreeMap<LocalDate, int[]>();
        for (var l : logs) {
            users.add(l.owner());
            boolean w = wears.getOrDefault(l.owner(), List.of()).stream().anyMatch(x -> x.at() >= l.at() && x.at() <= l.at() + WEAR_WINDOW_MS && x.garments().containsAll(l.garments()));
            boolean a = up.contains(l.id()) || opened.contains(l.id()) || w;
            if (w) worn++; if (a) adopted++;
            tally(bySource.computeIfAbsent(l.source(), k -> new int[2]), a);
            tally(byRank.computeIfAbsent(l.rank(), k -> new int[2]), a);
            if (l.template() != null) tally(byTemplate.computeIfAbsent(l.template(), k -> new int[2]), a);
            tally(daily.computeIfAbsent(ManagementActivity.day(l.at()), k -> new int[2]), a);
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("scope", new ManagementInsightService.Scope(username, range.from(), range.to()));
        out.put("shown", logs.size()); out.put("adopted", adopted); out.put("adoptionRate", rate(adopted, logs.size()));
        out.put("liked", up.size()); out.put("disliked", down.size()); out.put("likeRate", rate(up.size(), up.size() + down.size()));
        out.put("openedInWorkspace", opened.size()); out.put("wornWithin3Days", worn); out.put("swaps", swaps);
        out.put("users", users.size());
        out.put("bySource", rows(bySource, "source"));
        out.put("byRank", rows(byRank, "rank"));
        out.put("templates", byTemplate.entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<String, int[]> e) -> -e.getValue()[0]).thenComparing(Map.Entry::getKey)).limit(10)
            .map(e -> row("template", e.getKey(), e.getValue())).toList());
        var days = new ArrayList<Map<String, Object>>();
        for (LocalDate d = range.from(); !d.isAfter(range.to()); d = d.plusDays(1)) row(days, d, daily.getOrDefault(d, new int[2]));
        out.put("daily", days);
        out.put("definition", ADOPTION_DEFINITION);
        return out;
    }

    public Map<String, Object> ai(String username, ManagementInsightService.Range range) {
        requireUser(username);
        String owner = username == null ? "" : " AND owner_username=?";
        var args = args(range, username);
        var features = jdbc.query("SELECT feature,COUNT(*),COALESCE(SUM(input_tokens),0),COALESCE(SUM(output_tokens),0),SUM(CASE WHEN input_tokens IS NULL AND output_tokens IS NULL THEN 1 ELSE 0 END) "
            + "FROM model_usage WHERE created_at_ms>=? AND created_at_ms<?" + owner + " GROUP BY feature ORDER BY feature", (r, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("feature", r.getString(1)); m.put("calls", r.getLong(2)); m.put("inputTokens", r.getLong(3)); m.put("outputTokens", r.getLong(4)); m.put("unknownUsage", r.getLong(5));
                return m;
            }, args);
        var dateArgs = new ArrayList<Object>(List.of(java.sql.Date.valueOf(range.from()), java.sql.Date.valueOf(range.to()))); if (username != null) dateArgs.add(username);
        long images = jdbc.queryForObject("SELECT COALESCE(SUM(used),0) FROM ai_daily_quota WHERE quota_date>=? AND quota_date<=?" + owner, Long.class, dateArgs.toArray());
        long messages = jdbc.queryForObject("SELECT COALESCE(SUM(used),0) FROM agent_daily_quota WHERE quota_date>=? AND quota_date<=?" + owner, Long.class, dateArgs.toArray());
        long shown = jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_log WHERE created_at_ms>=? AND created_at_ms<?" + owner, Long.class, args);
        long local = jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_log WHERE source<>'AGENT' AND created_at_ms>=? AND created_at_ms<?" + owner, Long.class, args);
        var out = new LinkedHashMap<String, Object>();
        out.put("scope", new ManagementInsightService.Scope(username, range.from(), range.to()));
        out.put("byFeature", features);
        out.put("totals", Map.of("calls", features.stream().mapToLong(f -> (Long) f.get("calls")).sum(),
            "inputTokens", features.stream().mapToLong(f -> (Long) f.get("inputTokens")).sum(), "outputTokens", features.stream().mapToLong(f -> (Long) f.get("outputTokens")).sum()));
        out.put("imageUnits", images);
        out.put("agentMessages", messages);
        out.put("recommendations", Map.of("total", shown, "zeroModel", local, "zeroModelRatio", shown == 0 ? 1.0 : Math.round(1000.0 * local / shown) / 1000.0));
        if (username == null) out.put("topUsers", jdbc.query("SELECT owner_username,COUNT(*),COALESCE(SUM(input_tokens),0)+COALESCE(SUM(output_tokens),0) t FROM model_usage "
            + "WHERE created_at_ms>=? AND created_at_ms<? GROUP BY owner_username ORDER BY t DESC, owner_username LIMIT 10",
            (r, n) -> Map.of("username", r.getString(1), "calls", r.getLong(2), "tokens", r.getLong(3)), range.fromMs(), range.toMsExclusive()));
        out.put("note", "推荐、换一批、换一件、今日灵感、衣柜搜索、取色均为本地计算，不消耗模型；费用请按服务商单价乘以 token 数自行换算");
        return out;
    }

    /** Adoption over the last {@code days} days, platform-wide (dashboard tile). */
    public Double adoptionRate(LocalDate from, LocalDate to) {
        var k = recommendations(null, ManagementInsightService.range(from, to));
        return (Double) k.get("adoptionRate");
    }

    private Object[] args(ManagementInsightService.Range range, String username) {
        var a = new ArrayList<Object>(List.of(range.fromMs(), range.toMsExclusive())); if (username != null) a.add(username); return a.toArray();
    }
    private void requireUser(String username) {
        if (username != null && jdbc.queryForObject("SELECT COUNT(*) FROM closet_user WHERE username=?", Integer.class, username) == 0) throw ManagementException.userNotFound(username);
    }
    private static void tally(int[] c, boolean adopted) { c[0]++; if (adopted) c[1]++; }
    private static Map<String, Object> row(String key, Object value, int[] c) {
        var m = new LinkedHashMap<String, Object>(); m.put(key, value); m.put("shown", c[0]); m.put("adopted", c[1]); m.put("adoptionRate", rate(c[1], c[0])); return m;
    }
    private static <K> List<Map<String, Object>> rows(Map<K, int[]> counts, String key) { return counts.entrySet().stream().map(e -> row(key, e.getKey(), e.getValue())).toList(); }
    private static void row(List<Map<String, Object>> out, LocalDate d, int[] c) { var m = row("date", d.toString(), c); m.remove("adoptionRate"); out.add(m); }
    private static Double rate(int n, int d) { return d == 0 ? null : Math.round(1000.0 * n / d) / 1000.0; }
    private List<String> strings(String raw) { var out = new ArrayList<String>(); if (raw != null) for (var n : json.readTree(raw)) out.add(n.asText()); return out; }
}
