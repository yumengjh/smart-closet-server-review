package com.smartcloset.api;

import com.smartcloset.garment.GarmentRepository;
import com.smartcloset.security.ClosetIdentity;
import com.smartcloset.security.ClosetModelPolicy;
import com.smartcloset.security.ClosetOwnership;
import com.smartcloset.workflow.Workflow;
import com.smartcloset.workflow.WorkflowService;
import com.smartcloset.workflow.persistence.WorkflowRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import com.smartcloset.image.ProcessingException;

@RestController
@RequestMapping("/api/closet")
public class ClosetProductController {
    public record StepView(String name, String status) {}
    public record TaskView(String id, String garmentId, String title, String status, String mode,
                           Instant createdAt, List<StepView> steps, String note, String inputUrl, String outputUrl) {}
    public record RevisionRequest(Long revision) {}
    public record Quota(int limit, int used, String resetAt) {}

    private final GarmentRepository garments;
    private final WorkflowService workflows;
    private final WorkflowRepository repository;
    private final ClosetOwnership ownership;
    private final ClosetModelPolicy models;
    private final JdbcTemplate jdbc;
    private final com.smartcloset.workflow.WorkflowFiles files;
    public ClosetProductController(GarmentRepository garments, WorkflowService workflows,
            WorkflowRepository repository, ClosetOwnership ownership, ClosetModelPolicy models, JdbcTemplate jdbc,
            com.smartcloset.workflow.WorkflowFiles files) {
        this.garments = garments; this.workflows = workflows; this.repository = repository;
        this.ownership = ownership; this.models = models; this.jdbc = jdbc; this.files = files;
    }

    @GetMapping("/metadata-options") public Map<String, List<String>> metadataOptions() { return garments.metadataOptions(ClosetIdentity.username()); }

    @GetMapping("/garments") public GarmentRepository.ClosetPage list(
            @RequestParam(defaultValue = "ACTIVE") String status, @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String category, @RequestParam(defaultValue = "") String color,
            @RequestParam(defaultValue = "") String season, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "24") int size) {
        if (!Set.of("ACTIVE", "DRAFT", "TRASHED").contains(status)) throw ProcessingException.invalid("status 只能是 ACTIVE、DRAFT 或 TRASHED。");
        return garments.listCloset(ClosetIdentity.username(), status, q, category, color, season, page, size);
    }

    @PostMapping("/garments/{id}/confirm") public GarmentRepository.View confirm(@PathVariable UUID id,
            @RequestBody GarmentRepository.Confirm request) throws java.io.IOException {
        ownership.garment(id);
        if (request != null && request.assetId() != null) ownership.asset(request.assetId());
        if (request != null && request.assetId() != null) {
            var asset = repository.asset(request.assetId());
            files.readVerified(asset);
        }
        return garments.confirm(id, request);
    }

    @PostMapping("/garments/{id}/trash") public GarmentRepository.View trash(@PathVariable UUID id, @RequestBody RevisionRequest request) {
        ownership.garment(id); return garments.trash(id, request == null ? null : request.revision());
    }
    @PostMapping("/garments/{id}/restore") public GarmentRepository.View restore(@PathVariable UUID id, @RequestBody RevisionRequest request) {
        ownership.garment(id); return garments.restore(id, request == null ? null : request.revision());
    }

    @GetMapping("/tasks") public List<TaskView> tasks(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int size) {
        var history = repository.history(page, size, null);
        var result = new ArrayList<TaskView>();
        for (var summary : history.items()) {
            var job = workflows.get(summary.id());
            UUID garmentId = job.request().garmentId();
            var record = garmentId == null ? null : garments.get(garmentId);
            String title = record == null ? "图片处理任务" : record.effective().getOrDefault("primaryColor", List.of()).stream().findFirst().orElse("")
                    + record.effective().getOrDefault("category", List.of()).stream().findFirst().orElse("未分类衣物");
            String mode = job.steps().stream().anyMatch(step -> step.type() == Workflow.Type.GENERATE) ? job.steps().stream().anyMatch(step -> step.type() == Workflow.Type.RECOGNIZE) ? "识别并整理" : "图片整理"
                    : job.steps().stream().anyMatch(step -> step.type() == Workflow.Type.RECOGNIZE) ? "仅识别" : "本地贴纸";
            var steps = job.steps().stream().map(step -> new StepView(step.type().name(), taskStepState(step.status()))).toList();
            result.add(new TaskView(job.id().toString(), garmentId == null ? "" : garmentId.toString(), title,
                    taskState(job.status()), mode, job.createdAt(), steps, job.error() == null ? null : job.error().message(), job.inputUrl(), job.outputUrl()));
        }
        return result;
    }

    @GetMapping("/quota") public Quota quota() {
        String owner = ClosetIdentity.username(); LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        var values = jdbc.query("SELECT used FROM ai_daily_quota WHERE owner_username=? AND quota_date=?", (r,n) -> r.getInt(1), owner, java.sql.Date.valueOf(today));
        return new Quota(20, values.isEmpty() ? 0 : values.getFirst(), today.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toString());
    }

    private static String taskState(Workflow.Status status) {
        return switch (status) {
            case QUEUED -> "QUEUED"; case RUNNING -> "RUNNING"; case CANCEL_REQUESTED -> "CANCEL_REQUESTED"; case NEEDS_REVIEW -> "NEEDS_REVIEW";
            case SUCCEEDED -> "SUCCEEDED"; case FAILED -> "FAILED"; case CANCELLED -> "CANCELLED";
            default -> "UNKNOWN";
        };
    }
    private static String taskStepState(Workflow.Status status) {
        return switch (status) { case SUCCEEDED -> "completed"; case RUNNING -> "running"; case FAILED, UNKNOWN, INTERRUPTED -> "failed"; case NEEDS_REVIEW -> "review"; case SKIPPED, CANCELLED -> "skipped"; default -> "waiting"; };
    }
}
