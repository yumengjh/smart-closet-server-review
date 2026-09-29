package com.smartcloset.image;

import com.smartcloset.ClosetProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TransparencyService {
    public enum Policy { AUTO, FORCE, PRESERVE }
    public enum Action { REUSED_ALPHA, SEGMENTED, NEEDS_REVIEW, REJECTED }
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
        if (policy != Policy.FORCE && before.verdict() == TransparencyInspector.Verdict.USABLE)
            return new Result(png, null, new Decision(policy, Action.REUSED_ALPHA, before, before));
        if (policy == Policy.PRESERVE || (policy == Policy.AUTO && before.verdict() == TransparencyInspector.Verdict.SUSPICIOUS))
            return new Result(png, null, new Decision(policy, Action.NEEDS_REVIEW, before, before));
        var segmented = segmentations.uploadBytes(png, method, null, 0);
        UUID id = UUID.fromString(segmented.id()); byte[] result = Files.readAllBytes(segmentations.file(id, "cutout.png"));
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
