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
import com.smartcloset.management.application.ManagementTemplateService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

/** Outfit template review (design 管理接口二期 §8). */
@Validated
@RestController
@ConditionalOnManagementEnabled
@RequestMapping("/api/manage/v1/templates")
public class ManagementTemplateController {
    static final String TEMPLATE_ID = "^[A-Za-z0-9_.-]{1,80}$";
    public record Review(@Pattern(regexp = "^(PENDING_REVIEW|APPROVED|REJECTED)$") String reviewStatus, Boolean enabled,
                         @DecimalMin("0") @DecimalMax("5") Double bonus) {}
    private final ManagementTemplateService templates;
    private final StrictJsonBodies bodies;
    public ManagementTemplateController(ManagementTemplateService templates, StrictJsonBodies bodies) { this.templates = templates; this.bodies = bodies; }

    @GetMapping
    public Map<String, Object> list() { return templates.list(); }

    @PatchMapping("/{id}")
    public Map<String, Object> review(@PathVariable @Pattern(regexp = TEMPLATE_ID) String id, @RequestBody(required = false) byte[] body) {
        var r = bodies.read(body, Review.class);
        if (r.reviewStatus() == null && r.enabled() == null && r.bonus() == null) throw com.smartcloset.management.ManagementException.invalid("至少需要 reviewStatus、enabled、bonus 之一。");
        return templates.review(id, r.reviewStatus(), r.enabled(), r.bonus());
    }
}
