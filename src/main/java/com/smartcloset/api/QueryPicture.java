package com.smartcloset.api;

import com.smartcloset.agent.OutfitRules;
import com.smartcloset.image.Images;
import com.smartcloset.image.ProcessingException;
import com.smartcloset.image.SegmentationEngine;
import com.smartcloset.image.U2NetEngine;
import com.smartcloset.similar.ClipModels;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;
import org.springframework.web.multipart.MultipartFile;

/** A picture someone searches with: decoded, downsized, matted locally (U²-Net, else GrabCut) and cleaned of leftover background. */
public final class QueryPicture {
    public record Prepared(BufferedImage garment, String method) {}
    static final long MAX_BYTES = 15L * 1024 * 1024;
    private QueryPicture() {}

    public static Prepared prepare(MultipartFile image, List<SegmentationEngine> engines) throws IOException {
        if (image == null || image.isEmpty() || image.getSize() > MAX_BYTES) throw ProcessingException.invalid("图片不能为空，且不能超过 15 MB。");
        var decoded = Images.sampled(image.getBytes(), 640).image();   // matting time grows with size; 640 is plenty for colour, silhouette and the model
        if (transparent(decoded)) return new Prepared(decoded, "TRANSPARENT");
        var engine = engines.stream().filter(e -> e instanceof U2NetEngine u && u.modelPresent()).findFirst()
            .or(() -> engines.stream().filter(e -> e.method() == SegmentationEngine.Method.GRABCUT).findFirst()).orElse(null);
        if (engine == null) return new Prepared(decoded, "NONE");
        return new Prepared(cutout(decoded, engine.segment(decoded, null).mask()), engine.method().name());
    }

    /** Image vector of the matted picture, or null when the image-text model cannot be used now (callers fall back). */
    static float[] vector(Prepared picture, ClipModels models) {
        try { return models.encodeImage(picture.garment()); } catch (ClipModels.Unavailable e) { return null; }
    }

    static OutfitRules.Role role(String role) {
        if (role == null || role.isBlank()) return null;
        try { return OutfitRules.Role.valueOf(role.strip().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { throw ProcessingException.invalid("未知的位置：" + role); }
    }

    public static boolean transparent(BufferedImage image) {
        if (!image.getColorModel().hasAlpha()) return false;
        for (int y = 0; y < image.getHeight(); y += 4) for (int x = 0; x < image.getWidth(); x += 4) if ((image.getRGB(x, y) >>> 24) < 250) return true;
        return false;
    }

    /**
     * Applies the matting mask, then drops kept pixels that are close to the background colour (median of the image
     * border): GrabCut tends to keep patches of background next to the garment, which would add a false colour to the
     * palette. When that would remove most of the garment (a garment the colour of its background), the mask is kept as is.
     */
    public static BufferedImage cutout(BufferedImage image, BufferedImage mask) {
        int w = image.getWidth(), h = image.getHeight();
        var raster = mask.getRaster();
        var border = new ArrayList<Integer>();
        for (int x = 0; x < w; x++) { border.add(image.getRGB(x, 0)); border.add(image.getRGB(x, h - 1)); }
        for (int y = 0; y < h; y++) { border.add(image.getRGB(0, y)); border.add(image.getRGB(w - 1, y)); }
        int br = median(border, 16), bg = median(border, 8), bb = median(border, 0);
        int[] alpha = new int[w * h]; int kept = 0, cleaned = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int a = raster.getSample(Math.min(x, mask.getWidth() - 1), Math.min(y, mask.getHeight() - 1), 0);
            alpha[y * w + x] = a;
            if (a < 128) continue;
            kept++;
            int p = image.getRGB(x, y), dr = (p >> 16 & 255) - br, dg = (p >> 8 & 255) - bg, db = (p & 255) - bb;
            if (dr * dr + dg * dg + db * db < 30 * 30) { alpha[y * w + x] = -a; cleaned++; }
        }
        boolean clean = kept > 0 && cleaned < kept * 0.6;
        var out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int a = alpha[y * w + x]; a = a < 0 ? (clean ? 0 : -a) : a;
            out.setRGB(x, y, (a << 24) | (image.getRGB(x, y) & 0xFFFFFF));
        }
        return out;
    }
    private static int median(List<Integer> pixels, int shift) { var v = pixels.stream().mapToInt(p -> p >> shift & 255).sorted().toArray(); return v[v.length / 2]; }
}
