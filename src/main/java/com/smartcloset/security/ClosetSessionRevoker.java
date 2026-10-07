package com.smartcloset.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Deletes persisted Spring Sessions of one account so credential and status changes take effect immediately. */
@Component
public class ClosetSessionRevoker {
    private final JdbcTemplate jdbc;
    public ClosetSessionRevoker(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Attributes cascade via SPRING_SESSION_ATTRIBUTES_FK. Returns the number of removed sessions. */
    public int revokeAll(String username) {
        return jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?", username);
    }
}
