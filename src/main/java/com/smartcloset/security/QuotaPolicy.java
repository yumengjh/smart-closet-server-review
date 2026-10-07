package com.smartcloset.security;

import com.smartcloset.agent.AgentProperties;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Single source of per-account daily limits; per-user overrides live in closet_user_quota. */
@Component
public class QuotaPolicy {
    public static final int DEFAULT_IMAGE_DAILY_LIMIT = 20;
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public record Item(int limit, int used, boolean overridden) {}
    public record Snapshot(Item image, Item agent, long resetAtMs) {}
    public record Overrides(Integer imageDailyLimit, Integer agentDailyLimit) {}

    private final JdbcTemplate jdbc;
    private final AgentProperties agent;
    public QuotaPolicy(JdbcTemplate jdbc, AgentProperties agent) { this.jdbc = jdbc; this.agent = agent; }

    public static LocalDate today() { return LocalDate.now(ZONE); }
    public int imageDailyLimit(String owner) { var o = overrides(owner).imageDailyLimit(); return o == null ? DEFAULT_IMAGE_DAILY_LIMIT : o; }
    public int agentDailyLimit(String owner) { var o = overrides(owner).agentDailyLimit(); return o == null ? agent.dailyLimit() : o; }

    public Overrides overrides(String owner) {
        if (owner == null) return new Overrides(null, null);
        var rows = jdbc.query("SELECT image_daily_limit,agent_daily_limit FROM closet_user_quota WHERE owner_username=?",
                (r, n) -> new Overrides((Integer) r.getObject(1), (Integer) r.getObject(2)), owner);
        return rows.isEmpty() ? new Overrides(null, null) : rows.getFirst();
    }

    @Transactional
    public void setOverrides(String owner, Overrides overrides) {
        for (Integer value : new Integer[]{overrides.imageDailyLimit(), overrides.agentDailyLimit()})
            if (value != null && (value < 0 || value > 1000)) throw new IllegalArgumentException("额度需在 0 到 1000 之间。");
        jdbc.update("DELETE FROM closet_user_quota WHERE owner_username=?", owner);
        if (overrides.imageDailyLimit() != null || overrides.agentDailyLimit() != null)
            jdbc.update("INSERT INTO closet_user_quota(owner_username,image_daily_limit,agent_daily_limit,updated_at_ms) VALUES(?,?,?,?)",
                    owner, overrides.imageDailyLimit(), overrides.agentDailyLimit(), System.currentTimeMillis());
    }

    public Snapshot snapshot(String owner) {
        var day = java.sql.Date.valueOf(today());
        var o = overrides(owner);
        int image = used("ai_daily_quota", owner, day), messages = used("agent_daily_quota", owner, day);
        return new Snapshot(new Item(o.imageDailyLimit() == null ? DEFAULT_IMAGE_DAILY_LIMIT : o.imageDailyLimit(), image, o.imageDailyLimit() != null),
                new Item(o.agentDailyLimit() == null ? agent.dailyLimit() : o.agentDailyLimit(), messages, o.agentDailyLimit() != null),
                today().plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli());
    }

    private int used(String table, String owner, java.sql.Date day) {
        var rows = jdbc.query("SELECT used FROM " + table + " WHERE owner_username=? AND quota_date=?", (r, n) -> r.getInt(1), owner, day);
        return rows.isEmpty() ? 0 : rows.getFirst();
    }
}
