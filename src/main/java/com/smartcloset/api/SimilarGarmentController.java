package com.smartcloset.api;

import com.smartcloset.agent.OutfitRules;
import com.smartcloset.image.Images;
import com.smartcloset.image.ProcessingException;
import com.smartcloset.image.SegmentationEngine;
import com.smartcloset.image.U2NetEngine;
import com.smartcloset.security.ClosetIdentity;
import com.smartcloset.security.ClosetOwnership;
import com.smartcloset.similar.DuplicateScans;
import com.smartcloset.similar.ImageFingerprint;
import com.smartcloset.similar.SimilarGarments;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Similar garments, search by image and the one-click duplicate scan (design 相似衣物 §4/§5, 模型内存与图像向量 §8). Local only. */
@RestController
@RequestMapping("/api/garments")
public class SimilarGarmentController {
    public record Dismissal(String garmentA, String garmentB) {}
    static final long MAX_BYTES = 15L * 1024 * 1024;
    static final String SEARCH_NOTE = "按颜色、轮廓和外观比对，能找到颜色轮廓相近的衣物和同一张（或几乎相同的）图；换了角度、光线或穿在身上的同款可能认不出来。";
    static final String MODEL_NOTE = "按图像识别（款式、图案、材质感）加颜色轮廓比对，换了角度或光线的同款多数也能找到。";

    private final SimilarGarments similar;
    private final ClosetOwnership ownership;
    private final List<SegmentationEngine> engines;
    private final DuplicateScans scans;

    public SimilarGarmentController(SimilarGarments similar, ClosetOwnership ownership, List<SegmentationEngine> engines, DuplicateScans scans) {
        this.similar = similar; this.ownership = ownership; this.engines = engines; this.scans = scans;
    }

    @GetMapping("/{id}/similar")
    public Map<String, Object> similar(@PathVariable UUID id, @RequestParam(defaultValue = "5") int limit) {
        ownership.garment(id);
        return similar.similar(ClosetIdentity.username(), id.toString(), limit);
    }

    @GetMapping("/duplicates")
    public Map<String, Object> duplicates() { return Map.of("items", similar.duplicates(ClosetIdentity.username()), "imageModel", similar.images().status(ClosetIdentity.username())); }

    @GetMapping("/similar-flags")
    public Map<String, Object> flags(@RequestParam List<UUID> ids) {
        return Map.of("flags", similar.flags(ClosetIdentity.username(), ids.stream().map(UUID::toString).toList()));
    }

    @PostMapping("/similar-dismissals")
    public Map<String, Object> dismiss(@RequestBody Dismissal request) {
        if (request == null || request.garmentA() == null || request.garmentB() == null) throw ProcessingException.invalid("请提供两件衣物。");
        similar.dismiss(ClosetIdentity.username(), UUID.fromString(request.garmentA()).toString(), UUID.fromString(request.garmentB()).toString());
        return Map.of("ok", true);
    }

    @PostMapping(value = "/search-by-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> search(@RequestPart("image") MultipartFile image, @RequestParam(value = "role", required = false) String role,
                                      @RequestParam(value = "limit", defaultValue = "10") int limit) throws IOException {
        var picture = QueryPicture.prepare(image, engines);
        var vector = QueryPicture.vector(picture, similar.images().models());
        var items = similar.searchByImage(ClosetIdentity.username(), ImageFingerprint.of(picture.garment()), vector, QueryPicture.role(role), Math.max(1, Math.min(30, limit)));
        var status = similar.images().status(ClosetIdentity.username());
        return Map.of("items", items, "matting", picture.method(), "note", vector == null ? SEARCH_NOTE : MODEL_NOTE, "imageModel", status);
    }

    @PostMapping("/duplicate-scan")
    public Map<String, Object> scan() { return Map.of("scan", scans.start(ClosetIdentity.username())); }

    @GetMapping("/duplicate-scan")
    public Map<String, Object> scanStatus() {
        var latest = scans.latest(ClosetIdentity.username());
        var out = new LinkedHashMap<String, Object>(); out.put("scan", latest); out.put("imageModel", similar.images().status(ClosetIdentity.username()));
        return out;
    }
}
