package com.smartcloset.api;

import com.smartcloset.garment.GarmentRepository;
import com.smartcloset.recognition.*;
import com.smartcloset.security.ClosetIdentity;
import com.smartcloset.security.ClosetOwnership;
import com.smartcloset.security.ClosetModelPolicy;
import com.smartcloset.workflow.*;
import com.smartcloset.workflow.persistence.WorkflowRepository;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class GarmentController {
    private final GarmentRepository garments;
    private final RecognitionService recognition;
    private final RecognitionSchema schema;
    private final WorkflowService workflows;
    private final WorkflowRepository repository;
    private final WorkflowFiles files;
    private final ClosetOwnership ownership;
    private final ClosetModelPolicy modelPolicy;
    public GarmentController(GarmentRepository garments, RecognitionService recognition, RecognitionSchema schema,
            WorkflowService workflows, WorkflowRepository repository, WorkflowFiles files,
            ClosetOwnership ownership, ClosetModelPolicy modelPolicy) {
        this.garments = garments; this.recognition = recognition; this.schema = schema;
        this.workflows = workflows; this.repository = repository; this.files = files; this.ownership = ownership; this.modelPolicy = modelPolicy;
    }
    @GetMapping("/recognition/providers") public Object providers() { return recognition.providers(); }
    @GetMapping("/recognition/providers/{id}/models") public Object models(@PathVariable String id) {
        return Map.of("models", modelPolicy.recognitionModels(id, recognition.models(id)), "note", "模型许可由服务端控制；上游目录不代表识图能力已验证。");
    }
    @GetMapping("/recognition/schema") public Object schema() { return Map.of("version", RecognitionSchema.VERSION, "allowCustomValues", true,
        "fields", garments.metadataFields(ClosetIdentity.username()), "schema", schema.schema()); }
    @GetMapping("/garments") public Object list(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "12") int size) {
        if (ClosetIdentity.administrator()) return garments.list(page, size).stream().map(garments::get).toList();
        var active = garments.listCloset(ClosetIdentity.username(), "ACTIVE", "", "", "", "", page, size);
        var drafts = garments.listCloset(ClosetIdentity.username(), "DRAFT", "", "", "", "", page, size);
        return java.util.stream.Stream.concat(active.items().stream(), drafts.items().stream()).toList();
    }
    @GetMapping("/garments/{id}") public GarmentRepository.View get(@PathVariable UUID id) { ownership.garment(id); return garments.get(id); }
    @PatchMapping("/garments/{id}/metadata") public GarmentRepository.View edit(@PathVariable UUID id, @RequestBody GarmentRepository.Edit request) { ownership.garment(id); return garments.edit(id, request); }
    @PatchMapping("/garments/{id}") public GarmentRepository.View basic(@PathVariable UUID id,@RequestBody GarmentRepository.BasicEdit request){ownership.garment(id);return garments.editBasic(ClosetIdentity.username(),id,request);}
    @PostMapping("/garments/{id}/display-image") public GarmentRepository.View select(@PathVariable UUID id, @RequestBody GarmentRepository.SelectImage request) throws IOException {
        ownership.garment(id); if (request != null && request.assetId() != null) ownership.asset(request.assetId());
        if (request != null && request.assetId() != null) files.readVerified(repository.asset(request.assetId()));
        return garments.select(id, request);
    }
    @PostMapping("/garments/{id}/recognize") public ResponseEntity<Workflow.Job> recognize(@PathVariable UUID id,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody(required = false) Recognition.Request request) throws IOException {
        ownership.garment(id); ownership.workflowIfPresent(key);
        return accepted(workflows.rerun(id, key, new Workflow.RerunRequest(List.of(Workflow.Step.recognize(request)))));
    }
    @PostMapping("/garments/{id}/workflows") public ResponseEntity<Workflow.Job> rerun(@PathVariable UUID id,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody Workflow.RerunRequest request) throws IOException {
        ownership.garment(id); ownership.workflowIfPresent(key);
        return accepted(workflows.rerun(id, key, request));
    }
    @PostMapping(value = "/recognitions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Workflow.Job> standalone(@RequestHeader("Idempotency-Key") UUID key,
            @RequestPart("file") MultipartFile file, @RequestPart(value = "recognition", required = false) Recognition.Request request) throws IOException {
        return accepted(workflows.submit(key, new Workflow.Request(0d, null, null, List.of(Workflow.Step.recognize(request))), file.getBytes()));
    }
    private ResponseEntity<Workflow.Job> accepted(Workflow.Job job) { return ResponseEntity.accepted().location(URI.create("/api/workflows/" + job.id())).body(job); }
}
