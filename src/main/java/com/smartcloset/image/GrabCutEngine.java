package com.smartcloset.image;

import com.smartcloset.ClosetProperties;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import org.bytedeco.javacpp.indexer.UByteIndexer;
import org.bytedeco.opencv.opencv_core.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

@Component
public class GrabCutEngine implements SegmentationEngine {
    private final int maxSide;
    public GrabCutEngine(ClosetProperties properties) { this.maxSide = properties.grabcutMaxSide(); }
    public Method method() { return Method.GRABCUT; }

    public MaskResult segment(BufferedImage original, Region region) {
        int ow = original.getWidth(), oh = original.getHeight();
        if (region != null) region.validate(ow, oh);
        double scale = Math.min(1.0, (double) maxSide / Math.max(ow, oh));
        int w = Math.max(2, (int) Math.round(ow * scale)), h = Math.max(2, (int) Math.round(oh * scale));
        BufferedImage image = Images.resize(original, w, h);
        var warnings = new ArrayList<String>();
        int x, y, right, bottom;
        if (region == null) {
            x = Math.max(1, w / 33); y = Math.max(1, h / 33);
            right = w - x; bottom = h - y;
            warnings.add("GRABCUT_ASSUMES_BORDER_IS_BACKGROUND");
        } else {
            x = (int) Math.floor((double) region.x() * w / ow);
            y = (int) Math.floor((double) region.y() * h / oh);
            right = Math.min(w, (int) Math.ceil((double) (region.x() + region.width()) * w / ow));
            bottom = Math.min(h, (int) Math.ceil((double) (region.y() + region.height()) * h / oh));
        }
        if ((x == 0 && y == 0 && right == w && bottom == h) || right - x < 2 || bottom - y < 2)
            throw ProcessingException.invalid("GrabCut 框选必须留下部分框外背景，且缩放后的区域不能小于 2 像素。");
        try (Mat rgb = new Mat(h, w, CV_8UC3);
             Mat labels = new Mat(); Mat bg = new Mat(); Mat fg = new Mat();
             Rect rect = new Rect(x, y, right - x, bottom - y)) {
            setNumThreads(2);
            setRNGSeed(42);
            try (UByteIndexer pixels = rgb.createIndexer()) {
                for (int row = 0; row < h; row++) for (int col = 0; col < w; col++) {
                    int pixel = image.getRGB(col, row);
                    pixels.put(row, col, 0, pixel & 255);
                    pixels.put(row, col, 1, pixel >> 8 & 255);
                    pixels.put(row, col, 2, pixel >> 16 & 255);
                }
            }
            grabCut(rgb, labels, rect, bg, fg, 5, GC_INIT_WITH_RECT);
            BufferedImage mask = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
            try (UByteIndexer values = labels.createIndexer()) {
                for (int row = 0; row < h; row++) for (int col = 0; col < w; col++) {
                    int label = values.get(row, col);
                    mask.getRaster().setSample(col, row, 0, label == GC_FGD || label == GC_PR_FGD ? 255 : 0);
                }
            }
            return new MaskResult(Images.resize(mask, ow, oh), warnings);
        } catch (RuntimeException e) {
            if (e instanceof ProcessingException p) throw p;
            throw new ProcessingException(HttpStatus.UNPROCESSABLE_ENTITY, "SEGMENTATION_FAILED",
                    "GrabCut 无法分离前景，请调整框选、背景或尝试 U2NET。");
        }
    }
}
