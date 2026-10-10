package com.smartcloset.intake;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

/** Issue #1: a cream tee on a white background lost its lower half to a milky half-transparent ghost. */
class CutoutCheckTest {
    private static BufferedImage shirt(int lowerAlpha) {
        var img = new BufferedImage(800, 800, BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(240, 232, 220, 255)); g.fill(new Ellipse2D.Double(150, 100, 500, 330));      // opaque upper body, soft edge
        g.setColor(new Color(240, 232, 220, lowerAlpha)); g.fillRect(200, 360, 400, 380);               // lower body
        g.dispose();
        return img;
    }

    @Test void aCleanCutoutPasses() {
        var r = CutoutCheck.check(shirt(255));
        assertFalse(r.damaged(), r.toString());
        assertTrue(r.ghostRatio() < 0.05, r.toString());
    }

    @Test void aMilkyLowerHalfIsFlagged() {
        var r = CutoutCheck.check(shirt(70));
        assertTrue(r.damaged(), r.toString());
        assertTrue(r.reason().contains("半透明"));
    }

    @Test void picturesWithoutAlphaOrNothingVisibleAreNotJudged() {
        assertFalse(CutoutCheck.check(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)).damaged());
        assertFalse(CutoutCheck.check(new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB)).damaged());
    }
}
