package com.smartcloset.image;

import com.smartcloset.ClosetProperties;
import com.smartcloset.image.SegmentationEngine.Method;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
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
        return upload(file, method, region, 0);
    }

    public SegmentationResult upload(MultipartFile file, Method method, Region region, double rotationDegrees) throws IOException {
        if (file.getSize() > 15L * 1024 * 1024) throw new ProcessingException(HttpStatus.PAYLOAD_TOO_LARGE, "IMAGE_TOO_LARGE", "图片不能超过 15 MB。");
        return uploadBytes(file.getBytes(), method, region, rotationDegrees);
    }

    public SegmentationResult uploadBytes(byte[] bytes, Method method, Region region, double rotationDegrees) throws IOException {
        acquire();
        try {
            long started = System.nanoTime();
            double angle = Images.rotation(rotationDegrees);
            var decoded = Images.decode(bytes, properties.maxPixels());
            return process(Images.rotate(decoded.image(), angle, properties.maxPixels()), method, region,
                    decoded.orientation(), angle, started);
        } finally { capacity.release(); }
    }

    public SegmentationResult refine(UUID id, Method method, Region region) throws IOException {
        return refine(id, method, region, 0);
    }

    public SegmentationResult refine(UUID id, Method method, Region region, double rotationDegrees) throws IOException {
        acquire();
        try {
            long started = System.nanoTime();
            double angle = Images.rotation(rotationDegrees);
            var previous = get(id);
            // The saved original is already EXIF/angle-corrected. Apply only this request's extra angle.
            var decoded = Images.decode(Files.readAllBytes(file(id, "original.png")), properties.maxPixels());
            double total = ((previous.rotationDegrees() == null ? 0 : previous.rotationDegrees()) + angle) % 360;
            return process(Images.rotate(decoded.image(), angle, properties.maxPixels()), method, region,
                    previous.exifOrientation(), total, started);
        } finally { capacity.release(); }
    }

    private void acquire() {
        if (!capacity.tryAcquire()) throw new ProcessingException(HttpStatus.TOO_MANY_REQUESTS, "PROCESSOR_BUSY", "正在处理另一张图片，请稍后重试。");
    }

    private SegmentationResult process(BufferedImage image, Method method, Region region,
                                       int exifOrientation, double rotationDegrees, long started) throws IOException {
        var segmented = engines.get(method).segment(image, region);
        var processed = MaskProcessor.apply(image, segmented.mask());
        List<String> warnings = new ArrayList<>(segmented.warnings());
        warnings.addAll(processed.warnings());
        if (!warnings.contains("SUBJECT_IS_NOT_VERIFIED_AS_CLOTHING")) warnings.add("SUBJECT_IS_NOT_VERIFIED_AS_CLOTHING");
        String id = UUID.randomUUID().toString();
        String base = "/api/segmentations/" + id + "/files/";
        String source = "source.png";
        Path root = properties.storageDir().toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path staging = Files.createTempDirectory(root, ".processing-");
        try {
            // Persist the corrected photo only, with no EXIF rotation tag to apply again.
            var corrected = new ByteArrayOutputStream(); ImageIO.write(image, "png", corrected);
            Files.write(staging.resolve(source), corrected.toByteArray());
            Files.write(staging.resolve("original.png"), corrected.toByteArray());
            ImageIO.write(processed.mask(), "png", staging.resolve("mask.png").toFile());
            ImageIO.write(processed.cutout(), "png", staging.resolve("cutout.png").toFile());
            ImageIO.write(processed.cropped(), "png", staging.resolve("cropped.png").toFile());
            var result = new SegmentationResult(id, Instant.now(), method, method == Method.U2NET ? "u2net" : null,
                    image.getWidth(), image.getHeight(), exifOrientation, region, processed.bounds(),
                    (System.nanoTime() - started) / 1_000_000, processed.foregroundRatio(), processed.colors(), warnings,
                    base + source, base + "original.png", base + "mask.png", base + "cutout.png", base + "cropped.png", rotationDegrees);
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
