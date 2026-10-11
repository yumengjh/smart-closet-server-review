package com.smartcloset.image;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class StickerService {
    public record Result(String segmentationId, String style, Instant createdAt, int width, int height,
                         Region contentBounds, long processingMillis, String imageUrl) {}
    public record Creation(Result result, boolean created) {}
    private final SegmentationService segmentations;
    private final StickerRenderer renderer;
    private final ObjectMapper mapper;
    private final Semaphore capacity = new Semaphore(1);

    public StickerService(SegmentationService segmentations, StickerRenderer renderer, ObjectMapper mapper) {
        this.segmentations = segmentations; this.renderer = renderer; this.mapper = mapper;
    }

    public Creation create(UUID id) throws IOException {
        String dir = directory(id);
        if (complete(dir)) return new Creation(read(dir), false);
        if (!capacity.tryAcquire()) throw new ProcessingException(HttpStatus.TOO_MANY_REQUESTS,
                "PROCESSOR_BUSY", "正在生成另一张贴纸，请稍后重试。");
        try {
            if (complete(dir)) return new Creation(read(dir), false);
            long started = System.nanoTime();
            var image = ImageIO.read(new ByteArrayInputStream(segmentations.read(id, "cutout.png")));
            if (image == null) throw new IOException("Saved cutout could not be decoded");
            var rendered = renderer.render(image);
            var png = new ByteArrayOutputStream(); ImageIO.write(rendered.image(), "png", png);
            // Image first, result.json last: the sticker exists only once both are there.
            store().put(dir + "/image.png", png.toByteArray(), "image/png");
            var result = new Result(id.toString(), StickerRenderer.STYLE, Instant.now(), StickerRenderer.SIZE,
                    StickerRenderer.SIZE, rendered.contentBounds(), (System.nanoTime() - started) / 1_000_000,
                    "/api/segmentations/" + id + "/sticker/image?v=" + StickerRenderer.STYLE);
            store().put(dir + "/result.json", mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result), "application/json");
            return new Creation(result, true);
        } finally { capacity.release(); }
    }

    public Result get(UUID id) throws IOException { return read(existing(id)); }

    /** Storage key of the sticker image. */
    public String image(UUID id) throws IOException { return existing(id) + "/image.png"; }

    private String existing(UUID id) throws IOException {
        String dir = directory(id);
        if (!complete(dir)) throw new ProcessingException(HttpStatus.NOT_FOUND,
                "STICKER_NOT_FOUND", "尚未生成贴纸，请先调用生成接口。");
        return dir;
    }

    private String directory(UUID id) throws IOException {
        segmentations.key(id, "cutout.png");
        return id + "/sticker-" + StickerRenderer.STYLE;
    }
    private boolean complete(String dir) throws IOException { return store().exists(dir + "/result.json") && store().exists(dir + "/image.png"); }
    private Result read(String dir) throws IOException {
        byte[] bytes = store().get(dir + "/result.json");
        if (bytes == null) throw new IOException("Sticker result disappeared");
        return mapper.readValue(bytes, Result.class);
    }
    private com.smartcloset.storage.BlobStore store() { return segmentations.store(); }
}
