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
import com.smartcloset.management.application.ManagementDashboardService;

/** Big-screen snapshot (design 管理接口二期 §7.1): aggregates only, no user names. */
@RestController
@ConditionalOnManagementEnabled
@RequestMapping("/api/manage/v1/dashboard")
public class ManagementDashboardController {
    private final ManagementDashboardService dashboard;
    public ManagementDashboardController(ManagementDashboardService dashboard) { this.dashboard = dashboard; }

    @GetMapping("/snapshot")
    public Map<String, Object> snapshot() { return dashboard.snapshot(); }
}
