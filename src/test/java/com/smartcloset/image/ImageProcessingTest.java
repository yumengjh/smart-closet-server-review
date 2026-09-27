package com.smartcloset.image;

import com.smartcloset.ClosetProperties;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ImageProcessingTest {
    private final ClosetProperties properties = new ClosetProperties(Path.of("target/test-data"), Path.of("target/missing.onnx"), 12000000, 600, 2);

    public static BufferedImage fixture() {
        BufferedImage image = new BufferedImage(200, 240, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(240, 240, 240)); g.fillRect(0, 0, 200, 240);
        g.setColor(new Color(180, 25, 35));
        g.fillPolygon(new int[]{70, 40, 20, 50, 65, 65, 135, 135, 150, 180, 160, 130},
                new int[]{40, 50, 100, 115, 90, 200, 200, 90, 115, 100, 50, 40}, 12);
        g.setColor(new Color(240, 240, 240)); g.fillOval(87, 42, 26, 24);
        g.dispose(); return image;
    }

    @Test void rejectsTextEvenIfNamedJpeg() {
        assertThrows(ProcessingException.class, () -> Images.decode("not an image".getBytes(), 12000000));
    }

    @Test void rejectsOversizedDimensionsBeforeDecoding() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(fixture(), "png", bytes);
        var error = assertThrows(ProcessingException.class, () -> Images.decode(bytes.toByteArray(), 1000));
        assertTrue(error.getMessage().contains("总像素"));
    }

    @Test void rotatesAndMirrorsExifCoordinates() {
        BufferedImage image = new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.RED.getRGB()); image.setRGB(1, 2, Color.BLUE.getRGB());
        var rotated = Images.orient(image, 6);
        assertEquals(3, rotated.getWidth()); assertEquals(2, rotated.getHeight());
        assertEquals(Color.RED.getRGB(), rotated.getRGB(2, 0));
        assertEquals(Color.BLUE.getRGB(), rotated.getRGB(0, 1));
        assertEquals(Color.RED.getRGB(), Images.orient(image, 2).getRGB(1, 0));
        assertEquals(Color.RED.getRGB(), Images.orient(image, 7).getRGB(2, 1));
    }

    @Test void readsRealExifOrientationFromJpegUpload() throws Exception {
        BufferedImage image = new BufferedImage(40, 60, BufferedImage.TYPE_INT_RGB);
        var jpeg = new ByteArrayOutputStream(); ImageIO.write(image, "jpeg", jpeg);
        byte[] original = jpeg.toByteArray();
        // JPEG APP1 containing a little-endian TIFF IFD with Orientation=6.
        byte[] app1 = new byte[]{(byte) 0xff, (byte) 0xe1, 0, 34,
                'E', 'x', 'i', 'f', 0, 0, 'I', 'I', 42, 0, 8, 0, 0, 0,
                1, 0, 0x12, 1, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0};
        var uploaded = new ByteArrayOutputStream(); uploaded.write(original, 0, 2); uploaded.write(app1); uploaded.write(original, 2, original.length - 2);
        var decoded = Images.decode(uploaded.toByteArray(), 12000000);
        assertEquals(6, decoded.orientation());
        assertEquals(60, decoded.image().getWidth()); assertEquals(40, decoded.image().getHeight());
    }

    @Test void preservesRgbAndHolesWhileApplyingMask() {
        var image = fixture();
        BufferedImage mask = new BufferedImage(200, 240, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 40; y < 200; y++) for (int x = 60; x < 140; x++) mask.getRaster().setSample(x, y, 0, 255);
        mask.getRaster().setSample(90, 60, 0, 0);
        mask.getRaster().setSample(65, 45, 0, 120);
        var result = MaskProcessor.apply(image, mask);
        assertEquals(0, result.cutout().getRGB(90, 60) >>> 24);
        assertEquals(120, result.cutout().getRGB(65, 45) >>> 24);
        assertEquals(image.getRGB(80, 100) & 0xffffff, result.cutout().getRGB(80, 100) & 0xffffff);
        assertEquals(80, result.cropped().getWidth());
        assertEquals(160, result.cropped().getHeight());
    }

    @Test void emptyMaskIsAnExplicitFailure() {
        var error = assertThrows(ProcessingException.class,
                () -> MaskProcessor.apply(fixture(), new BufferedImage(200, 240, BufferedImage.TYPE_BYTE_GRAY)));
        assertEquals("NO_FOREGROUND", error.code());
    }

    @Test void grabcutNativeLibraryExtractsContrastingSubject() {
        var image = fixture();
        var result = new GrabCutEngine(properties).segment(image, new Region(10, 20, 180, 200));
        assertEquals(255, result.mask().getRaster().getSample(100, 150, 0));
        assertEquals(0, result.mask().getRaster().getSample(5, 5, 0));
        assertEquals(0, result.mask().getRaster().getSample(100, 52, 0));
    }

    @Test void invalidAndFullImageGrabcutRegionsAreRejected() {
        var engine = new GrabCutEngine(properties);
        assertThrows(ProcessingException.class, () -> engine.segment(fixture(), new Region(-1, 10, 50, 50)));
        assertThrows(ProcessingException.class, () -> engine.segment(fixture(), new Region(0, 0, 200, 240)));
        assertThrows(ProcessingException.class, () -> new Region(Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 100).validate(200, 240));
    }

    @Test void missingModelIsAnExplicit503() {
        var error = assertThrows(ProcessingException.class, () -> new U2NetEngine(properties).segment(fixture(), null));
        assertEquals("MODEL_NOT_INSTALLED", error.code());
        assertEquals(503, error.status().value());
    }
}
