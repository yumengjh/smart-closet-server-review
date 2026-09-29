package com.smartcloset.image;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import static org.bytedeco.opencv.global.opencv_core.CV_8UC1;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

/** Display-only derivative. Never changes the segmentation mask or saved cutout. */
@Component
public class StickerRenderer {
    // 展示参数集中在这里调整；修改后同步升级 STYLE，避免复用旧样式缓存。
    public static final String STYLE = "bold-v2";
    public static final int SIZE = 1024;
    private static final int PADDING = 88;                // 衣物到画布边缘的最小距离
    private static final int OUTLINE = 32;                // 外描边宽度，输出像素
    private static final int OUTLINE_RGB = 0xfffdf9;       // 暖白纸边
    private static final int SHADOW_RGB = 0x20252b;
    private static final float SHADOW_OPACITY = .32f;     // 0～1
    private static final int SHADOW_OFFSET_X = 8;
    private static final int SHADOW_OFFSET_Y = 18;
    private static final double SHADOW_SIGMA = 10;        // 越大，阴影越柔和
    public record Rendered(BufferedImage image, Region contentBounds) {}

    public Rendered render(BufferedImage cutout) {
        Region bounds = bounds(cutout);
        double scale = (double) (SIZE - 2 * PADDING) / Math.max(bounds.width(), bounds.height());
        int w = Math.max(1, (int) Math.round(bounds.width() * scale));
        int h = Math.max(1, (int) Math.round(bounds.height() * scale));
        Region content = new Region((SIZE - w) / 2, (SIZE - h) / 2, w, h);
        // ARGB_PRE prevents hidden RGB in transparent input from creating dark resize fringes.
        BufferedImage garment = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = garment.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(cutout, content.x(), content.y(), content.x() + w, content.y() + h,
                    bounds.x(), bounds.y(), bounds.x() + bounds.width(), bounds.y() + bounds.height(), null);
        } finally { g.dispose(); }
        int[] pixels = garment.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE);
        byte[] alpha = new byte[pixels.length];
        for (int i = 0; i < pixels.length; i++) alpha[i] = (byte) (pixels[i] >>> 24);
        boolean[] exterior = exterior(alpha);
        byte[] expanded = new byte[alpha.length], blurred = new byte[alpha.length];
        try (Mat mask = new Mat(SIZE, SIZE, CV_8UC1); Mat outline = new Mat(); Mat shadow = new Mat();
             Size kernelSize = new Size(OUTLINE * 2 + 1, OUTLINE * 2 + 1);
             Size blurSize = new Size(2 * (int) Math.ceil(3 * SHADOW_SIGMA) + 1, 2 * (int) Math.ceil(3 * SHADOW_SIGMA) + 1);
             Mat kernel = getStructuringElement(MORPH_ELLIPSE, kernelSize)) {
            mask.data().put(alpha);
            dilate(mask, outline, kernel);
            GaussianBlur(outline, shadow, blurSize, SHADOW_SIGMA);
            outline.data().get(expanded);
            shadow.data().get(blurred);
        }
        BufferedImage backing = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        BufferedImage stroke = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < pixels.length; i++) {
            // Only decorate background connected to the canvas edge. Enclosed garment holes stay clear.
            if (!exterior[i]) continue;
            int y = i / SIZE, x = i % SIZE;
            int sx = x - SHADOW_OFFSET_X, sy = y - SHADOW_OFFSET_Y;
            int shadowAlpha = sx >= 0 && sx < SIZE && sy >= 0 && sy < SIZE
                    ? Math.round((blurred[sy * SIZE + sx] & 255) * SHADOW_OPACITY) : 0;
            backing.setRGB(x, y, shadowAlpha << 24 | SHADOW_RGB);
            stroke.setRGB(x, y, (expanded[i] & 255) << 24 | OUTLINE_RGB);
        }
        Graphics2D layers = backing.createGraphics();
        try { layers.drawImage(stroke, 0, 0, null); layers.drawImage(garment, 0, 0, null); }
        finally { layers.dispose(); }
        return new Rendered(backing, content);
    }

    private Region bounds(BufferedImage image) {
        int left = image.getWidth(), top = image.getHeight(), right = -1, bottom = -1;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            if ((image.getRGB(x, y) >>> 24) < 8) continue;
            left = Math.min(left, x); right = Math.max(right, x);
            top = Math.min(top, y); bottom = Math.max(bottom, y);
        }
        if (right < left) throw new ProcessingException(HttpStatus.UNPROCESSABLE_ENTITY,
                "NO_FOREGROUND", "抠图没有可见衣物，无法生成贴纸。");
        return new Region(left, top, right - left + 1, bottom - top + 1);
    }

    private boolean[] exterior(byte[] alpha) {
        boolean[] visited = new boolean[alpha.length];
        int[] queue = new int[alpha.length];
        int head = 0, tail = 1; queue[0] = 0; visited[0] = true;
        // Normalization guarantees transparent padding around the entire canvas.
        while (head < tail) {
            int i = queue[head++], x = i % SIZE;
            if (x > 0) tail = enqueue(i - 1, alpha, visited, queue, tail);
            if (x + 1 < SIZE) tail = enqueue(i + 1, alpha, visited, queue, tail);
            if (i >= SIZE) tail = enqueue(i - SIZE, alpha, visited, queue, tail);
            if (i + SIZE < alpha.length) tail = enqueue(i + SIZE, alpha, visited, queue, tail);
        }
        return visited;
    }

    private int enqueue(int i, byte[] alpha, boolean[] visited, int[] queue, int tail) {
        if (!visited[i] && (alpha[i] & 255) < 8) { visited[i] = true; queue[tail++] = i; }
        return tail;
    }
}
