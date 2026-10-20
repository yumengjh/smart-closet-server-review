package com.smartcloset.management.api;

import com.smartcloset.management.ConditionalOnManagementEnabled;
import com.smartcloset.management.application.ManagementInsightService;
import com.smartcloset.security.AccountEvents;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Site-wide account audit feed (register, login, password, profile, admin and management actions). Read-only. */
@Validated
@RestController
@ConditionalOnManagementEnabled
public class ManagementAccountEventController {
    private final AccountEvents events;
    public ManagementAccountEventController(AccountEvents events) { this.events = events; }

    @GetMapping("/api/manage/v1/account-events")
    public AccountEvents.Page events(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Pattern(regexp = ManagementUserController.USERNAME) String username,
            @RequestParam(required = false) @Pattern(regexp = "^[A-Z_]{1,48}$") String action,
            @RequestParam(required = false) @Size(max = 200) String actor,
            @RequestParam(defaultValue = "1") @Min(1) @Max(100000) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var range = ManagementInsightService.range(from, to);
        return events.search(username, action, actor, range.fromMs(), range.toMsExclusive(), page, size);
    }
}
