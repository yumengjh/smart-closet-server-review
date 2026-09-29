package com.smartcloset.image;

import java.io.IOException;
import java.nio.file.*;
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
        Path source = segmentations.file(id, "cutout.png");
        Path output = directory(source);
        if (complete(output)) return new Creation(read(output), false);
        if (!capacity.tryAcquire()) throw new ProcessingException(HttpStatus.TOO_MANY_REQUESTS,
                "PROCESSOR_BUSY", "正在生成另一张贴纸，请稍后重试。");
        try {
            if (complete(output)) return new Creation(read(output), false);
            long started = System.nanoTime();
            var image = ImageIO.read(source.toFile());
            if (image == null) throw new IOException("Saved cutout could not be decoded");
            var rendered = renderer.render(image);
            Path staging = Files.createTempDirectory(source.getParent(), ".sticker-");
            try {
                ImageIO.write(rendered.image(), "png", staging.resolve("image.png").toFile());
                var result = new Result(id.toString(), StickerRenderer.STYLE, Instant.now(), StickerRenderer.SIZE,
                        StickerRenderer.SIZE, rendered.contentBounds(), (System.nanoTime() - started) / 1_000_000,
                        "/api/segmentations/" + id + "/sticker/image?v=" + StickerRenderer.STYLE);
                mapper.writerWithDefaultPrettyPrinter().writeValue(staging.resolve("result.json").toFile(), result);
                Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE);
                return new Creation(result, true);
            } finally {
                if (Files.exists(staging)) {
                    try (var children = Files.list(staging)) {
                        for (Path child : children.toList()) Files.deleteIfExists(child);
                    }
                    Files.deleteIfExists(staging);
                }
            }
        } finally { capacity.release(); }
    }

    public Result get(UUID id) throws IOException { return read(existing(id)); }

    public Path image(UUID id) { return existing(id).resolve("image.png"); }

    private Path existing(UUID id) {
        Path output = directory(segmentations.file(id, "cutout.png"));
        if (!complete(output)) throw new ProcessingException(HttpStatus.NOT_FOUND,
                "STICKER_NOT_FOUND", "尚未生成贴纸，请先调用生成接口。");
        return output;
    }

    private Path directory(Path source) { return source.getParent().resolve("sticker-" + StickerRenderer.STYLE); }
    private boolean complete(Path dir) { return Files.isRegularFile(dir.resolve("result.json")) && Files.isRegularFile(dir.resolve("image.png")); }
    private Result read(Path dir) throws IOException { return mapper.readValue(dir.resolve("result.json").toFile(), Result.class); }
}
