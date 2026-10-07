package com.smartcloset.management.auth;

/** The calling host service, plus the optional operator and trace it vouches for. Never a closet end user. */
public record ManagementPrincipal(String keyId, String operator, String requestId) {}
