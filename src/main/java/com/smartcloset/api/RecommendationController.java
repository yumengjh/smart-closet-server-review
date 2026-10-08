package com.smartcloset.api;

import com.smartcloset.recommend.Intent;
import com.smartcloset.recommend.RecommendationService;
import com.smartcloset.security.ClosetIdentity;
import java.time.LocalDate;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** Zero-model recommendation endpoints used by pages and the assistant's quick entry. */
@RestController
public class RecommendationController {
    public record RecommendRequest(Intent.Occasion occasion, Double temperatureC, String season, Double formality, List<String> wantStyles,
                                   List<String> wantColors, List<String> avoidColors, String freeText, List<String> requiredGarmentIds,
                                   List<String> excludedGarmentIds, Integer count) {
        Intent intent() {
            var sources = new HashMap<String, String>();
            if (temperatureC != null) sources.put("temperatureC", "USER");
            return new Intent(occasion, temperatureC, season, formality, wantStyles, wantColors, avoidColors, freeText, requiredGarmentIds, excludedGarmentIds, count == null ? 3 : count, sources);
        }
    }
    public record FeedbackRequest(String verdict, List<String> reasons, String garmentId) {}
    public record SignalRequest(List<Map<String, String>> events) {}
    public record WearRequest(String outfitId, List<String> garmentIds, LocalDate wornOn) {}
    public record AdoptRequest(List<String> garmentIds) {}

    private final RecommendationService recommendations;
    public RecommendationController(RecommendationService recommendations) { this.recommendations = recommendations; }

    @PostMapping("/api/recommendations")
    public RecommendationService.View recommend(@RequestBody RecommendRequest request) {
        return recommendations.recommend(ClosetIdentity.username(), request.intent(), "PAGE");
    }

    /** 今日灵感 (P4): reviewed templates filled with the user's clothes; zero model calls. */
    @GetMapping("/api/recommendations/inspiration")
    public RecommendationService.InspirationView inspiration(@RequestParam(required = false) Double temperatureC) {
        return recommendations.inspiration(ClosetIdentity.username(), temperatureC);
    }

    @GetMapping("/api/recommendations/{runId}/more")
    public RecommendationService.View more(@PathVariable String runId, @RequestParam(defaultValue = "3") int count) {
        return recommendations.more(ClosetIdentity.username(), runId, count);
    }

    @GetMapping("/api/recommendations/{runId}/outfits/{key}/swap")
    public RecommendationService.SwapView swap(@PathVariable String runId, @PathVariable String key, @RequestParam String garmentId) {
        return recommendations.swap(ClosetIdentity.username(), runId, key, garmentId);
    }

    @PostMapping("/api/recommendations/{runId}/outfits")
    public RecommendationService.OutfitView adopt(@PathVariable String runId, @RequestBody AdoptRequest request) {
        return recommendations.adopt(ClosetIdentity.username(), runId, request.garmentIds() == null ? List.of() : request.garmentIds());
    }

    @PostMapping("/api/recommendations/logs/{logId}/feedback")
    public Map<String, Object> feedback(@PathVariable String logId, @RequestBody FeedbackRequest request) {
        return recommendations.feedback(ClosetIdentity.username(), logId, request.verdict(), request.reasons(), request.garmentId());
    }

    @PostMapping("/api/recommendations/events")
    public Map<String, Object> events(@RequestBody SignalRequest request) {
        recommendations.signals(ClosetIdentity.username(), request.events());
        return Map.of("accepted", true);
    }

    @PostMapping("/api/wear-logs")
    public Map<String, Object> wear(@RequestBody WearRequest request) {
        return recommendations.wear(ClosetIdentity.username(), request.outfitId(), request.garmentIds(), request.wornOn());
    }

    /** "为什么没推荐它 / 为什么排第几" from the cached run's rule trace (D27, D33). Zero model calls. */
    @GetMapping("/api/recommendations/{runId}/explain")
    public Map<String, Object> explain(@PathVariable String runId, @RequestParam(required = false) String garmentId, @RequestParam(required = false) String outfitKey) {
        return recommendations.explain(ClosetIdentity.username(), runId, garmentId, outfitKey);
    }

    @GetMapping("/api/wear-logs/garments/{garmentId}")
    public Map<String, Object> garmentWear(@PathVariable String garmentId) { return recommendations.wearSummary(ClosetIdentity.username(), garmentId); }

    @GetMapping("/api/wear-logs/outfits")
    public Map<String, String> outfitWear() { return recommendations.outfitWearDates(ClosetIdentity.username()); }

    @GetMapping("/api/style-profile")
    public Map<String, Object> profile() { return recommendations.profile(ClosetIdentity.username()); }

    @PatchMapping("/api/style-profile")
    public Object updateProfile(@RequestBody Map<String, Object> patch) { return recommendations.updateSettings(ClosetIdentity.username(), patch); }
}
