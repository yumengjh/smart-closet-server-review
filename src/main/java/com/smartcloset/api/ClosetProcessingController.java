package com.smartcloset.api;

import com.smartcloset.generation.*;
import com.smartcloset.recognition.*;
import com.smartcloset.security.ClosetOwnership;
import com.smartcloset.workflow.*;
import com.smartcloset.image.SegmentationEngine;
import com.smartcloset.image.TransparencyService;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static com.smartcloset.workflow.Workflow.*;

@RestController
@RequestMapping("/api/closet")
public class ClosetProcessingController {
    public record UploadOptions(String mode, Boolean restage, Boolean precut, Boolean postcut, Double rotationDegrees,
                                String generationProvider, String generationModel, String recognitionProvider, String recognitionModel,
                                TransparencyService.Policy transparencyPolicy) {}
    private final WorkflowService workflows;
    private final GenerationProperties generation;
    private final RecognitionProperties recognition;
    private final ClosetOwnership ownership;
    public ClosetProcessingController(WorkflowService workflows, GenerationProperties generation,
            RecognitionProperties recognition, ClosetOwnership ownership) {
        this.workflows = workflows; this.generation = generation; this.recognition = recognition;
        this.ownership = ownership;
    }

    @PostMapping(value = "/garments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Job> create(@RequestHeader("Idempotency-Key") UUID key,
            @RequestPart("file") MultipartFile file, @RequestPart("workflow") UploadOptions options) throws IOException {
        return upload(key, file, options, null);
    }

    /** Replace a preset's picture using the same processing modes, retaining the garment and its editable fields. */
    @PostMapping(value = "/garments/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Job> replacePhoto(@PathVariable UUID id, @RequestHeader("Idempotency-Key") UUID key,
            @RequestPart("file") MultipartFile file, @RequestPart("workflow") UploadOptions options) throws IOException {
        ownership.garment(id);
        return upload(key, file, options, id);
    }

    private ResponseEntity<Job> upload(UUID key, MultipartFile file, UploadOptions options, UUID garmentId) throws IOException {
        ownership.workflowIfPresent(key);
        if (options == null || options.mode() == null) throw com.smartcloset.image.ProcessingException.invalid("请选择衣物处理模式。");
        var steps = new ArrayList<Step>();
        switch (options.mode()) {
            case "识别并整理" -> {
                steps.add(Step.recognize(new Recognition.Request(options.recognitionProvider(), options.recognitionModel())));
                if (Boolean.TRUE.equals(options.precut())) steps.add(new Step(Type.SEGMENT, null, null, SegmentationEngine.Method.U2NET));
                var provider = options.generationProvider() == null || options.generationProvider().isBlank()
                        ? generation.providers().entrySet().stream().filter(e -> e.getValue().configured()).sorted(Map.Entry.comparingByKey()).findFirst().orElse(null)
                        : generation.providers().entrySet().stream().filter(e -> e.getKey().equals(options.generationProvider()) && e.getValue().configured()).findFirst().orElse(null);
                if (provider == null) throw new com.smartcloset.image.ProcessingException(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_NOT_CONFIGURED", "管理员尚未配置图片整理服务商。" );
                var request = new GenerationService.Request(provider.getKey(), options.generationModel() == null ? provider.getValue().defaultModel() : options.generationModel(),
                        Boolean.FALSE.equals(options.restage()) ? ClosetPrompts.FAITHFUL : ClosetPrompts.RESTAGE, Map.of());
                steps.add(new Step(Type.GENERATE, Mode.IMAGE_TO_IMAGE, request, null));
                if (!Boolean.FALSE.equals(options.postcut())) {
                    steps.add(new Step(Type.ENSURE_TRANSPARENCY, null, null, SegmentationEngine.Method.U2NET, TransparencyService.Policy.AUTO));
                    steps.add(new Step(Type.STICKER, null, null, null));
                }
            }
            case "仅识别" -> steps.add(Step.recognize(new Recognition.Request(options.recognitionProvider(), options.recognitionModel())));
            case "本地贴纸" -> {
                var policy = options.transparencyPolicy() == null ? TransparencyService.Policy.AUTO : options.transparencyPolicy();
                if (policy != TransparencyService.Policy.AUTO && policy != TransparencyService.Policy.FORCE)
                    throw com.smartcloset.image.ProcessingException.invalid("本地制作请选择重新去背景或复用已有透明背景。");
                steps.add(new Step(Type.ENSURE_TRANSPARENCY, null, null, SegmentationEngine.Method.U2NET, policy));
                steps.add(new Step(Type.STICKER, null, null, null));
            }
            default -> throw com.smartcloset.image.ProcessingException.invalid("未知的处理模式。");
        }
        var request = new Request(options.rotationDegrees() == null ? 0d : options.rotationDegrees(), null, null, steps, garmentId, Origin.USER_UPLOAD);
        Job job = garmentId == null ? workflows.submit(key, request, file.getBytes()) : workflows.submitPresetPhoto(key, request, file.getBytes());
        return ResponseEntity.accepted().location(URI.create("/api/workflows/" + job.id())).body(job);
    }

    public record ReprocessOptions(Boolean restage, Boolean precut, Boolean postcut) {}
    @PostMapping("/garments/{id}/reprocess")
    public ResponseEntity<Job> reprocess(@PathVariable UUID id, @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody ReprocessOptions options) throws IOException {
        ownership.garment(id); ownership.workflowIfPresent(key);
        var garments = new ArrayList<Step>();
        if (options != null && Boolean.TRUE.equals(options.precut())) garments.add(new Step(Type.SEGMENT, null, null, SegmentationEngine.Method.U2NET));
        var provider = generation.providers().entrySet().stream().filter(e -> e.getValue().configured()).sorted(Map.Entry.comparingByKey()).findFirst()
                .orElseThrow(() -> new com.smartcloset.image.ProcessingException(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_NOT_CONFIGURED", "管理员尚未配置图片整理服务商。"));
        var request = new GenerationService.Request(provider.getKey(), provider.getValue().defaultModel(),
                options != null && Boolean.FALSE.equals(options.restage()) ? ClosetPrompts.FAITHFUL : ClosetPrompts.RESTAGE, Map.of());
        garments.add(new Step(Type.GENERATE, Mode.IMAGE_TO_IMAGE, request, null));
        if (options == null || !Boolean.FALSE.equals(options.postcut())) {
            garments.add(new Step(Type.ENSURE_TRANSPARENCY, null, null, SegmentationEngine.Method.U2NET, TransparencyService.Policy.AUTO));
            garments.add(new Step(Type.STICKER, null, null, null));
        }
        Job job = workflows.rerun(id, key, new RerunRequest(garments));
        return ResponseEntity.accepted().location(URI.create("/api/workflows/" + job.id())).body(job);
    }
}
