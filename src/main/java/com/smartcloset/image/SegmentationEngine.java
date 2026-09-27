package com.smartcloset.image;

import java.awt.image.BufferedImage;
import java.util.List;

public interface SegmentationEngine {
    enum Method { GRABCUT, U2NET }
    Method method();
    MaskResult segment(BufferedImage image, Region region);
    record MaskResult(BufferedImage mask, List<String> warnings) {}
}
