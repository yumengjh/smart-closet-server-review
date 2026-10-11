package com.smartcloset.image;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StickerServiceTest {
    @TempDir Path storage;

    private void stub(SegmentationService segmentations, UUID id, Path source) throws Exception {
        when(segmentations.read(id, "cutout.png")).thenReturn(Files.readAllBytes(source));
        when(segmentations.key(id, "cutout.png")).thenReturn(id + "/cutout.png");
        when(segmentations.store()).thenReturn(new com.smartcloset.storage.LocalBlobStore(storage));
    }

    @Test void persistedCacheSurvivesServiceRecreationWithoutRenderingAgain() throws Exception {
        UUID id = UUID.randomUUID(); Path source = source();
        var segmentations = mock(SegmentationService.class);
        stub(segmentations, id, source);
        var mapper = JsonMapper.builder().build();
        var first = new StickerService(segmentations, new StickerRenderer(), mapper).create(id);
        assertTrue(first.created());
        var unusedRenderer = mock(StickerRenderer.class);
        var afterRestart = new StickerService(segmentations, unusedRenderer, mapper);
        assertEquals(first.result(), afterRestart.get(id));
        assertFalse(afterRestart.create(id).created());
        assertTrue(Files.isRegularFile(storage.resolve(afterRestart.image(id))));
        verifyNoInteractions(unusedRenderer);
    }

    @Test void refusesConcurrentRenderAndReleasesCapacityAfterFailure() throws Exception {
        UUID id = UUID.randomUUID(); Path source = source();
        var segmentations = mock(SegmentationService.class);
        stub(segmentations, id, source);
        var renderer = mock(StickerRenderer.class);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        when(renderer.render(any())).thenAnswer(invocation -> {
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            throw ProcessingException.invalid("test rendering failure");
        });
        var service = new StickerService(segmentations, renderer, JsonMapper.builder().build());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> assertThrows(ProcessingException.class, () -> service.create(id)));
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertEquals(429, assertThrows(ProcessingException.class, () -> service.create(id)).status().value());
            } finally { release.countDown(); }
            assertEquals(400, first.get(5, TimeUnit.SECONDS).status().value());
            assertEquals(400, assertThrows(ProcessingException.class, () -> service.create(id)).status().value());
            assertFalse(Files.exists(storage.resolve("sticker-" + StickerRenderer.STYLE)));
        }
    }

    private Path source() throws Exception {
        var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 16; y < 48; y++) for (int x = 16; x < 48; x++) image.setRGB(x, y, 0xff336699);
        Path path = storage.resolve("cutout.png"); ImageIO.write(image, "png", path.toFile()); return path;
    }
}
