package com.smartcloset.storage;

import com.smartcloset.ClosetProperties;
import com.smartcloset.image.Images;
import com.smartcloset.image.ProcessingException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;
import org.springframework.stereotype.Component;
import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgcodecs.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

/**
 * Smaller copies of garment and processing images for lists and previews: scaled to one of a few widths and encoded
 * as WebP when the browser accepts it (PNG keeps transparency, JPEG otherwise). The original stays untouched and is
 * still what downloads, the outfit canvas and processing use.
 *
 * <p>Copies are a cache on the local disk whatever stores the originals: they are named after the original's key and
 * version, so a rewritten original gets a fresh copy, and the whole directory can be deleted at any time.
 */
@Component
public class ImageVariants {
    /** Requested widths snap up to one of these, so a handful of copies serve every layout. */
    static final int[] WIDTHS = {160, 320, 480, 720, 960};
    private static final int WEBP_QUALITY = 82, JPEG_QUALITY = 85;

    public record Variant(Path file, String contentType, String etag) {}

    private final BlobStore store;
    private final Path root;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    // Scaling is CPU work on the request thread; a first visit to a full wardrobe must not take every core.
    private final Semaphore encoders = new Semaphore(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));

    @org.springframework.beans.factory.annotation.Autowired
    public ImageVariants(BlobStore store, ClosetProperties properties) {
        this(store, properties.storageDir().toAbsolutePath().normalize().resolve("cache").resolve("image-variants"));
    }
    ImageVariants(BlobStore store, Path root) { this.store = store; this.root = root; }

    public static int snap(int width) {
        for (int allowed : WIDTHS) if (width <= allowed) return allowed;
        return WIDTHS[WIDTHS.length - 1];
    }

    /** The cached copy, created on first use; null when the original does not exist. */
    public Variant variant(String key, int width, boolean webp) throws IOException {
        String version = store.version(key);
        if (version == null) return null;
        int target = snap(width);
        String name = hash(key + "|" + version) + "-w" + target;
        String extension = webp ? "webp" : null;
        if (extension == null) {
            // Without WebP keep the original's family: transparent PNG stays PNG, everything else becomes JPEG.
            extension = BlobStore.contentType(key).equals("image/jpeg") ? "jpg" : "png";
        }
        Path file = root.resolve(name.substring(0, 2)).resolve(name + "." + extension);
        String etag = "\"" + name + "." + extension + "\"";
        String contentType = BlobStore.contentType(file.toString());
        if (Files.isRegularFile(file)) return new Variant(file, contentType, etag);
        synchronized (locks.computeIfAbsent(file.toString(), k -> new Object())) {
            try {
                if (Files.isRegularFile(file)) return new Variant(file, contentType, etag);
                byte[] original = store.get(key);
                if (original == null) return null;
                byte[] encoded;
                encoders.acquireUninterruptibly();
                try { encoded = encode(Images.sampledForWidth(original, target).image(), target, extension); }
                finally { encoders.release(); }
                write(file, encoded);
                return new Variant(file, contentType, etag);
            } finally { locks.remove(file.toString()); }
        }
    }

    static byte[] encode(BufferedImage image, int width, String extension) {
        boolean alpha = image.getColorModel().hasAlpha() && !extension.equals("jpg");
        int w = image.getWidth(), h = image.getHeight();
        int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
        int channels = alpha ? 4 : 3;
        byte[] pixels = new byte[w * h * channels];
        for (int i = 0, o = 0; i < argb.length; i++) {
            int p = argb[i], a = p >>> 24;
            int r = p >> 16 & 255, g = p >> 8 & 255, b = p & 255;
            if (!alpha && a < 255) { // JPEG has no transparency: flatten onto white like the processing engines do.
                r = (r * a + 255 * (255 - a) + 127) / 255; g = (g * a + 255 * (255 - a) + 127) / 255; b = (b * a + 255 * (255 - a) + 127) / 255;
            }
            pixels[o++] = (byte) b; pixels[o++] = (byte) g; pixels[o++] = (byte) r;
            if (alpha) pixels[o++] = (byte) a;
        }
        try (Mat source = new Mat(h, w, alpha ? CV_8UC4 : CV_8UC3); Mat scaled = new Mat(); BytePointer out = new BytePointer()) {
            source.data().put(pixels);
            Mat ready = source;
            if (w > width) {
                // INTER_AREA averages the dropped pixels: no shimmering edges on knit and stripes.
                resize(source, scaled, new Size(width, Math.max(1, (int) Math.round((double) h * width / w))), 0, 0, INTER_AREA);
                ready = scaled;
            }
            int[] parameters = switch (extension) {
                case "webp" -> new int[] {IMWRITE_WEBP_QUALITY, WEBP_QUALITY};
                case "jpg" -> new int[] {IMWRITE_JPEG_QUALITY, JPEG_QUALITY, IMWRITE_JPEG_PROGRESSIVE, 1};
                default -> new int[] {IMWRITE_PNG_COMPRESSION, 6};
            };
            if (!imencode("." + extension, ready, out, new IntPointer(parameters)))
                throw new ProcessingException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "VARIANT_FAILED", "缩略图生成失败。");
            byte[] bytes = new byte[(int) out.limit()];
            out.get(bytes);
            return bytes;
        }
    }

    private static void write(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = Files.createTempFile(file.getParent(), ".pending-", ".tmp");
        try {
            Files.write(tmp, bytes);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
    }

    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 32); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
