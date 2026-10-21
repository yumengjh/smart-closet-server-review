package com.smartcloset.api;

import com.smartcloset.generation.GenerationService;
import com.smartcloset.workflow.*;
import com.smartcloset.security.ClosetOwnership;
import com.smartcloset.security.ClosetModelPolicy;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class WorkflowController {
    private final WorkflowService workflows;
    private final GenerationService generation;
    private final ClosetOwnership ownership;
    private final ClosetModelPolicy modelPolicy;
    private final com.smartcloset.storage.StoredMedia media;
    public WorkflowController(WorkflowService workflows, GenerationService generation, ClosetOwnership ownership, ClosetModelPolicy modelPolicy, com.smartcloset.storage.StoredMedia media) {
        this.media = media;
        this.workflows = workflows; this.generation = generation; this.ownership = ownership; this.modelPolicy = modelPolicy;
    }
    @GetMapping("/generation/providers")
    public List<GenerationService.ProviderInfo> providers() { return generation.providers(); }
    @GetMapping("/generation/providers/{id}/models")
    public Map<String, Object> models(@PathVariable String id) {
        var models = generation.models(id);
        return Map.of("models", modelPolicy.generationModels(id, models), "note", "模型许可由服务端控制；上游目录不代表已验证模型能力。");
    }
    @PostMapping(value = "/workflows", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Workflow.Job> create(@RequestHeader(value = "Idempotency-Key", required = false) UUID key,
                                               @RequestBody Workflow.Request request) throws IOException {
        ownership.workflowIfPresent(key);
        if (request != null && request.sourceSegmentationId() != null) ownership.segmentation(request.sourceSegmentationId());
        return accepted(workflows.submit(key, request, null));
    }
    @PostMapping(value = "/workflows", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Workflow.Job> upload(@RequestHeader(value = "Idempotency-Key", required = false) UUID key,
            @RequestPart("workflow") Workflow.Request request, @RequestPart(value = "file", required = false) MultipartFile file) throws IOException {
        ownership.workflowIfPresent(key);
        if (request != null && request.sourceSegmentationId() != null) ownership.segmentation(request.sourceSegmentationId());
        return accepted(workflows.submit(key, request, file == null ? null : file.getBytes()));
    }
    private ResponseEntity<Workflow.Job> accepted(Workflow.Job job) {
        return ResponseEntity.accepted().location(URI.create("/api/workflows/" + job.id())).body(job);
    }
    @GetMapping("/workflows/{id}") public Workflow.Job get(@PathVariable UUID id) throws IOException { ownership.workflow(id); return workflows.get(id); }
    @GetMapping("/workflows")
    public Workflow.History history(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int size, @RequestParam(required = false) Workflow.Status status) {
        return workflows.history(page, size, status);
    }
    @PostMapping("/workflows/{id}/cancel")
    public Workflow.Job cancel(@PathVariable UUID id) { ownership.workflow(id); return workflows.cancel(id); }
    @PostMapping("/workflows/{id}/continue")
    public ResponseEntity<Workflow.Job> continueFrom(@PathVariable UUID id,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody Workflow.ContinueRequest request) throws IOException {
        ownership.workflow(id); ownership.workflowIfPresent(key); ownership.asset(request == null ? null : request.assetId());
        return accepted(workflows.continueFrom(id, key, request));
    }
    @PostMapping("/workflows/{id}/assets/{assetId}/review")
    public Workflow.Job review(@PathVariable UUID id, @PathVariable UUID assetId,
            @RequestBody Workflow.ReviewRequest request) throws IOException {
        ownership.workflow(id); ownership.asset(assetId);
        return workflows.review(id, assetId, request);
    }
    @GetMapping("/workflows/{id}/files/{name}")
    public ResponseEntity<?> file(@PathVariable UUID id, @PathVariable String name, @RequestParam(required = false) Integer w) throws IOException {
        ownership.workflowRead(id);
        return media.shared(workflows.file(id, name), w);
    }
}
