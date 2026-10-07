package com.smartcloset.management.application;

import com.smartcloset.management.ConditionalOnManagementEnabled;
import com.smartcloset.management.auth.ManagementPrincipal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Local record of management writes. Never stores passwords; hosts keep their own audit trail. */
@Component
@ConditionalOnManagementEnabled
public class ManagementOperationLog {
    private final JdbcTemplate jdbc;
    public ManagementOperationLog(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void record(String action, String target, String result, String detail) {
        var principal = current();
        jdbc.update("INSERT INTO management_operation_log(id,occurred_at_ms,key_id,operator,request_id,action,target_username,result,detail) VALUES(?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), System.currentTimeMillis(), principal == null ? "unknown" : principal.keyId(),
                principal == null ? null : principal.operator(), principal == null ? null : principal.requestId(), action, target, result,
                detail == null ? null : detail.substring(0, Math.min(1000, detail.length())));
    }

    static ManagementPrincipal current() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof ManagementPrincipal p ? p : null;
    }
}
