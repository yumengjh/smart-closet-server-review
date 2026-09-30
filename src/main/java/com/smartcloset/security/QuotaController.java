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
    public QuotaController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @GetMapping("/api/me/quota") public Map<String,Object> quota() {
        String name = ClosetIdentity.username(); var today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        var values = jdbc.query("SELECT used FROM ai_daily_quota WHERE owner_username=? AND quota_date=?",
                (r,n) -> r.getInt(1), name, java.sql.Date.valueOf(today));
        return Map.of("limit", 20, "used", values.isEmpty() ? 0 : values.getFirst(),
                "resetAt", today.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toString());
    }
}
