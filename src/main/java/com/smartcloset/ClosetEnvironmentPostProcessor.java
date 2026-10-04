package com.smartcloset;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** Apply deployment aliases above imported YAML, retaining normal command-line precedence. */
public class ClosetEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("CLOSET_COOKIE_SECURE", "server.servlet.session.cookie.secure"),
            Map.entry("CLOSET_RECOGNITION_MODEL", "recognition.providers.yumg.default-model"),
            Map.entry("CLOSET_AGENT_MODEL", "agent.default-model"),
            Map.entry("CLOSET_AGENT_ALLOWED_MODELS", "agent.allowed-models"),
            Map.entry("CLOSET_AGENT_DAILY_LIMIT", "agent.daily-limit"),
            Map.entry("CLOSET_AGENT_MAX_TOOL_CALLS", "agent.max-tool-calls"),
            Map.entry("CLOSET_AGENT_TIMEOUT_SECONDS", "agent.timeout-seconds"));

    @Override public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        var values = new LinkedHashMap<String, Object>();
        ALIASES.forEach((alias, property) -> {
            String value = environment.getProperty(alias);
            if (value != null && !value.isBlank()) values.put(property, value);
        });
        var source = new MapPropertySource("closetDeploymentEnvironment", values);
        if (environment.getPropertySources().contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
            environment.getPropertySources().addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, source);
        else environment.getPropertySources().addFirst(source);
    }

    @Override public int getOrder() { return ConfigDataEnvironmentPostProcessor.ORDER + 1; }
}
