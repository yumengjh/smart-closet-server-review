package com.smartcloset.management.api;

import com.smartcloset.management.ConditionalOnManagementEnabled;
import com.smartcloset.management.application.ManagementInsightService;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@ConditionalOnManagementEnabled
@RequestMapping("/api/manage/v1/insights")
public class ManagementInsightController {
    private final ManagementInsightService insights;
    public ManagementInsightController(ManagementInsightService insights) { this.insights = insights; }

    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam(required = false) @Pattern(regexp = ManagementUserController.USERNAME) String username,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return insights.overview(username, ManagementInsightService.range(from, to));
    }

    @GetMapping("/wardrobe")
    public Map<String, Object> wardrobe(@RequestParam(required = false) @Pattern(regexp = ManagementUserController.USERNAME) String username) {
        return insights.wardrobe(username);
    }

    @GetMapping("/usage")
    public Map<String, Object> usage(@RequestParam(required = false) @Pattern(regexp = ManagementUserController.USERNAME) String username) {
        return insights.usage(username);
    }

    @GetMapping("/trends")
    public Map<String, Object> trends(@RequestParam(required = false) @Pattern(regexp = ManagementUserController.USERNAME) String username,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "metric") List<String> metrics,
            @RequestParam(defaultValue = "day") String granularity) {
        return insights.trends(username, ManagementInsightService.range(from, to), metrics, granularity);
    }
}
