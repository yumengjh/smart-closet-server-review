package com.smartcloset.image;

import com.smartcloset.ClosetProperties;
import com.smartcloset.image.SegmentationEngine.Method;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import com.smartcloset.storage.BlobStore;
import com.smartcloset.storage.LocalBlobStore;
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
    private final BlobStore store;
    // Bound native memory and CPU usage; don't queue large decoded images in HTTP threads.
    private final Semaphore capacity = new Semaphore(1);

    public SegmentationService(ClosetProperties properties, List<SegmentationEngine> engines, ObjectMapper mapper) {
        this(properties, engines, mapper, new LocalBlobStore(properties.storageDir()));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SegmentationService(ClosetProperties properties, List<SegmentationEngine> engines, ObjectMapper mapper, BlobStore store) {
        this.properties = properties; this.store = store;
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
            var decoded = Images.decode(read(id, "original.png"), properties.maxPixels());
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
        // Images first, result.json last: a result counts only once result.json exists, so a crash leaves no half result.
        var corrected = png(image);
        store.put(id + "/" + source, corrected, "image/png");
        store.put(id + "/original.png", corrected, "image/png");
        store.put(id + "/mask.png", png(processed.mask()), "image/png");
        store.put(id + "/cutout.png", png(processed.cutout()), "image/png");
        store.put(id + "/cropped.png", png(processed.cropped()), "image/png");
        var result = new SegmentationResult(id, Instant.now(), method, method == Method.U2NET ? "u2net" : null,
                image.getWidth(), image.getHeight(), exifOrientation, region, processed.bounds(),
                (System.nanoTime() - started) / 1_000_000, processed.foregroundRatio(), processed.colors(), warnings,
                base + source, base + "original.png", base + "mask.png", base + "cutout.png", base + "cropped.png", rotationDegrees);
        store.put(id + "/result.json", mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result), "application/json");
        return result;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        var out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out); return out.toByteArray();
    }

    public SegmentationResult get(UUID id) throws IOException {
        byte[] bytes = store.get(id + "/result.json");
        if (bytes == null) throw notFound();
        return mapper.readValue(bytes, SegmentationResult.class);
    }

    /** Storage key of a finished result's image; not found unless result.json was written. */
    public String key(UUID id, String name) throws IOException {
        if (!Set.of("source.jpg", "source.png", "original.png", "mask.png", "cutout.png", "cropped.png").contains(name)) throw notFound();
        if (!store.exists(id + "/result.json")) throw notFound();
        return id + "/" + name;
    }

    public byte[] read(UUID id, String name) throws IOException {
        byte[] bytes = store.get(key(id, name));
        if (bytes == null) throw notFound();
        return bytes;
    }

    public BlobStore store() { return store; }

    private ProcessingException notFound() {
        return new ProcessingException(HttpStatus.NOT_FOUND, "RESULT_NOT_FOUND", "没有找到这份处理结果或图片。");
    }
}
