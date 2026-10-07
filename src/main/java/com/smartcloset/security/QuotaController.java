package com.smartcloset.security;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class QuotaController {
    private final JdbcTemplate jdbc;
    private final QuotaPolicy quotas;
    public QuotaController(JdbcTemplate jdbc, QuotaPolicy quotas) { this.jdbc = jdbc; this.quotas = quotas; }
    @GetMapping("/api/me/quota") public Map<String,Object> quota() {
        String name = ClosetIdentity.username(); var today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        var values = jdbc.query("SELECT used FROM ai_daily_quota WHERE owner_username=? AND quota_date=?",
                (r,n) -> r.getInt(1), name, java.sql.Date.valueOf(today));
        return Map.of("limit", quotas.imageDailyLimit(name), "used", values.isEmpty() ? 0 : values.getFirst(),
                "resetAt", today.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toString());
    }
}
