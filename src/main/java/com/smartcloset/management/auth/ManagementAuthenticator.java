package com.smartcloset.management.auth;

import jakarta.servlet.http.HttpServletRequest;

/** Pluggable service authentication for /api/manage/**. Implementations throw {@link Failure} to reject. */
public interface ManagementAuthenticator {
    ManagementPrincipal authenticate(HttpServletRequest request, byte[] body, String requestId);

    class Failure extends RuntimeException {
        private final int status;
        private final String code;
        public Failure(int status, String code, String message) { super(message); this.status = status; this.code = code; }
        public int status() { return status; }
        public String code() { return code; }
    }
}
