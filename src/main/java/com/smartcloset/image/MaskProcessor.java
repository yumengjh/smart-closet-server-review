package com.smartcloset.image;

import java.awt.image.BufferedImage;
import java.util.*;
import org.springframework.http.HttpStatus;

public final class MaskProcessor {
    private MaskProcessor() {}
    public record ColorShare(String hex, double fraction) {}
    public record Processed(BufferedImage mask, BufferedImage cutout, BufferedImage cropped,
                            Region bounds, double foregroundRatio, List<ColorShare> colors,
                            List<String> warnings) {}

    public static Processed apply(BufferedImage image, BufferedImage mask) {
        int w = image.getWidth(), h = image.getHeight();
        if (mask.getWidth() != w || mask.getHeight() != h) throw new IllegalArgumentException("Mask dimensions differ");
        BufferedImage cutout = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int left = w, top = h, right = -1, bottom = -1, foreground = 0, colorCount = 0;
        long[][] bins = new long[4096][4];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int alpha = mask.getRaster().getSample(x, y, 0);
            // Suppress near-zero noise, but retain soft edges and genuine holes.
            if (alpha < 8) alpha = 0;
            if (alpha > 247) alpha = 255;
            mask.getRaster().setSample(x, y, 0, alpha);
            int rgb = image.getRGB(x, y) & 0xffffff;
            cutout.setRGB(x, y, alpha << 24 | rgb);
            if (alpha > 0) { left = Math.min(left, x); top = Math.min(top, y); right = Math.max(right, x); bottom = Math.max(bottom, y); }
            if (alpha >= 128) foreground++;
            if (alpha >= 230) {
                int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
                long[] bin = bins[(r >> 4) * 256 + (g >> 4) * 16 + (b >> 4)];
                bin[0]++; bin[1] += r; bin[2] += g; bin[3] += b; colorCount++;
            }
        }
        if (right < left || foreground == 0) throw new ProcessingException(HttpStatus.UNPROCESSABLE_ENTITY,
                "NO_FOREGROUND", "没有找到可用前景，请重新拍摄、调整框选或切换处理方法。");
        Region bounds = new Region(left, top, right - left + 1, bottom - top + 1);
        double ratio = (double) foreground / ((long) w * h);
        List<String> warnings = new ArrayList<>();
        if (ratio < .01) warnings.add("VERY_SMALL_FOREGROUND");
        if (ratio > .90) warnings.add("MOST_OF_IMAGE_IS_FOREGROUND");
        if (left == 0 || top == 0 || right == w - 1 || bottom == h - 1) warnings.add("FOREGROUND_TOUCHES_IMAGE_EDGE");
        Arrays.sort(bins, Comparator.comparingLong((long[] b) -> b[0]).reversed());
        List<ColorShare> colors = new ArrayList<>();
        for (int i = 0; i < 5 && bins[i][0] > 0; i++) {
            long[] b = bins[i];
            colors.add(new ColorShare(String.format("#%02X%02X%02X", b[1] / b[0], b[2] / b[0], b[3] / b[0]),
                    Math.round((double) b[0] / colorCount * 10000) / 10000.0));
        }
        return new Processed(mask, cutout, cutout.getSubimage(left, top, bounds.width(), bounds.height()),
                bounds, Math.round(ratio * 10000) / 10000.0, colors, warnings);
    }
}
