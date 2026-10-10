package com.smartcloset.image;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.smartcloset.ClosetProperties;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Idle unload of the matting session (design 模型内存与图像向量 M-1), with a 161-byte stand-in model of the same shape. */
class U2NetIdleUnloadTest {
    static ClosetProperties properties() throws Exception {
        var p = mock(ClosetProperties.class);
        when(p.modelPath()).thenReturn(Path.of(U2NetIdleUnloadTest.class.getResource("/onnx/tiny-saliency.onnx").toURI()));
        when(p.inferenceThreads()).thenReturn(1);
        return p;
    }
    static BufferedImage picture() {
        var img = new BufferedImage(64, 80, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics(); g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, 64, 80); g.setColor(java.awt.Color.BLUE); g.fillRect(16, 16, 32, 48); g.dispose();
        return img;
    }

    @Test void releasesAfterIdleAndReloadsOnNextUse() throws Exception {
        var engine = new U2NetEngine(properties(), Duration.ofMinutes(10));
        try {
            assertFalse(engine.loaded());
            var mask = engine.segment(picture(), null).mask();
            assertEquals(64, mask.getWidth());
            assertTrue(engine.loaded());
            long now = System.currentTimeMillis();
            assertFalse(engine.unloadIfIdle(now), "just used");
            assertTrue(engine.unloadIfIdle(now + Duration.ofMinutes(11).toMillis()));
            assertFalse(engine.loaded());
            assertEquals(1, engine.unloads());
            engine.segment(picture(), null);
            assertTrue(engine.loaded(), "reloaded transparently");
        } finally { engine.close(); }
    }

    @Test void neverUnloadsDuringAnInference() throws Exception {
        var engine = new U2NetEngine(properties(), Duration.ofMinutes(10));
        try {
            engine.segment(picture(), null);
            engine.lifecycle.readLock().lock();   // what segment() holds while it runs
            try { assertFalse(engine.unloadIfIdle(System.currentTimeMillis() + Duration.ofHours(1).toMillis())); }
            finally { engine.lifecycle.readLock().unlock(); }
            assertTrue(engine.loaded());
        } finally { engine.close(); }
    }

    @Test void zeroKeepsTheSessionLoaded() throws Exception {
        var engine = new U2NetEngine(properties(), Duration.ZERO);
        try {
            engine.segment(picture(), null);
            assertFalse(engine.unloadIfIdle(System.currentTimeMillis() + Duration.ofDays(1).toMillis()));
            assertTrue(engine.loaded());
        } finally { engine.close(); }
    }
}
