package com.smartcloset.image;

import java.awt.image.BufferedImage;
import java.util.*;
import org.springframework.stereotype.Component;

/** Structural heuristics only: cannot prove that a foreground is a complete garment. Never mutates pixels. */
@Component
public class TransparencyInspector {
    public static final String RULE_VERSION = "alpha-v1";
    public static final int TRANSPARENT_MAX = 7, OPAQUE_MIN = 248, FOREGROUND_MIN = 128;
    public static final double MIN_TRANSPARENT_RATIO = .005, MIN_EXTERIOR_RATIO = .01,
            MIN_FOREGROUND_RATIO = .01, MAX_FOREGROUND_RATIO = .90,
            MAX_SEMITRANSPARENT_RATIO = .20, MIN_DOMINANT_COMPONENT_RATIO = .95;
    public enum Verdict { USABLE, OPAQUE, SUSPICIOUS, EMPTY }
    public record Report(String ruleVersion, Verdict verdict, boolean hasAlpha, int width, int height,
                         double transparentRatio, double semiTransparentRatio, double foregroundRatio,
                         Double exteriorTransparentRatio, Double dominantComponentRatio,
                         Integer foregroundComponents, boolean foregroundTouchesEdge, List<String> reasons) {}

    public Report inspect(BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight(), count = Math.multiplyExact(w, h);
        byte[] alpha = new byte[count];
        int transparent = 0, semi = 0, foreground = 0, visible = 0;
        boolean touches = false;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int a = image.getRGB(x, y) >>> 24; alpha[y * w + x] = (byte) a;
            if (a <= TRANSPARENT_MAX) transparent++; else {
                visible++; if (a < OPAQUE_MIN) semi++;
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) touches = true;
            }
            if (a >= FOREGROUND_MIN) foreground++;
        }
        double tr = transparent / (double) count, sr = semi / (double) count, fr = foreground / (double) count;
        boolean empty = visible == 0 || foreground == 0;
        boolean opaque = tr < MIN_TRANSPARENT_RATIO && sr < MIN_TRANSPARENT_RATIO;
        if (empty || opaque) return new Report(RULE_VERSION, empty ? Verdict.EMPTY : Verdict.OPAQUE,
                image.getColorModel().hasAlpha(), w, h, tr, sr, fr, null, null, null, touches,
                List.of(empty ? "NO_EFFECTIVE_FOREGROUND" : "NO_EFFECTIVE_TRANSPARENCY"));
        byte[] visited = new byte[count]; int[] queue = new int[count]; int tail = 0;
        for (int x = 0; x < w; x++) {
            tail = enqueue(x, alpha, visited, queue, tail, true);
            tail = enqueue((h - 1) * w + x, alpha, visited, queue, tail, true);
        }
        for (int y = 0; y < h; y++) {
            tail = enqueue(y * w, alpha, visited, queue, tail, true);
            tail = enqueue(y * w + w - 1, alpha, visited, queue, tail, true);
        }
        for (int head = 0; head < tail; head++) {
            int i = queue[head], x = i % w;
            if (x > 0) tail = enqueue(i - 1, alpha, visited, queue, tail, true);
            if (x + 1 < w) tail = enqueue(i + 1, alpha, visited, queue, tail, true);
            if (i >= w) tail = enqueue(i - w, alpha, visited, queue, tail, true);
            if (i + w < count) tail = enqueue(i + w, alpha, visited, queue, tail, true);
        }
        double exterior = tail / (double) count;
        int components = 0, largest = 0;
        for (int seed = 0; seed < count; seed++) {
            if ((alpha[seed] & 255) < FOREGROUND_MIN || (visited[seed] & 2) != 0) continue;
            components++; tail = enqueue(seed, alpha, visited, queue, 0, false);
            for (int head = 0; head < tail; head++) {
                int i = queue[head], x = i % w, y = i / w;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    if (x + dx >= 0 && x + dx < w && y + dy >= 0 && y + dy < h)
                        tail = enqueue((y + dy) * w + x + dx, alpha, visited, queue, tail, false);
                }
            }
            largest = Math.max(largest, tail);
        }
        double dominant = largest / (double) foreground;
        var reasons = new ArrayList<String>();
        if (exterior < MIN_EXTERIOR_RATIO) reasons.add("INSUFFICIENT_EXTERIOR_TRANSPARENCY");
        if (fr < MIN_FOREGROUND_RATIO) reasons.add("VERY_SMALL_FOREGROUND");
        if (fr > MAX_FOREGROUND_RATIO) reasons.add("MOST_OF_IMAGE_IS_FOREGROUND");
        if (touches) reasons.add("FOREGROUND_TOUCHES_IMAGE_EDGE");
        if (sr > MAX_SEMITRANSPARENT_RATIO) reasons.add("LARGE_SEMITRANSPARENT_AREA");
        if (dominant < MIN_DOMINANT_COMPONENT_RATIO) reasons.add("FRAGMENTED_FOREGROUND");
        return new Report(RULE_VERSION, reasons.isEmpty() ? Verdict.USABLE : Verdict.SUSPICIOUS,
                image.getColorModel().hasAlpha(), w, h, tr, sr, fr, exterior, dominant, components, touches, List.copyOf(reasons));
    }
    private int enqueue(int i, byte[] alpha, byte[] visited, int[] queue, int tail, boolean background) {
        int bit = background ? 1 : 2;
        if ((visited[i] & bit) == 0 && (background ? (alpha[i] & 255) <= TRANSPARENT_MAX : (alpha[i] & 255) >= FOREGROUND_MIN)) {
            visited[i] |= (byte) bit; queue[tail++] = i;
        }
        return tail;
    }
}
