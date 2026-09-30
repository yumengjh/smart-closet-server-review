package com.smartcloset.recognition;

import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Credentials and base URL are referenced from server-side generation providers. */
@ConfigurationProperties("recognition")
public record RecognitionProperties(String defaultProvider, Map<String, Provider> providers) {
    public RecognitionProperties { providers = providers == null ? Map.of() : Map.copyOf(providers); }
    public enum Protocol { OPENAI_CHAT, GEMINI }
    public record Provider(String credentialProvider, Protocol protocol, String defaultModel,
                           Integer maxOutputTokens, String reasoningEffort) {
        public Provider {
            maxOutputTokens = maxOutputTokens == null ? 4096 : maxOutputTokens;
            if (protocol == null || maxOutputTokens < 512 || maxOutputTokens > 16384)
                throw new IllegalArgumentException("Recognition protocol and token limit required");
            if (reasoningEffort != null && !Set.of("none", "minimal", "low", "medium", "high").contains(reasoningEffort))
                throw new IllegalArgumentException("Unsupported reasoning effort");
        }
    }
}
