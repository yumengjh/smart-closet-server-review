package com.smartcloset.image;

import com.smartcloset.ClosetProperties;
import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TransparencyService {
    public enum Policy { AUTO, FORCE, PRESERVE, REVIEWED }
    public enum Action { REUSED_ALPHA, APPROVED_ALPHA, SEGMENTED, NEEDS_REVIEW, REJECTED }
    public record Decision(Policy policy, Action action, TransparencyInspector.Report before, TransparencyInspector.Report after) {}
    public record Result(byte[] png, UUID segmentationId, Decision decision) {
        public boolean needsReview() { return decision.action() == Action.NEEDS_REVIEW; }
        public boolean rejected() { return decision.action() == Action.REJECTED; }
    }
    private final ClosetProperties properties;
    private final TransparencyInspector inspector;
    private final SegmentationService segmentations;
    public TransparencyService(ClosetProperties properties, TransparencyInspector inspector, SegmentationService segmentations) {
        this.properties = properties; this.inspector = inspector; this.segmentations = segmentations;
    }
    public Result ensure(byte[] png, Policy policy, SegmentationEngine.Method method) throws IOException {
        var before = inspect(png);
        if (before.verdict() == TransparencyInspector.Verdict.EMPTY)
            return new Result(png, null, new Decision(policy, Action.REJECTED, before, before));
        if (policy == Policy.REVIEWED) {
            // Human approval may accept structural warnings, never opaque or empty pixels.
            if (!before.hasAlpha() || before.transparentRatio() < TransparencyInspector.MIN_TRANSPARENT_RATIO
                    || before.verdict() == TransparencyInspector.Verdict.OPAQUE)
                throw ProcessingException.invalid("这张图片没有有效透明背景，请先重新抠图。");
            return new Result(png, null, new Decision(policy, Action.APPROVED_ALPHA, before, before));
        }
        if (policy != Policy.FORCE && before.verdict() == TransparencyInspector.Verdict.USABLE)
            return new Result(png, null, new Decision(policy, Action.REUSED_ALPHA, before, before));
        if (policy == Policy.PRESERVE || (policy == Policy.AUTO && before.verdict() == TransparencyInspector.Verdict.SUSPICIOUS))
            return new Result(png, null, new Decision(policy, Action.NEEDS_REVIEW, before, before));
        var segmented = segmentations.uploadBytes(png, method, null, 0);
        UUID id = UUID.fromString(segmented.id()); byte[] result = segmentations.read(id, "cutout.png");
        var after = inspect(result);
        Action action = switch (after.verdict()) {
            case USABLE -> Action.SEGMENTED;
            case EMPTY -> Action.REJECTED;
            default -> Action.NEEDS_REVIEW;
        };
        return new Result(result, id, new Decision(policy, action, before, after));
    }
    public TransparencyInspector.Report inspect(byte[] png) {
        return inspector.inspect(Images.decodePreservingAlpha(png, properties.maxPixels()).image());
    }
}
