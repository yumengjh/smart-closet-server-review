package com.smartcloset.api;

import com.smartcloset.garment.ImageStyles;
import com.smartcloset.security.ClosetIdentity;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** Display-image style of garments: overview and the user's own correction (kept over automatic classification). */
@RestController
public class ImageStyleController {
    public record StyleRequest(String style) {}
    private final ImageStyles styles;
    public ImageStyleController(ImageStyles styles) { this.styles = styles; }

    @GetMapping("/api/garments/image-styles")
    public Map<String, Object> overview() {
        String owner = ClosetIdentity.username();
        var current = styles.current(owner);
        var counts = new TreeMap<String, Integer>(); current.values().forEach(e -> counts.merge(e.style().name(), 1, Integer::sum));
        var items = current.values().stream().map(e -> Map.of("garmentId", e.garmentId(), "style", e.style().name(), "label", e.style().label,
            "source", e.source(), "evidence", Objects.toString(e.evidence(), ""))).toList();
        return Map.of("dominant", styles.dominant(owner).name(), "counts", counts, "items", items);
    }

    @PutMapping("/api/garments/{id}/image-style")
    public ImageStyles.Entry set(@PathVariable String id, @RequestBody StyleRequest request) {
        return styles.setByUser(ClosetIdentity.username(), id, request == null ? null : request.style());
    }
}
