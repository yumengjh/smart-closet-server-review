package com.smartcloset.garment;

import com.smartcloset.image.ProcessingException;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Image style of each garment's current display image (D29). Resolved without any model call, in this order:
 * the user's own choice; a style named in the generation parameters of the display image; the style the recognition
 * call reported for the photo the display image was cut out of or tidied from (garment-v6, same call, no extra quota);
 * the one-off legacy grid judgement for that display image; and finally the source itself (a cut-out of the user's
 * photo is a photo). Garments recognised before garment-v6 with no legacy judgement stay unknown until the one-off
 * backfill ({@link ImageStyleHandler}) classifies them; nothing new ever enters that backfill.
 */
@Repository
public class ImageStyles {
    public enum Style {
        PHOTO("写实实物"), ILLUSTRATION("卡通插画"), SKETCH("简笔线稿"), OTHER("其他风格");
        public final String label;
        Style(String label) { this.label = label; }
        public static Style parse(String raw) {
            try { return raw == null ? null : Style.valueOf(raw.strip().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { return null; }
        }
    }
    /** source: USER, GENERATION, RECOGNITION, MODEL (legacy one-off grid) or SOURCE (inferred from how the image was made). */
    public record Entry(String garmentId, Style style, String source, String evidence) {}
    public record Pending(String garmentId, String owner, String displayAssetId) {}
    static final String CUTOFF = "image-style-backfill-cutoff", DONE = "image-style-backfill-done";
    private static final Pattern STYLE_FIELD = Pattern.compile("\"imageStyle\"\\s*:\\s*\"([A-Za-z]+)\"");
    private static final Set<String> CUTOUTS = Set.of("INPUT", "STICKER", "BASE_STICKER", "BASE", "AI_ENHANCED", "GENERATE");

    private final JdbcTemplate db;
    public ImageStyles(JdbcTemplate db) { this.db = db; }

    /** Current styles of a user's active garments; garments that cannot be resolved yet are absent. */
    public Map<String, Entry> current(String owner) {
        var out = new HashMap<String, Entry>();
        long cutoff = cutoff();
        db.query("SELECT g.id,g.created_at_ms,g.automatic_json,a.kind,j.request_json,s.style,s.source,s.evidence,CASE WHEN s.display_asset_id=g.display_asset_id THEN 1 ELSE 0 END fresh "
            + "FROM garment g LEFT JOIN image_asset a ON a.id=g.display_asset_id LEFT JOIN workflow_job j ON j.id=a.job_id LEFT JOIN garment_image_style s ON s.garment_id=g.id "
            + "WHERE g.owner_username=? AND g.closet_status='ACTIVE'", rs -> {
            var e = resolve(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getInt(9) == 1, cutoff);
            if (e != null) out.put(e.garmentId(), e);
        }, owner);
        return out;
    }

    static Entry resolve(String id, long createdAt, String automatic, String kind, String request, String rowStyle, String rowSource, String rowEvidence, boolean rowFresh, long cutoff) {
        if ("USER".equals(rowSource)) { var st = Style.parse(rowStyle); if (st != null) return new Entry(id, st, "USER", rowEvidence); }
        var generated = field(request);
        if (generated != null) return new Entry(id, generated, "GENERATION", "生成参数指定为" + generated.label);
        var recognised = field(automatic);
        if (recognised != null && (kind == null || CUTOUTS.contains(kind))) return new Entry(id, recognised, "RECOGNITION", "识图时一并判断（展示图由这张原图抠图或整理而来）");
        if ("MODEL".equals(rowSource) && rowFresh) { var st = Style.parse(rowStyle); if (st != null) return new Entry(id, st, "MODEL", rowEvidence); }
        // Recognised before garment-v6 without a legacy judgement: left for the one-off backfill instead of guessing.
        if (createdAt < cutoff && automatic != null) return null;
        if (kind == null || CUTOUTS.contains(kind)) return new Entry(id, Style.PHOTO, "SOURCE", "由你拍摄的照片抠图或整理而来，按来源推断为写实");
        return null;
    }

    private static Style field(String json) {
        if (json == null || !json.contains("imageStyle")) return null;
        var m = STYLE_FIELD.matcher(json);
        return m.find() ? Style.parse(m.group(1)) : null;
    }

    /** Most common known style of the user's closet; PHOTO when nothing is classified yet. */
    public Style dominant(String owner) {
        var counts = new EnumMap<Style, Integer>(Style.class);
        current(owner).values().forEach(e -> counts.merge(e.style(), 1, Integer::sum));
        return counts.entrySet().stream().max(Comparator.<Map.Entry<Style, Integer>>comparingInt(Map.Entry::getValue).thenComparing(e -> e.getKey() == Style.PHOTO ? 1 : 0))
            .map(Map.Entry::getKey).orElse(Style.PHOTO);
    }

    /** First call fixes the cutoff: garments created before it are "old data" eligible for the one-off backfill. */
    long cutoff() {
        var v = db.query("SELECT value_ms FROM app_marker WHERE name=?", (r, n) -> r.getLong(1), CUTOFF);
        if (!v.isEmpty()) return v.getFirst();
        long now = System.currentTimeMillis();
        db.update("MERGE INTO app_marker(name,value_ms) KEY(name) VALUES(?,?)", CUTOFF, now);
        return now;
    }
    public boolean backfillDone() { return !db.query("SELECT value_ms FROM app_marker WHERE name=?", (r, n) -> r.getLong(1), DONE).isEmpty(); }
    public void markBackfillDone() { db.update("MERGE INTO app_marker(name,value_ms) KEY(name) VALUES(?,?)", DONE, System.currentTimeMillis()); }

    /** Old garments the zero-call rules cannot resolve (one-off backfill only). */
    public List<Pending> pending(int limit) { return pending(null, limit); }

    public List<Pending> pending(String owner, int limit) {
        long cutoff = cutoff();
        var out = new ArrayList<Pending>();
        db.query("SELECT g.id,g.owner_username,g.display_asset_id,g.created_at_ms,g.automatic_json,a.kind,j.request_json,s.style,s.source,s.evidence,CASE WHEN s.display_asset_id=g.display_asset_id THEN 1 ELSE 0 END fresh "
            + "FROM garment g LEFT JOIN image_asset a ON a.id=g.display_asset_id LEFT JOIN workflow_job j ON j.id=a.job_id LEFT JOIN garment_image_style s ON s.garment_id=g.id "
            + "WHERE g.closet_status='ACTIVE' AND g.owner_username IS NOT NULL AND g.created_at_ms<?" + (owner == null ? "" : " AND g.owner_username=?") + " ORDER BY g.owner_username, g.updated_at_ms DESC", rs -> {
            if (out.size() >= limit) return;
            if (resolve(rs.getString(1), rs.getLong(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getInt(11) == 1, cutoff) == null)
                out.add(new Pending(rs.getString(1), rs.getString(2), rs.getString(3)));
        }, owner == null ? new Object[]{cutoff} : new Object[]{cutoff, owner});
        return out;
    }

    public void saveModel(Pending p, Style style, String evidence) {
        // Never overwrite a user's own choice.
        if (db.queryForObject("SELECT COUNT(*) FROM garment_image_style WHERE garment_id=? AND source='USER'", Integer.class, p.garmentId()) > 0) return;
        db.update("MERGE INTO garment_image_style(garment_id,owner_username,display_asset_id,style,source,evidence,updated_at_ms) KEY(garment_id) VALUES(?,?,?,?,'MODEL',?,?)",
            p.garmentId(), p.owner(), p.displayAssetId(), style.name(), evidence == null ? null : evidence.substring(0, Math.min(300, evidence.length())), System.currentTimeMillis());
    }

    public Entry setByUser(String owner, String garmentId, String raw) {
        var style = Style.parse(raw);
        if (style == null) throw ProcessingException.invalid("图片风格只能是 PHOTO、ILLUSTRATION、SKETCH 或 OTHER。");
        var asset = db.query("SELECT display_asset_id FROM garment WHERE id=? AND owner_username=?", (r, n) -> r.getString(1), garmentId, owner);
        if (asset.isEmpty()) throw new ProcessingException(HttpStatus.NOT_FOUND, "GARMENT_NOT_FOUND", "没有找到该衣物。");
        db.update("MERGE INTO garment_image_style(garment_id,owner_username,display_asset_id,style,source,evidence,updated_at_ms) KEY(garment_id) VALUES(?,?,?,?,'USER',?,?)",
            garmentId, owner, asset.getFirst(), style.name(), "你手动设置", System.currentTimeMillis());
        return new Entry(garmentId, style, "USER", "你手动设置");
    }
}
