package com.smartcloset.generation;

import java.net.URI;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("generation")
public record GenerationProperties(Map<String, Provider> providers) {
    public GenerationProperties { providers = providers == null ? Map.of() : Map.copyOf(providers); }
    public enum Protocol { OPENAI_IMAGES, GEMINI }
    public record Provider(Protocol protocol, URI baseUrl, String apiKey, String defaultModel,
                           Map<String, Object> defaults, List<String> imageHosts, Integer timeoutSeconds) {
        public Provider {
            defaults = defaults == null ? Map.of() : Map.copyOf(defaults);
            imageHosts = imageHosts == null ? List.of() : List.copyOf(imageHosts);
            timeoutSeconds = timeoutSeconds == null ? 180 : timeoutSeconds;
            if (timeoutSeconds < 1 || timeoutSeconds > 600) throw new IllegalArgumentException("Provider timeout must be 1..600 seconds");
            if (protocol == null || baseUrl == null || baseUrl.getHost() == null || baseUrl.getQuery() != null
                    || baseUrl.getFragment() != null || baseUrl.getUserInfo() != null
                    || !("https".equals(baseUrl.getScheme()) || ("http".equals(baseUrl.getScheme())
                    && Set.of("localhost", "127.0.0.1", "[::1]").contains(baseUrl.getHost()))))
                throw new IllegalArgumentException("Provider requires an HTTPS API base URL (HTTP allowed only for loopback testing)");
        }
        // Never include credentials in diagnostics, even when a config record is logged accidentally.
        @Override public String toString() { return "Provider[protocol=" + protocol + ", configured=" + configured() + "]"; }
        public boolean configured() { return apiKey != null && !apiKey.isBlank(); }
        public URI endpoint(String path) { return URI.create(baseUrl.toString().replaceAll("/+$", "") + path); }
    }
}
