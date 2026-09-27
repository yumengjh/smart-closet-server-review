package com.smartcloset.image;

import com.smartcloset.ClosetProperties;
import com.smartcloset.image.SegmentationEngine.Method;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

@Service
public class SegmentationService {
    private final ClosetProperties properties;
    private final Map<Method, SegmentationEngine> engines = new EnumMap<>(Method.class);
    private final ObjectMapper mapper;
    // Bound native memory and CPU usage; don't queue large decoded images in HTTP threads.
    private final Semaphore capacity = new Semaphore(1);

    public SegmentationService(ClosetProperties properties, List<SegmentationEngine> engines, ObjectMapper mapper) {
        this.properties = properties;
        engines.forEach(engine -> this.engines.put(engine.method(), engine));
        this.mapper = mapper;
    }

    public SegmentationResult upload(MultipartFile file, Method method, Region region) throws IOException {
        acquire();
        try {
            if (file.getSize() > 15L * 1024 * 1024) throw new ProcessingException(HttpStatus.PAYLOAD_TOO_LARGE, "IMAGE_TOO_LARGE", "图片不能超过 15 MB。");
            return process(file.getBytes(), method, region);
        } finally { capacity.release(); }
    }

    public SegmentationResult refine(UUID id, Method method, Region region) throws IOException {
        acquire();
        try {
            var previous = get(id);
            String source = previous.sourceUrl().substring(previous.sourceUrl().lastIndexOf('/') + 1);
            return process(Files.readAllBytes(file(id, source)), method, region);
        } finally { capacity.release(); }
    }

    private void acquire() {
        if (!capacity.tryAcquire()) throw new ProcessingException(HttpStatus.TOO_MANY_REQUESTS, "PROCESSOR_BUSY", "正在处理另一张图片，请稍后重试。");
    }

    private SegmentationResult process(byte[] bytes, Method method, Region region) throws IOException {
        long started = System.nanoTime();
        var decoded = Images.decode(bytes, properties.maxPixels());
        var image = decoded.image();
        var segmented = engines.get(method).segment(image, region);
        var processed = MaskProcessor.apply(image, segmented.mask());
        List<String> warnings = new ArrayList<>(segmented.warnings());
        warnings.addAll(processed.warnings());
        if (!warnings.contains("SUBJECT_IS_NOT_VERIFIED_AS_CLOTHING")) warnings.add("SUBJECT_IS_NOT_VERIFIED_AS_CLOTHING");
        String id = UUID.randomUUID().toString();
        String base = "/api/segmentations/" + id + "/files/";
        String source = decoded.format().equals("jpeg") ? "source.jpg" : "source.png";
        Path root = properties.storageDir().toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path staging = Files.createTempDirectory(root, ".processing-");
        try {
            Files.write(staging.resolve(source), bytes);
            ImageIO.write(image, "png", staging.resolve("original.png").toFile());
            ImageIO.write(processed.mask(), "png", staging.resolve("mask.png").toFile());
            ImageIO.write(processed.cutout(), "png", staging.resolve("cutout.png").toFile());
            ImageIO.write(processed.cropped(), "png", staging.resolve("cropped.png").toFile());
            var result = new SegmentationResult(id, Instant.now(), method, method == Method.U2NET ? "u2net" : null,
                    image.getWidth(), image.getHeight(), decoded.orientation(), region, processed.bounds(),
                    (System.nanoTime() - started) / 1_000_000, processed.foregroundRatio(), processed.colors(), warnings,
                    base + source, base + "original.png", base + "mask.png", base + "cutout.png", base + "cropped.png");
            mapper.writerWithDefaultPrettyPrinter().writeValue(staging.resolve("result.json").toFile(), result);
            Files.move(staging, root.resolve(id), StandardCopyOption.ATOMIC_MOVE);
            return result;
        } finally {
            if (Files.exists(staging)) {
                try (var children = Files.list(staging)) {
                    for (Path child : children.toList()) Files.deleteIfExists(child);
                }
                Files.deleteIfExists(staging);
            }
        }
    }

    public SegmentationResult get(UUID id) throws IOException {
        Path path = properties.storageDir().resolve(id.toString()).resolve("result.json");
        if (!Files.isRegularFile(path)) throw notFound();
        return mapper.readValue(path.toFile(), SegmentationResult.class);
    }

    public Path file(UUID id, String name) {
        if (!Set.of("source.jpg", "source.png", "original.png", "mask.png", "cutout.png", "cropped.png").contains(name)) throw notFound();
        Path path = properties.storageDir().resolve(id.toString()).resolve(name);
        if (!Files.isRegularFile(path) || !Files.isRegularFile(path.getParent().resolve("result.json"))) throw notFound();
        return path;
    }

    private ProcessingException notFound() {
        return new ProcessingException(HttpStatus.NOT_FOUND, "RESULT_NOT_FOUND", "没有找到这份处理结果或图片。");
    }
}
