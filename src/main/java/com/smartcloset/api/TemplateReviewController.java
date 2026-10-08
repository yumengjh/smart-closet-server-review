package com.smartcloset.api;

import com.smartcloset.recommend.OutfitTemplates;
import com.smartcloset.recommend.TemplateCatalog;
import com.smartcloset.security.ClosetIdentity;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** Admin review of curated outfit templates (D31). /api/admin/** is admin-only in SecurityConfiguration. */
@RestController
public class TemplateReviewController {
    public record ReviewRequest(String reviewStatus, Boolean enabled, Double bonus) {}
    public record BatchRequest(List<String> ids, String reviewStatus) {}

    private final TemplateCatalog catalog;
    public TemplateReviewController(TemplateCatalog catalog) { this.catalog = catalog; }

    @GetMapping("/api/admin/outfit-templates")
    public Map<String, Object> list() {
        var t = catalog.current();
        return Map.of("version", t.version(), "maxBonus", OutfitTemplates.MAX_BONUS, "active", t.active().size(), "items", t.entries());
    }

    @PatchMapping("/api/admin/outfit-templates/{id}")
    public OutfitTemplates.Entry review(@PathVariable String id, @RequestBody ReviewRequest request) {
        return catalog.review(id, request.reviewStatus(), request.enabled(), request.bonus(), ClosetIdentity.username());
    }

    @PostMapping("/api/admin/outfit-templates/review")
    public Map<String, Object> batch(@RequestBody BatchRequest request) {
        return Map.of("items", catalog.reviewAll(request.ids(), request.reviewStatus(), ClosetIdentity.username()));
    }
}
