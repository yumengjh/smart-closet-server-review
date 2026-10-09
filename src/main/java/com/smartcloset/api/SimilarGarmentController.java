package com.smartcloset.api;

import com.smartcloset.agent.OutfitRules;
import com.smartcloset.image.Images;
import com.smartcloset.image.ProcessingException;
import com.smartcloset.image.SegmentationEngine;
import com.smartcloset.image.U2NetEngine;
import com.smartcloset.security.ClosetIdentity;
import com.smartcloset.security.ClosetOwnership;
import com.smartcloset.similar.ImageFingerprint;
import com.smartcloset.similar.SimilarGarments;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Similar garments and basic search by image (design 相似衣物 §4/§5). Zero model calls. */
@RestController
@RequestMapping("/api/garments")
public class SimilarGarmentController {
    public record Dismissal(String garmentA, String garmentB) {}
    static final long MAX_BYTES = 15L * 1024 * 1024;
    static final String SEARCH_NOTE = "按颜色、轮廓和外观比对，能找到颜色轮廓相近的衣物和同一张（或几乎相同的）图；换了角度、光线或穿在身上的同款可能认不出来。";

    private final SimilarGarments similar;
    private final ClosetOwnership ownership;
    private final List<SegmentationEngine> engines;

    public SimilarGarmentController(SimilarGarments similar, ClosetOwnership ownership, List<SegmentationEngine> engines) {
        this.similar = similar; this.ownership = ownership; this.engines = engines;
    }

    @GetMapping("/{id}/similar")
    public Map<String, Object> similar(@PathVariable UUID id, @RequestParam(defaultValue = "5") int limit) {
        ownership.garment(id);
        return similar.similar(ClosetIdentity.username(), id.toString(), limit);
    }

    @GetMapping("/duplicates")
    public Map<String, Object> duplicates() { return Map.of("items", similar.duplicates(ClosetIdentity.username())); }

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
        if (image.isEmpty() || image.getSize() > MAX_BYTES) throw ProcessingException.invalid("图片不能为空，且不能超过 15 MB。");
        OutfitRules.Role position = null;
        if (role != null && !role.isBlank()) {
            try { position = OutfitRules.Role.valueOf(role.strip().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { throw ProcessingException.invalid("未知的位置：" + role); }
        }
        var decoded = Images.sampled(image.getBytes(), 1024).image();
        String method = "TRANSPARENT";
        BufferedImage garment = decoded;
        if (!transparent(decoded)) {
            var engine = engines.stream().filter(e -> e instanceof U2NetEngine u && u.modelPresent()).findFirst()
                .or(() -> engines.stream().filter(e -> e.method() == SegmentationEngine.Method.GRABCUT).findFirst()).orElse(null);
            if (engine != null) {
                var mask = engine.segment(decoded, null).mask();
                garment = cutout(decoded, mask);
                method = engine.method().name();
            } else method = "NONE";
        }
        var items = similar.searchByImage(ClosetIdentity.username(), ImageFingerprint.of(garment), position, Math.max(1, Math.min(30, limit)));
        return Map.of("items", items, "matting", method, "note", SEARCH_NOTE);
    }

    private static boolean transparent(BufferedImage image) {
        if (!image.getColorModel().hasAlpha()) return false;
        for (int y = 0; y < image.getHeight(); y += 4) for (int x = 0; x < image.getWidth(); x += 4) if ((image.getRGB(x, y) >>> 24) < 250) return true;
        return false;
    }
    private static BufferedImage cutout(BufferedImage image, BufferedImage mask) {
        var out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        var raster = mask.getRaster();
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int a = raster.getSample(Math.min(x, mask.getWidth() - 1), Math.min(y, mask.getHeight() - 1), 0);
            out.setRGB(x, y, (a << 24) | (image.getRGB(x, y) & 0xFFFFFF));
        }
        return out;
    }
}
