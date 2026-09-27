package com.smartcloset.api;

import com.smartcloset.image.*;
import com.smartcloset.image.SegmentationEngine.Method;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class SegmentationController {
    private final SegmentationService service;
    private final U2NetEngine u2net;
    public SegmentationController(SegmentationService service, U2NetEngine u2net) { this.service = service; this.u2net = u2net; }

    @GetMapping("/health")
    public Map<String, String> health() { return Map.of("status", "UP"); }

    @GetMapping("/segmentation-methods")
    public Map<String, Object> methods() {
        return Map.of("methods", List.of(
                Map.of("method", "GRABCUT", "requiresModelFile", false, "description", "传统前景分割；可指定矩形框，框外视为背景"),
                Map.of("method", "U2NET", "requiresModelFile", true, "modelFilePresent", u2net.modelPresent(),
                        "loaded", u2net.loaded(), "description", "本地显著主体分割；矩形框表示输入裁剪区域")),
                "clothingClassification", false, "maxConcurrentProcessing", 1);
    }

    @PostMapping(value = "/segmentations", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SegmentationResult> upload(@RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "U2NET") Method method,
            @RequestParam(required = false) Integer x, @RequestParam(required = false) Integer y,
            @RequestParam(required = false) Integer width, @RequestParam(required = false) Integer height) throws IOException {
        Region region = null;
        if (x != null || y != null || width != null || height != null) {
            if (x == null || y == null || width == null || height == null)
                throw ProcessingException.invalid("框选需要同时提供 x、y、width、height。");
            region = new Region(x, y, width, height);
        }
        return created(service.upload(file, method, region));
    }

    public record RefineRequest(@NotNull Method method, @NotNull Region region) {}

    @PostMapping("/segmentations/{id}/refine")
    public ResponseEntity<SegmentationResult> refine(@PathVariable UUID id, @Valid @RequestBody RefineRequest request) throws IOException {
        return created(service.refine(id, request.method(), request.region()));
    }

    @GetMapping("/segmentations/{id}")
    public SegmentationResult result(@PathVariable UUID id) throws IOException { return service.get(id); }

    @GetMapping("/segmentations/{id}/files/{name}")
    public ResponseEntity<FileSystemResource> image(@PathVariable UUID id, @PathVariable String name) {
        var path = service.file(id, name);
        return ResponseEntity.ok().contentType(name.endsWith(".jpg") ? MediaType.IMAGE_JPEG : MediaType.IMAGE_PNG)
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(new FileSystemResource(path));
    }

    private ResponseEntity<SegmentationResult> created(SegmentationResult result) {
        return ResponseEntity.created(URI.create("/api/segmentations/" + result.id())).body(result);
    }
}
