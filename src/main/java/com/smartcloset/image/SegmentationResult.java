package com.smartcloset.image;

import java.time.Instant;
import java.util.List;
import com.smartcloset.image.MaskProcessor.ColorShare;
import com.smartcloset.image.SegmentationEngine.Method;

public record SegmentationResult(String id, Instant createdAt, Method method, String model,
                                 int width, int height, int exifOrientation, Region requestedRegion,
                                 Region foregroundBounds, long processingMillis, double foregroundRatio,
                                 List<ColorShare> dominantColors, List<String> warnings,
                                 String sourceUrl, String originalUrl, String maskUrl,
                                 String cutoutUrl, String croppedUrl) {}
