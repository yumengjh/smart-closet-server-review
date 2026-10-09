package com.smartcloset.api;

import com.smartcloset.ootd.OotdSampleService;
import com.smartcloset.security.ClosetIdentity;
import java.io.IOException;
import java.util.*;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

/** Whole-outfit samples (design 2026-10-09 §6/§8). /api/admin/** is admin-only in SecurityConfiguration. */
@RestController
@RequestMapping("/api/admin/ootd-samples")
public class OotdSampleController {
    public record ReviewRequest(String decision) {}
    public record DraftRequest(String kind, String text) {}

    private final OotdSampleService samples;
    public OotdSampleController(OotdSampleService samples) { this.samples = samples; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public OotdSampleService.Sample upload(@RequestPart("image") MultipartFile image, @RequestParam(value = "sourceUrl", required = false) String sourceUrl,
                                           @RequestParam(value = "license", required = false) String license, @RequestParam(value = "note", required = false) String note) throws IOException {
        return samples.upload(ClosetIdentity.username(), image.getBytes(), sourceUrl, license, note);
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(value = "status", required = false) String status, @RequestParam(value = "limit", defaultValue = "200") int limit) {
        return Map.of("items", samples.list(status, limit), "licenses", OotdSampleService.LICENSES);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam(value = "presentation", required = false) String presentation) { return samples.stats(presentation); }

    @GetMapping("/{id}")
    public OotdSampleService.Sample get(@PathVariable String id) { return samples.get(id); }

    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable String id) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore()).body(samples.image(id));
    }

    @PutMapping("/{id}")
    public OotdSampleService.Sample update(@PathVariable String id, @RequestBody JsonNode result) { return samples.update(id, result); }

    @PostMapping("/{id}/extract")
    public OotdSampleService.Sample extract(@PathVariable String id) { return samples.extract(id, ClosetIdentity.username()); }

    @PostMapping("/{id}/review")
    public OotdSampleService.Sample review(@PathVariable String id, @RequestBody ReviewRequest request) { return samples.review(id, request.decision(), ClosetIdentity.username()); }

    @PostMapping("/{id}/template-draft")
    public Map<String, Object> draft(@PathVariable String id, @RequestBody(required = false) DraftRequest request) {
        return samples.templateDraft(id, request == null ? null : request.kind(), request == null ? null : request.text(), ClosetIdentity.username());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) { samples.delete(id); return ResponseEntity.noContent().build(); }
}
