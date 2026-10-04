package com.smartcloset.security;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("closet.cors")
public record ClosetCorsProperties(List<String> allowedOrigins) {
    public ClosetCorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins.stream().filter(s -> !s.isBlank()).map(String::trim).toList();
        for (String origin : allowedOrigins) {
            URI uri = URI.create(origin);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !uri.getRawPath().isEmpty() || origin.contains("*"))
                throw new IllegalArgumentException("CORS origins must be exact HTTP(S) origins without paths or wildcards");
        }
    }
}
