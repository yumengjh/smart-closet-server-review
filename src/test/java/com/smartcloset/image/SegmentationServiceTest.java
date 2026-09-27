package com.smartcloset.image;

import com.smartcloset.ClosetProperties;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class SegmentationServiceTest {
    @TempDir Path storage;

    @Test void rejectsConcurrentProcessingAndReleasesPermitAfterFailure() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        SegmentationEngine engine = new SegmentationEngine() {
            public Method method() { return Method.GRABCUT; }
            public MaskResult segment(BufferedImage image, Region region) {
                started.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                return new MaskResult(new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_BYTE_GRAY), List.of());
            }
        };
        var props = new ClosetProperties(storage, Path.of("missing.onnx"), 12000000, 600, 2);
        var service = new SegmentationService(props, List.of(engine), JsonMapper.builder().build());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(ImageProcessingTest.fixture(), "png", bytes);
        var file = new MockMultipartFile("file", bytes.toByteArray());
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> first = executor.submit(() -> {
                var failure = assertThrows(ProcessingException.class, () -> service.upload(file, SegmentationEngine.Method.GRABCUT, null));
                assertEquals("NO_FOREGROUND", failure.code());
            });
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                var busy = assertThrows(ProcessingException.class, () -> service.upload(file, SegmentationEngine.Method.GRABCUT, null));
                assertEquals(429, busy.status().value());
            } finally { release.countDown(); }
            first.get(5, TimeUnit.SECONDS);
            var afterFailure = assertThrows(ProcessingException.class, () -> service.upload(file, SegmentationEngine.Method.GRABCUT, null));
            assertEquals("NO_FOREGROUND", afterFailure.code());
        }
    }
}
