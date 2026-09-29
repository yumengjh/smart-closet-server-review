package com.smartcloset.image;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RotationTest {
    @Test void positiveAnglesAreClockwiseAndQuarterTurnsKeepPixelsExact() {
        var input = new BufferedImage(40, 60, BufferedImage.TYPE_INT_RGB);
        input.setRGB(5, 10, Color.RED.getRGB()); input.setRGB(30, 45, Color.BLUE.getRGB());
        var clockwise = Images.rotate(input, 90, 12000000);
        assertEquals(60, clockwise.getWidth()); assertEquals(40, clockwise.getHeight());
        assertEquals(Color.RED.getRGB(), clockwise.getRGB(49, 5));
        assertEquals(Color.BLUE.getRGB(), clockwise.getRGB(14, 30));
        var counterclockwise = Images.rotate(input, -90, 12000000);
        assertEquals(Color.RED.getRGB(), counterclockwise.getRGB(10, 34));
        var back = Images.rotate(clockwise, -90, 12000000);
        assertArrayEquals(input.getRGB(0, 0, 40, 60, null, 0, 40), back.getRGB(0, 0, 40, 60, null, 0, 40));
        assertSame(input, Images.rotate(input, 360, 12000000));
    }

    @Test void arbitraryAngleExpandsCanvasAndFillsNewCornersWhite() {
        var input = new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB);
        var g = input.createGraphics(); g.setColor(Color.RED); g.fillRect(0, 0, 80, 40); g.dispose();
        var output = Images.rotate(input, 45, 12000000);
        assertEquals(85, output.getWidth()); assertEquals(85, output.getHeight());
        assertEquals(Color.WHITE.getRGB(), output.getRGB(0, 0));
        assertEquals(Color.RED.getRGB(), output.getRGB(42, 42));
        long redPixels = 0;
        for (int y = 0; y < 85; y++) for (int x = 0; x < 85; x++) {
            if ((output.getRGB(x, y) & 0x00ffff) < 0x004040) redPixels++;
        }
        assertTrue(redPixels > 3000, "Expanding the canvas must retain the rotated rectangle");
        assertEquals(Color.RED.getRGB(), input.getRGB(0, 0));
    }

    @Test void rejectsNonFiniteOutOfRangeAndOversizedRotations() {
        var input = new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB);
        for (double angle : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 361, -361})
            assertThrows(ProcessingException.class, () -> Images.rotate(input, angle, 12000000));
        assertThrows(ProcessingException.class, () -> Images.rotate(input, 45, 4000));
    }

    @Test void appliesUserAngleAfterExifAndSavedPngHasNoSecondExifRotation() throws Exception {
        var image = new BufferedImage(40, 60, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0, 0, 40, 60);
        g.setColor(Color.RED); g.fillRect(2, 2, 12, 12); g.dispose();
        var bytes = new ByteArrayOutputStream(); ImageIO.write(image, "jpeg", bytes);
        byte[] jpeg = bytes.toByteArray();
        byte[] app1 = {(byte) 0xff, (byte) 0xe1, 0, 34, 'E','x','i','f',0,0,'I','I',42,0,8,0,0,0,
                1,0,0x12,1,3,0,1,0,0,0,6,0,0,0,0,0,0,0};
        bytes.reset(); bytes.write(jpeg, 0, 2); bytes.write(app1); bytes.write(jpeg, 2, jpeg.length - 2);
        var decoded = Images.decode(bytes.toByteArray(), 12000000);
        var corrected = Images.rotate(decoded.image(), 90, 12000000);
        assertEquals(40, corrected.getWidth()); assertEquals(60, corrected.getHeight());
        var color = new Color(corrected.getRGB(32, 52)); assertTrue(color.getRed() > 180 && color.getGreen() < 80);
        bytes.reset(); ImageIO.write(corrected, "png", bytes);
        var saved = Images.decode(bytes.toByteArray(), 12000000);
        assertEquals(1, saved.orientation());
        assertEquals(corrected.getRGB(32, 52), saved.image().getRGB(32, 52));
    }
}
