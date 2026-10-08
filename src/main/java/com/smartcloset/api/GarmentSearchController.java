package com.smartcloset.api;

import com.smartcloset.security.ClosetIdentity;
import com.smartcloset.semantic.GarmentSearchService;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Hybrid wardrobe search (design 5.10): filters + keywords + local semantics, zero model calls. */
@RestController
public class GarmentSearchController {
    private final GarmentSearchService search;
    public GarmentSearchController(GarmentSearchService search) { this.search = search; }

    @GetMapping("/api/garments/search")
    public Map<String, Object> search(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "1") int page, @RequestParam(required = false) String imageStyle) {
        return search.page(ClosetIdentity.username(), q, page, imageStyle);
    }
}
