package com.smartcloset.management.application;

import com.smartcloset.garment.GarmentRepository;
import com.smartcloset.management.ConditionalOnManagementEnabled;
import com.smartcloset.management.ManagementException;
import com.smartcloset.management.ManagementProperties;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * De-identified event stream for a data warehouse (design 管理接口二期 §7.3, M8/M9). Events are ordered by (atMs, type, id);
 * the cursor encodes the last one, so a page can be re-fetched any number of times with the same result. A five-second
 * watermark keeps in-flight writes out of the page. Users appear only as userKey = HMAC-SHA256(salt, username)[0..16).
 */
@Service
@ConditionalOnManagementEnabled
public class ManagementExportService {
    public static final int MAX_LIMIT = 1000;
    static final long WATERMARK_MS = 5_000;
    public record Event(String type, String id, long atMs, String userKey, Map<String, Object> attrs) {}
    record Cursor(long atMs, String type, String id) {}
    private interface Source { List<Event> read(Cursor after, long watermark, int limit); }

    private final JdbcTemplate jdbc;
    private final GarmentRepository garments;
    private final ObjectMapper json;
    private final byte[] salt;
    private final Map<String, Source> sources = new TreeMap<>();

    public ManagementExportService(JdbcTemplate jdbc, GarmentRepository garments, ObjectMapper json, ManagementProperties properties) {
        this.jdbc = jdbc; this.garments = garments; this.json = json;
        this.salt = properties.exportSalt().isBlank() ? hmac(firstSecret(properties).getBytes(StandardCharsets.UTF_8), "closet-export-salt-v1")
            : properties.exportSalt().getBytes(StandardCharsets.UTF_8);
        // Users are ordered by their pseudonymous key, which SQL cannot compute; the table is small, so filter in memory.
        // This also keeps user names out of the cursor.
        sources.put("user_created", (c, w, n) -> jdbc.query("SELECT username,created_at_ms,role FROM closet_user WHERE created_at_ms<=?",
                (r, i) -> new Event("user_created", userKey(r.getString(1)), r.getLong(2), userKey(r.getString(1)), Map.of("role", r.getString(3))), w).stream()
            .filter(e -> after(e, c)).sorted(Comparator.comparingLong(Event::atMs).thenComparing(Event::id)).limit(n).toList());
        sources.put("garment_created", (c, w, n) -> query("garment_created", "SELECT id,owner_username,created_at_ms,closet_status,automatic_json,overrides_json FROM garment WHERE owner_username IS NOT NULL",
            "created_at_ms", "id", c, w, n, r -> Map.of("status", r.getString(4), "family", garments.family(r.getString(5), r.getString(6)))));
        sources.put("outfit_created", (c, w, n) -> query("outfit_created", "SELECT id,owner_username,created_at_ms,scene_json FROM outfit", "created_at_ms", "id", c, w, n,
            r -> Map.of("items", garmentCount(r.getString(4)))));
        sources.put("recommendation_shown", (c, w, n) -> query("recommendation_shown", "SELECT id,owner_username,created_at_ms,source,rank_no,total_score,template_id FROM recommendation_log",
            "created_at_ms", "id", c, w, n, r -> {
                var m = new LinkedHashMap<String, Object>(); m.put("source", r.getString(4)); m.put("rank", r.getInt(5)); m.put("score", r.getDouble(6)); m.put("template", r.getString(7)); return m;
            }));
        sources.put("recommendation_feedback", (c, w, n) -> query("recommendation_feedback", "SELECT id,owner_username,created_at_ms,verdict,reasons_json,log_id FROM recommendation_feedback",
            "created_at_ms", "id", c, w, n, r -> Map.of("verdict", r.getString(4), "reasons", strings(r.getString(5)), "recommendationId", r.getString(6))));
        sources.put("wear_logged", (c, w, n) -> query("wear_logged", "SELECT id,owner_username,created_at_ms,garment_ids_json FROM wear_log", "created_at_ms", "id", c, w, n,
            r -> Map.of("items", strings(r.getString(4)).size())));
        sources.put("model_usage", (c, w, n) -> query("model_usage", "SELECT id,owner_username,created_at_ms,feature,model,input_tokens,output_tokens FROM model_usage", "created_at_ms", "id", c, w, n,
            r -> {
                var m = new LinkedHashMap<String, Object>(); m.put("feature", r.getString(4)); m.put("model", r.getString(5));
                m.put("inputTokens", r.getObject(6) == null ? null : r.getLong(6)); m.put("outputTokens", r.getObject(7) == null ? null : r.getLong(7)); return m;
            }));
        sources.put("quick_intake", (c, w, n) -> query("quick_intake", "SELECT id,owner_username,created_at_ms,admission_mode FROM quick_intake", "created_at_ms", "id", c, w, n,
            r -> Map.of("mode", r.getString(4))));
    }

    public Set<String> types() { return sources.keySet(); }

    public Map<String, Object> events(String cursor, Long since, int limit, List<String> types) {
        if (limit < 1 || limit > MAX_LIMIT) throw ManagementException.invalid("limit 范围为 1 到 " + MAX_LIMIT + "。");
        if (cursor != null && since != null) throw ManagementException.invalid("cursor 与 since 只能二选一。");
        var wanted = types == null || types.isEmpty() ? new ArrayList<>(sources.keySet()) : types.stream().distinct().toList();
        if (!sources.keySet().containsAll(wanted)) throw ManagementException.invalid("types 只能是 " + String.join("、", sources.keySet()) + "。");
        Cursor after = cursor != null ? decode(cursor) : new Cursor(since == null ? Long.MIN_VALUE : since, "", "");
        long watermark = System.currentTimeMillis() - WATERMARK_MS;
        var merged = new ArrayList<Event>();
        for (String type : wanted) merged.addAll(sources.get(type).read(after, watermark, limit + 1));
        merged.sort(Comparator.comparingLong(Event::atMs).thenComparing(Event::type).thenComparing(Event::id));
        boolean more = merged.size() > limit;
        var page = merged.subList(0, Math.min(limit, merged.size()));
        String next = page.isEmpty() ? (cursor != null ? cursor : encode(after)) : encode(new Cursor(page.getLast().atMs(), page.getLast().type(), page.getLast().id()));
        var out = new LinkedHashMap<String, Object>();
        out.put("items", List.copyOf(page)); out.put("nextCursor", next); out.put("hasMore", more); out.put("watermarkMs", watermark);
        return out;
    }

    @FunctionalInterface private interface Attrs { Map<String, Object> of(java.sql.ResultSet r) throws java.sql.SQLException; }

    /**
     * Rows strictly after the cursor in (atMs, type, id) order. Within one table the type is constant, so the comparison
     * reduces to: a later type may share the cursor's millisecond, the same type must have a larger id, an earlier type must not.
     */
    private List<Event> query(String type, String select, String at, String id, Cursor c, long watermark, int limit, Attrs attrs) {
        int order = type.compareTo(c.type());
        String condition; Object[] args;
        if (c.atMs() == Long.MIN_VALUE) { condition = at + "<=?"; args = new Object[]{watermark, limit}; }
        else if (order > 0) { condition = at + ">=? AND " + at + "<=?"; args = new Object[]{c.atMs(), watermark, limit}; }
        else if (order == 0) { condition = "(" + at + ">? OR (" + at + "=? AND " + id + ">?)) AND " + at + "<=?"; args = new Object[]{c.atMs(), c.atMs(), c.id(), watermark, limit}; }
        else { condition = at + ">? AND " + at + "<=?"; args = new Object[]{c.atMs(), watermark, limit}; }
        String sql = "SELECT * FROM (" + select + ") s WHERE " + condition + " ORDER BY " + at + ", " + id + " LIMIT ?";
        return jdbc.query(sql, (r, n) -> new Event(type, r.getString(1), r.getLong(3), userKey(r.getString(2)), attrs.of(r)), args);
    }

    static boolean after(Event e, Cursor c) {
        if (c.atMs() == Long.MIN_VALUE) return true;
        if (e.atMs() != c.atMs()) return e.atMs() > c.atMs();
        int t = e.type().compareTo(c.type());
        return t != 0 ? t > 0 : e.id().compareTo(c.id()) > 0;
    }

    public String userKey(String username) { return username == null ? null : HexFormat.of().formatHex(hmac(salt, username)).substring(0, 16); }

    static String encode(Cursor c) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((c.atMs() + "|" + c.type() + "|" + c.id()).getBytes(StandardCharsets.UTF_8));
    }
    static Cursor decode(String raw) {
        try {
            var parts = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8).split("\\|", 3);
            if (parts.length != 3 || parts[2].length() > 80 || !parts[1].matches("[a-z_]{0,40}")) throw new IllegalArgumentException();
            return new Cursor(Long.parseLong(parts[0]), parts[1], parts[2]);
        } catch (IllegalArgumentException e) { throw ManagementException.invalid("cursor 无效，请使用上次返回的 nextCursor。"); }
    }

    private int garmentCount(String scene) {
        try { int n = 0; for (var e : json.readTree(scene).path("elements")) if (e.hasNonNull("garmentId")) n++; return n; }
        catch (RuntimeException e) { return 0; }
    }
    private List<String> strings(String raw) {
        var out = new ArrayList<String>();
        try { if (raw != null) for (var n : json.readTree(raw)) out.add(n.asText()); } catch (RuntimeException ignored) { }
        return out;
    }
    private static String firstSecret(ManagementProperties p) { return p.auth().keys().isEmpty() ? "closet" : p.auth().keys().getFirst().secret(); }
    private static byte[] hmac(byte[] key, String value) {
        try { var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
}
