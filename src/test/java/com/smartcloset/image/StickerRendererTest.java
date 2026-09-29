package com.smartcloset.image;

import java.awt.*;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StickerRendererTest {
    private final StickerRenderer renderer = new StickerRenderer();

    private BufferedImage rectangle(int width, int height, int x, int y, int w, int h) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics(); g.setColor(new Color(39, 92, 171)); g.fillRect(x, y, w, h); g.dispose();
        return image;
    }

    @Test void preservesGarmentColorHolesAndSourcePixels() {
        var source = rectangle(200, 200, 40, 40, 120, 120);
        for (int y = 90; y < 110; y++) for (int x = 90; x < 110; x++) source.setRGB(x, y, 0x00ff0000);
        int[] before = source.getRGB(0, 0, 200, 200, null, 0, 200);
        var output = renderer.render(source).image();
        assertEquals(1024, output.getWidth()); assertEquals(1024, output.getHeight());
        assertEquals(0, output.getRGB(512, 512) >>> 24, "Enclosed hole must not be filled by outline or shadow");
        assertEquals(new Color(39, 92, 171).getRGB(), output.getRGB(300, 300));
        assertArrayEquals(before, source.getRGB(0, 0, 200, 200, null, 0, 200));
    }

    @Test void addsExternalOutlineAndShadowWithoutClippingCanvas() {
        var output = renderer.render(rectangle(200, 200, 40, 40, 120, 120)).image();
        int stroke = output.getRGB(64, 512);
        assertEquals(255, stroke >>> 24); assertEquals(0xfffdf9, stroke & 0xffffff);
        int shadow = output.getRGB(48, 512) >>> 24;
        assertTrue(shadow > 0 && shadow < 64, "Outside the stroke there should be a faint shadow");
        for (int i = 0; i < 1024; i++) {
            assertEquals(0, output.getRGB(0, i) >>> 24); assertEquals(0, output.getRGB(1023, i) >>> 24);
            assertEquals(0, output.getRGB(i, 0) >>> 24); assertEquals(0, output.getRGB(i, 1023) >>> 24);
        }
    }

    @Test void normalizesDifferentInputMarginsToSameStickerAndKeepsAspectRatio() {
        var a = renderer.render(rectangle(100, 200, 20, 30, 60, 120));
        var b = renderer.render(rectangle(300, 250, 100, 50, 60, 120));
        assertEquals(2, (double) a.contentBounds().height() / a.contentBounds().width(), .01);
        assertEquals(a.contentBounds(), b.contentBounds());
        assertArrayEquals(a.image().getRGB(0, 0, 1024, 1024, null, 0, 1024), b.image().getRGB(0, 0, 1024, 1024, null, 0, 1024));
        var landscape = renderer.render(rectangle(200, 100, 30, 20, 120, 60));
        assertEquals(2, (double) landscape.contentBounds().width() / landscape.contentBounds().height(), .01);
    }

    @Test void preservesTranslucentInteriorInsteadOfBackingItWithWhite() {
        var source = rectangle(100, 100, 10, 10, 80, 80);
        for (int y = 30; y < 70; y++) for (int x = 30; x < 70; x++) source.setRGB(x, y, 0x802060a0);
        int pixel = renderer.render(source).image().getRGB(512, 512);
        assertEquals(128, pixel >>> 24);
        // Premultiplied 8-bit alpha can round an RGB channel by one level on conversion.
        for (int shift : new int[]{16, 8, 0})
            assertEquals((0x2060a0 >> shift) & 255, (pixel >> shift) & 255, 1);
    }

    @Test void refusesAnEmptyCutout() {
        var error = assertThrows(ProcessingException.class, () -> renderer.render(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)));
        assertEquals("NO_FOREGROUND", error.code());
    }
}
