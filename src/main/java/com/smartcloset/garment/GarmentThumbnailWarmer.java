package com.smartcloset.garment;

import com.smartcloset.storage.ImageVariants;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Makes the WebP list copies of each garment's display image ahead of the first visit, so a new garment or a freshly
 * deployed server shows its wardrobe without waiting for scaling. It follows garment.updated_at_ms, which every
 * display change bumps (processing, quick intake, choosing an image, confirming), and starts from zero, so the first
 * pass after start covers the existing wardrobe. A copy that already exists costs one file check.
 */
@Component
public class GarmentThumbnailWarmer {
    private static final Logger log = LoggerFactory.getLogger(GarmentThumbnailWarmer.class);
    /** The widths wardrobe cards ask for (GarmentArt srcset) on phones and desktops. */
    static final int[] WIDTHS = {320, 480, 720};
    private static final int BATCH = 200;

    private final JdbcTemplate db;
    private final ImageVariants variants;
    private final ScheduledExecutorService timer;
    private long watermark;

    public GarmentThumbnailWarmer(JdbcTemplate db, ImageVariants variants,
            @Value("${closet.image-variants.prewarm:true}") boolean enabled,
            @Value("${closet.image-variants.prewarm-interval-seconds:15}") long interval) {
        this.db = db; this.variants = variants;
        if (!enabled) { timer = null; return; }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "garment-thumbnail-warmer");
            // Behind live requests: those scale images the user is looking at right now.
            thread.setDaemon(true); thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        // Wait out start-up, when models and caches are loading.
        timer.scheduleWithFixedDelay(this::safePass, Math.max(interval, 30), interval, TimeUnit.SECONDS);
    }

    private void safePass() {
        try { while (pass() == BATCH) { /* keep going until caught up */ } }
        catch (RuntimeException e) { log.warn("Thumbnail warm-up pass failed: {}", e.getMessage()); }
    }

    /** One batch of garments changed since the last pass; returns how many were looked at. */
    public synchronized int pass() {
        record Row(long updated, String key) {}
        List<Row> rows = db.query("SELECT g.updated_at_ms,a.job_id,a.file_name FROM garment g JOIN image_asset a ON a.id=g.display_asset_id "
                + "WHERE g.closet_status!='TRASHED' AND g.updated_at_ms>? ORDER BY g.updated_at_ms LIMIT " + BATCH,
                (r, n) -> new Row(r.getLong(1), "workflows/" + r.getString(2) + "/" + r.getString(3)), watermark);
        for (var row : rows) {
            for (int width : WIDTHS) {
                try { variants.variant(row.key(), width, true); }
                catch (Exception e) { log.debug("No thumbnail for {}: {}", row.key(), e.getMessage()); break; } // Served as the original instead.
            }
            watermark = Math.max(watermark, row.updated());
        }
        return rows.size();
    }

    @PreDestroy void stop() { if (timer != null) timer.shutdownNow(); }
}
