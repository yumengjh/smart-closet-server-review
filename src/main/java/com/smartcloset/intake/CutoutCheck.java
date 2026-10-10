package com.smartcloset.intake;

import java.awt.image.BufferedImage;

/**
 * Local check of a background-removal result (issue #1): a light garment on a light background can come back with a
 * large part of its body half transparent (a milky "ghost") while the outline still looks plausible, and the model's
 * quality judgement may not notice. A clean cutout is opaque inside with only a thin soft edge, so the share of
 * half-transparent pixels among the visible ones is a direct, model-free signal. Pure and cheap (one pass).
 */
public final class CutoutCheck {
    /** alpha below this is background; between it and {@link #SOLID} is half transparent. */
    static final int VISIBLE = 13, SOLID = 160;
    /** Share of half-transparent pixels among visible ones above which the cutout needs enhancement. */
    public static final double GHOST_LIMIT = 0.18;
    /** Ignore tiny results (icons, failed masks are handled elsewhere). */
    static final double MIN_VISIBLE_SHARE = 0.01;

    public record Result(double ghostRatio, double visibleShare, boolean damaged, String reason) {}

    private CutoutCheck() {}

    public static Result check(BufferedImage image) {
        if (image == null || !image.getColorModel().hasAlpha()) return new Result(0, 1, false, "");
        int w = image.getWidth(), h = image.getHeight();
        // Sample at most ~250k pixels; the ratio does not need every pixel of a 4K image.
        int step = Math.max(1, (int) Math.sqrt((double) w * h / 250_000));
        long visible = 0, ghost = 0, total = 0;
        for (int y = 0; y < h; y += step) for (int x = 0; x < w; x += step) {
            int a = image.getRGB(x, y) >>> 24; total++;
            if (a >= VISIBLE) { visible++; if (a < SOLID) ghost++; }
        }
        double share = total == 0 ? 0 : (double) visible / total;
        double ratio = visible == 0 ? 0 : (double) ghost / visible;
        boolean damaged = share >= MIN_VISIBLE_SHARE && ratio >= GHOST_LIMIT;
        String reason = damaged ? String.format(java.util.Locale.ROOT, "本地检查：去背景结果约 %.0f%% 的衣物区域呈半透明，可能缺失或褪色，需要增强。", ratio * 100) : "";
        return new Result(Math.round(ratio * 1000) / 1000.0, Math.round(share * 1000) / 1000.0, damaged, reason);
    }
}
