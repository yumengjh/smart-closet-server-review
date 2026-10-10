package com.smartcloset.management.api;

import com.smartcloset.management.ConditionalOnManagementEnabled;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import com.smartcloset.management.application.ManagementExportService;
import com.smartcloset.management.application.ManagementInsightService;

/** Data-warehouse exports (design 管理接口二期 §7.2/§7.3): a daily wide table and a de-identified incremental event stream. */
@Validated
@RestController
@ConditionalOnManagementEnabled
@RequestMapping("/api/manage/v1/exports")
public class ManagementExportController {
    private final ManagementInsightService insights;
    private final ManagementExportService exports;
    public ManagementExportController(ManagementInsightService insights, ManagementExportService exports) { this.insights = insights; this.exports = exports; }

    @GetMapping("/daily")
    public ResponseEntity<?> daily(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "json") @Pattern(regexp = "^(json|csv)$") String format) {
        var range = ManagementInsightService.range(from, to);
        var rows = insights.dailyTable(range);
        if ("json".equals(format)) return ResponseEntity.ok(Map.of("from", range.from().toString(), "to", range.to().toString(),
            "columns", ManagementInsightService.DAILY_COLUMNS, "rows", rows));
        var csv = new StringBuilder("date,").append(String.join(",", ManagementInsightService.DAILY_COLUMNS)).append('\n');
        for (var row : rows) csv.append(String.join(",", row.values().stream().map(String::valueOf).toList())).append('\n');
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
            .header("Content-Disposition", "attachment; filename=\"closet-daily-" + range.from() + "-" + range.to() + ".csv\"").body(csv.toString());
    }

    @GetMapping("/events")
    public Map<String, Object> events(@RequestParam(required = false) @Size(max = 200) String cursor, @RequestParam(required = false) @Min(0) Long since,
            @RequestParam(defaultValue = "500") @Min(1) @Max(1000) int limit, @RequestParam(name = "types", required = false) List<@Pattern(regexp = "^[a-z_]{1,40}$") String> types) {
        return exports.events(cursor, since, limit, types);
    }
}
