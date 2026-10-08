package com.smartcloset.garment;

import com.smartcloset.image.ProcessingException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Image style of each garment's current display image. A row whose display_asset_id no longer matches the garment is
 * stale and treated as unknown until reclassified; a USER row is kept until the user changes it.
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
    public record Entry(String garmentId, Style style, String source, String evidence) {}
    public record Pending(String garmentId, String owner, String displayAssetId) {}

    private final JdbcTemplate db;
    public ImageStyles(JdbcTemplate db) { this.db = db; }

    /** Current (non-stale) styles of a user's active garments. */
    public Map<String, Entry> current(String owner) {
        var out = new HashMap<String, Entry>();
        db.query("SELECT s.garment_id,s.style,s.source,s.evidence FROM garment_image_style s JOIN garment g ON g.id=s.garment_id "
            + "WHERE g.owner_username=? AND g.closet_status='ACTIVE' AND (s.source='USER' OR s.display_asset_id=g.display_asset_id)", rs -> {
            var style = Style.parse(rs.getString(2));
            if (style != null) out.put(rs.getString(1), new Entry(rs.getString(1), style, rs.getString(3), rs.getString(4)));
        }, owner);
        return out;
    }

    /** Most common known style of the user's closet; PHOTO when nothing is classified yet. */
    public Style dominant(String owner) {
        var counts = new EnumMap<Style, Integer>(Style.class);
        current(owner).values().forEach(e -> counts.merge(e.style(), 1, Integer::sum));
        return counts.entrySet().stream().max(Comparator.<Map.Entry<Style, Integer>>comparingInt(Map.Entry::getValue).thenComparing(e -> e.getKey() == Style.PHOTO ? 1 : 0))
            .map(Map.Entry::getKey).orElse(Style.PHOTO);
    }

    public List<Pending> pending(int limit) {
        return db.query("SELECT g.id,g.owner_username,g.display_asset_id FROM garment g LEFT JOIN garment_image_style s ON s.garment_id=g.id "
            + "WHERE g.closet_status='ACTIVE' AND g.owner_username IS NOT NULL AND (s.garment_id IS NULL OR (s.source='MODEL' AND s.display_asset_id<>g.display_asset_id)) "
            + "ORDER BY g.owner_username, g.updated_at_ms DESC LIMIT ?", (r, n) -> new Pending(r.getString(1), r.getString(2), r.getString(3)), limit);
    }

    public List<Pending> pending(String owner, int limit) {
        return db.query("SELECT g.id,g.owner_username,g.display_asset_id FROM garment g LEFT JOIN garment_image_style s ON s.garment_id=g.id "
            + "WHERE g.closet_status='ACTIVE' AND g.owner_username=? AND (s.garment_id IS NULL OR (s.source='MODEL' AND s.display_asset_id<>g.display_asset_id)) "
            + "ORDER BY g.updated_at_ms DESC LIMIT ?", (r, n) -> new Pending(r.getString(1), r.getString(2), r.getString(3)), owner, limit);
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
