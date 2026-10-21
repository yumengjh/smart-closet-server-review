package com.smartcloset.workflow;

import com.smartcloset.generation.GenerationService;
import com.smartcloset.recognition.Recognition;
import com.smartcloset.image.SegmentationEngine.Method;
import com.smartcloset.image.TransparencyService;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;

public final class Workflow {
    private Workflow() {}
    public enum Type { GENERATE, SEGMENT, ENSURE_TRANSPARENCY, STICKER, RECOGNIZE }
    public enum Mode { TEXT_TO_IMAGE, IMAGE_TO_IMAGE }
    public enum Source { ORIGINAL, CUTOUT }
    public enum Origin { USER_UPLOAD, GENERATED, UNKNOWN, PRESET }
    public enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED, NEEDS_REVIEW, INTERRUPTED, SKIPPED, PENDING, UNKNOWN, CANCEL_REQUESTED, CANCELLED }
    public record Step(Type type, Mode mode, GenerationService.Request generation, Method method,
                       @JsonInclude(JsonInclude.Include.NON_NULL) TransparencyService.Policy policy,
                       @JsonInclude(JsonInclude.Include.NON_NULL) String providerRevision,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Recognition.Request recognition,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Recognition.Prepared preparedRecognition) {
        public Step(Type type, Mode mode, GenerationService.Request generation, Method method, TransparencyService.Policy policy, String providerRevision) {
            this(type, mode, generation, method, policy, providerRevision, null, null);
        }
        public static Step recognize(Recognition.Request request) { return new Step(Type.RECOGNIZE, null, null, null, null, null, request, null); }
        public Step(Type type, Mode mode, GenerationService.Request generation, Method method, TransparencyService.Policy policy) {
            this(type, mode, generation, method, policy, null);
        }
        public Step(Type type, Mode mode, GenerationService.Request generation, Method method) {
            this(type, mode, generation, method, null, null);
        }
    }
    public record Request(Double rotationDegrees, UUID sourceSegmentationId, Source sourceImage, List<Step> steps,
                          @JsonInclude(JsonInclude.Include.NON_NULL) UUID garmentId,
                          @JsonInclude(JsonInclude.Include.NON_NULL) Origin sourceOrigin) {
        public Request(Double rotationDegrees, UUID sourceSegmentationId, Source sourceImage, List<Step> steps) { this(rotationDegrees, sourceSegmentationId, sourceImage, steps, null, null); }
    }
    public record RerunRequest(List<Step> steps) {}
    public record Failure(String code, String message) {}
    public record StepResult(int index, Type type, Status status, Instant startedAt, Instant finishedAt,
                             String inputUrl, String outputUrl, UUID segmentationId, JsonNode usage, Failure error,
                             @JsonInclude(JsonInclude.Include.NON_NULL) TransparencyService.Decision transparency,
                             @JsonInclude(JsonInclude.Include.NON_NULL) Recognition.Result recognition,
                             @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode rawRecognition) {
        public StepResult(int index, Type type, Status status, Instant startedAt, Instant finishedAt, String inputUrl, String outputUrl,
                          UUID segmentationId, JsonNode usage, Failure error, TransparencyService.Decision transparency) {
            this(index, type, status, startedAt, finishedAt, inputUrl, outputUrl, segmentationId, usage, error, transparency, null, null);
        }
        public StepResult(int index, Type type, Status status, Instant startedAt, Instant finishedAt,
                          String inputUrl, String outputUrl, UUID segmentationId, JsonNode usage, Failure error) {
            this(index, type, status, startedAt, finishedAt, inputUrl, outputUrl, segmentationId, usage, error, null);
        }
    }
    public record Job(UUID id, String fingerprint, Status status, Instant createdAt, Instant updatedAt,
                      Request request, String inputUrl, String outputUrl, boolean requiresReview,
                      List<StepResult> steps, Failure error, UUID parentJobId, UUID selectedAssetId,
                      List<Asset> assets, List<Attempt> attempts) {
        public Job(UUID id, String fingerprint, Status status, Instant createdAt, Instant updatedAt,
                   Request request, String inputUrl, String outputUrl, boolean requiresReview,
                   List<StepResult> steps, Failure error) {
            this(id, fingerprint, status, createdAt, updatedAt, request, inputUrl, outputUrl,
                    requiresReview, steps, error, null, null, List.of(), List.of());
        }
    }
    public record Asset(UUID id, String url, String kind, String sha256, long byteSize, int width, int height,
                        String reviewStatus, String reviewNote, Instant reviewedAt) {}
    public record Attempt(UUID id, int stepIndex, String provider, String model, String providerRequestId,
                          String status, Instant startedAt, Instant finishedAt, JsonNode usage, String errorCode) {}
    public record Summary(UUID id, Status status, Instant createdAt, Instant updatedAt, String outputUrl,
                          int completedSteps, int totalSteps, boolean requiresReview, UUID selectedAssetId, UUID parentJobId) {}
    public record History(List<Summary> items, long total, long page, long size) {}
    public record ContinueRequest(UUID assetId, TransparencyService.Policy policy, Method method) {}
    public record ReviewRequest(String decision, String note) {}
}
