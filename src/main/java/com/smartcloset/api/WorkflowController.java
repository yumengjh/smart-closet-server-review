package com.smartcloset.api;

import com.smartcloset.generation.GenerationService;
import com.smartcloset.workflow.*;
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
    public WorkflowController(WorkflowService workflows, GenerationService generation) { this.workflows = workflows; this.generation = generation; }
    @GetMapping("/generation/providers")
    public List<GenerationService.ProviderInfo> providers() { return generation.providers(); }
    @GetMapping("/generation/providers/{id}/models")
    public Map<String, Object> models(@PathVariable String id) {
        return Map.of("models", generation.models(id), "note", "imageCandidate 仅按名称筛选；模型列表不代表已通过生图测试。");
    }
    @PostMapping(value = "/workflows", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Workflow.Job> create(@RequestHeader(value = "Idempotency-Key", required = false) UUID key,
                                               @RequestBody Workflow.Request request) throws IOException {
        return accepted(workflows.submit(key, request, null));
    }
    @PostMapping(value = "/workflows", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Workflow.Job> upload(@RequestHeader(value = "Idempotency-Key", required = false) UUID key,
            @RequestPart("workflow") Workflow.Request request, @RequestPart(value = "file", required = false) MultipartFile file) throws IOException {
        return accepted(workflows.submit(key, request, file == null ? null : file.getBytes()));
    }
    private ResponseEntity<Workflow.Job> accepted(Workflow.Job job) {
        return ResponseEntity.accepted().location(URI.create("/api/workflows/" + job.id())).body(job);
    }
    @GetMapping("/workflows/{id}") public Workflow.Job get(@PathVariable UUID id) throws IOException { return workflows.get(id); }
    @GetMapping("/workflows")
    public Workflow.History history(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int size, @RequestParam(required = false) Workflow.Status status) {
        return workflows.history(page, size, status);
    }
    @PostMapping("/workflows/{id}/cancel")
    public Workflow.Job cancel(@PathVariable UUID id) { return workflows.cancel(id); }
    @PostMapping("/workflows/{id}/continue")
    public ResponseEntity<Workflow.Job> continueFrom(@PathVariable UUID id,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody Workflow.ContinueRequest request) throws IOException {
        return accepted(workflows.continueFrom(id, key, request));
    }
    @PostMapping("/workflows/{id}/assets/{assetId}/review")
    public Workflow.Job review(@PathVariable UUID id, @PathVariable UUID assetId,
            @RequestBody Workflow.ReviewRequest request) throws IOException {
        return workflows.review(id, assetId, request);
    }
    @GetMapping("/workflows/{id}/files/{name}")
    public ResponseEntity<FileSystemResource> file(@PathVariable UUID id, @PathVariable String name) throws IOException {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(new FileSystemResource(workflows.file(id, name)));
    }
}
