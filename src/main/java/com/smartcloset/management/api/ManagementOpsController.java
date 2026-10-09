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
import com.smartcloset.management.application.ManagementOpsService;

/** Operations (design 管理接口二期 §4): read-only status, background tasks and the management operation log. */
@Validated
@RestController
@ConditionalOnManagementEnabled
@RequestMapping("/api/manage/v1/ops")
public class ManagementOpsController {
    private final ManagementOpsService ops;
    public ManagementOpsController(ManagementOpsService ops) { this.ops = ops; }

    @GetMapping("/status")
    public Map<String, Object> status() { return ops.status(); }

    @GetMapping("/tasks")
    public Map<String, Object> tasks(@RequestParam(name = "status", required = false) List<@Pattern(regexp = "^[A-Za-z_]{1,32}$") String> statuses,
            @RequestParam(required = false) @Pattern(regexp = "^[A-Z_]{1,40}$") String kind,
            @RequestParam(defaultValue = "1") @Min(1) @Max(100000) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ops.tasks(statuses, kind, page, size);
    }

    @GetMapping("/operation-log")
    public Map<String, Object> operationLog(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Size(max = 64) String action,
            @RequestParam(required = false) @Pattern(regexp = ManagementUserController.USERNAME) String targetUsername,
            @RequestParam(defaultValue = "1") @Min(1) @Max(100000) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ops.operationLog(from, to, action, targetUsername, page, size);
    }
}
