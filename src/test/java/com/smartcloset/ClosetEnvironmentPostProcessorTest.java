package com.smartcloset;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class ClosetEnvironmentPostProcessorTest {
    @Test void deploymentAliasesOverrideImportedYamlButNotCommandLine() {
        var env = new StandardEnvironment();
        env.getPropertySources().replace("systemEnvironment", new MapPropertySource("systemEnvironment", Map.of(
                "CLOSET_AGENT_MODEL", "env-model", "CLOSET_COOKIE_SECURE", "true")));
        env.getPropertySources().addLast(new MapPropertySource("importedYaml", Map.of("agent.default-model", "yaml-model")));
        new ClosetEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());
        assertEquals("env-model", env.getProperty("agent.default-model"));
        assertEquals("true", env.getProperty("server.servlet.session.cookie.secure"));
        env.getPropertySources().addFirst(new MapPropertySource("commandLineArgs", Map.of("agent.default-model", "cli-model")));
        assertEquals("cli-model", env.getProperty("agent.default-model"));
    }
    @Test void emptyCookieAliasLeavesSecureDetectionToTheServer() {
        var env = new StandardEnvironment();
        env.getPropertySources().replace("systemEnvironment", new MapPropertySource("systemEnvironment", Map.of("CLOSET_COOKIE_SECURE", "")));
        new ClosetEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());
        assertNull(env.getProperty("server.servlet.session.cookie.secure"));
    }
}
