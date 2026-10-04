package com.smartcloset.generation;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class GenerationConfiguration {
    @Bean GenerationProperties generationProperties(Environment environment, ObjectMapper json) {
        var configured = Binder.get(environment).bind("generation", Bindable.of(GenerationProperties.class))
                .orElse(new GenerationProperties(Map.of()));
        return withEnvironment(configured, environment, json);
    }

    static GenerationProperties withEnvironment(GenerationProperties configured, Environment env, ObjectMapper json) {
        var providers = new LinkedHashMap<>(configured.providers());
        var old = providers.get("yumg");
        String url = value(env, "BASE_URL", old == null ? null : old.baseUrl().toString());
        String key = value(env, "API_KEY", old == null ? null : old.apiKey());
        if (url == null) {
            if (key != null) throw new IllegalArgumentException("CLOSET_YUMG_BASE_URL is required when CLOSET_YUMG_API_KEY is set");
            return configured;
        }
        var protocol = GenerationProperties.Protocol.valueOf(value(env, "PROTOCOL", old == null ? "OPENAI_IMAGES" : old.protocol().name()));
        String model = value(env, "DEFAULT_MODEL", old == null ? null : old.defaultModel());
        Map<String, Object> defaults = old == null ? Map.of() : old.defaults();
        String defaultsJson = value(env, "DEFAULTS_JSON", null);
        if (defaultsJson != null) {
            try { defaults = json.readValue(defaultsJson, new TypeReference<Map<String, Object>>() {}); }
            catch (Exception ignored) { throw new IllegalArgumentException("CLOSET_YUMG_DEFAULTS_JSON must be a JSON object"); }
            if (defaults == null) throw new IllegalArgumentException("CLOSET_YUMG_DEFAULTS_JSON must be a JSON object");
        }
        List<String> hosts = old == null ? List.of() : old.imageHosts();
        String hostsValue = value(env, "IMAGE_HOSTS", null);
        if (hostsValue != null) hosts = Arrays.stream(hostsValue.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        Integer timeout = old == null ? null : old.timeoutSeconds();
        String timeoutValue = value(env, "TIMEOUT_SECONDS", null);
        if (timeoutValue != null) timeout = Integer.valueOf(timeoutValue);
        providers.put("yumg", new GenerationProperties.Provider(protocol, URI.create(url), key, model, defaults, hosts, timeout));
        return new GenerationProperties(providers);
    }

    private static String value(Environment env, String suffix, String fallback) {
        String value = env.getProperty("CLOSET_YUMG_" + suffix);
        return value == null || value.isBlank() ? fallback : value;
    }
}
