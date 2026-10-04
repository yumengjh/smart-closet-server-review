package com.smartcloset.agent;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agent")
public record AgentProperties(URI runtimeUrl, URI callbackUrl, String secret, String credentialProvider,
        String defaultModel, List<String> allowedModels, Integer dailyLimit, Integer maxToolCalls, Integer timeoutSeconds) {
    public AgentProperties {
        runtimeUrl = runtimeUrl == null ? URI.create("http://127.0.0.1:8091") : runtimeUrl;
        callbackUrl = callbackUrl == null ? URI.create("http://127.0.0.1:8080") : callbackUrl;
        credentialProvider = credentialProvider == null ? "yumg" : credentialProvider;
        defaultModel = defaultModel == null ? "gpt-6-luna" : defaultModel;
        allowedModels = allowedModels == null ? List.of("gpt-6-luna", "gpt-5.6-luna") : List.copyOf(allowedModels);
        dailyLimit = dailyLimit == null ? 30 : dailyLimit;
        maxToolCalls = maxToolCalls == null ? 8 : maxToolCalls;
        timeoutSeconds = timeoutSeconds == null ? 120 : timeoutSeconds;
        if (!allowedModels.contains(defaultModel) || dailyLimit < 1 || maxToolCalls < 1 || maxToolCalls > 20 || timeoutSeconds < 5 || timeoutSeconds > 300)
            throw new IllegalArgumentException("Invalid agent limits or default model");
        for (var uri : List.of(runtimeUrl, callbackUrl))
            if (uri.getHost() == null || uri.getUserInfo() != null || !List.of("http", "https").contains(uri.getScheme()))
                throw new IllegalArgumentException("Agent endpoints require HTTP(S)");
    }
    public boolean configured() { return secret != null && secret.length() >= 32; }
    @Override public String toString() { return "AgentProperties[defaultModel=" + defaultModel + ", configured=" + configured() + "]"; }
}
