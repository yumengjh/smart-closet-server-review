package com.smartcloset.storage;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.imageio.ImageIO;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.bytedeco.opencv.global.opencv_imgcodecs.*;
import static org.junit.jupiter.api.Assertions.*;

/** List thumbnails: smaller, WebP when accepted, transparency kept, refreshed when the original changes. */
class ImageVariantsTest {
    @TempDir Path dir;

    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }

    @Test void widthsSnapUpToAFewSizes() {
        assertEquals(160, ImageVariants.snap(1));
        assertEquals(320, ImageVariants.snap(161));
        assertEquals(480, ImageVariants.snap(480));
        assertEquals(960, ImageVariants.snap(5000));
    }

    @Test void transparentCutoutBecomesASmallWebpWithAlpha() throws Exception {
        var store = new LocalBlobStore(dir.resolve("data"));
        byte[] original = cutout(1600, 2000);
        store.put("workflows/a/step-2.png", original, "image/png");
        var variants = new ImageVariants(store, dir.resolve("cache"));

        var webp = variants.variant("workflows/a/step-2.png", 300, true);
        assertEquals("image/webp", webp.contentType());
        byte[] bytes = Files.readAllBytes(webp.file());
        assertTrue(bytes.length * 5 < original.length, bytes.length + " vs " + original.length);
        Mat decoded = imdecode(new Mat(new BytePointer(bytes)), IMREAD_UNCHANGED);
        assertEquals(320, decoded.cols());
        assertEquals(400, decoded.rows());
        assertEquals(4, decoded.channels(), "transparent background survives");

        // Without WebP support the cutout stays a transparent PNG.
        var png = variants.variant("workflows/a/step-2.png", 300, false);
        assertEquals("image/png", png.contentType());
        var image = ImageIO.read(png.file().toFile());
        assertEquals(320, image.getWidth());
        assertEquals(0, image.getRGB(0, 0) >>> 24, "corner is still transparent");
    }

    @Test void rewrittenOriginalGetsAFreshCopyAndSmallImagesAreNotEnlarged() throws Exception {
        var store = new LocalBlobStore(dir.resolve("data"));
        store.put("workflows/b/original.jpg", jpeg(200, 100), "image/jpeg");
        var variants = new ImageVariants(store, dir.resolve("cache"));
        var first = variants.variant("workflows/b/original.jpg", 480, false);
        assertEquals("image/jpeg", first.contentType());
        assertEquals(200, ImageIO.read(first.file().toFile()).getWidth());
        assertEquals(first.etag(), variants.variant("workflows/b/original.jpg", 480, false).etag(), "second request reuses the copy");

        Files.setLastModifiedTime(store.path("workflows/b/original.jpg"), java.nio.file.attribute.FileTime.fromMillis(1000));
        store.put("workflows/b/original.jpg", jpeg(240, 100), "image/jpeg");
        var second = variants.variant("workflows/b/original.jpg", 480, false);
        assertNotEquals(first.etag(), second.etag());
        assertEquals(240, ImageIO.read(second.file().toFile()).getWidth());
        assertNull(variants.variant("workflows/missing/original.jpg", 480, false));
    }

    @Test void storedMediaServesCopiesWithRevalidationHeaders() throws Exception {
        var store = new LocalBlobStore(dir.resolve("data"));
        store.put("workflows/c/step-1.png", cutout(800, 1000), "image/png");
        store.put("workflows/c/input.png", "not an image".getBytes(), "image/png");
        var media = new StoredMedia(store, StorageSettings.of(Map.<String, String>of()::get), new ImageVariants(store, dir.resolve("cache")));
        var request = new MockHttpServletRequest("GET", "/api/workflows/c/files/step-1.png");
        request.addHeader("Accept", "image/avif,image/webp,*/*");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        var thumb = media.shared("workflows/c/step-1.png", 320);
        assertEquals(200, thumb.getStatusCode().value());
        assertEquals("image/webp", thumb.getHeaders().getContentType().toString());
        assertEquals("private, max-age=86400", thumb.getHeaders().getCacheControl());
        assertEquals("Accept", thumb.getHeaders().getFirst("Vary"));
        assertNotNull(thumb.getHeaders().getETag());
        assertInstanceOf(FileSystemResource.class, thumb.getBody());

        var original = media.shared("workflows/c/step-1.png", null);
        assertEquals("private, max-age=86400", original.getHeaders().getCacheControl());
        assertNotNull(original.getHeaders().getETag(), "local originals revalidate too");

        // A file that does not decode still shows as itself rather than failing the card.
        var fallback = media.shared("workflows/c/input.png", 320);
        assertEquals(200, fallback.getStatusCode().value());
        assertEquals("image/png", fallback.getHeaders().getContentType().toString());
    }

    static byte[] cutout(int w, int h) throws Exception {
        var image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        // Noise-free but detailed enough that PNG is not trivially small.
        for (int y = h / 8; y < h * 7 / 8; y += 4) { g.setColor(new Color((y * 37) & 255, (y * 11) & 255, 160)); g.fillRect(w / 6, y, w * 2 / 3, 3); }
        g.dispose();
        var out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out); return out.toByteArray();
    }

    static byte[] jpeg(int w, int h) throws Exception {
        var image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics(); g.setColor(Color.ORANGE); g.fillRect(0, 0, w, h); g.dispose();
        var out = new ByteArrayOutputStream(); ImageIO.write(image, "jpg", out); return out.toByteArray();
    }
}
