package com.smartcloset.generation;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class GenerationConfigurationTest {
    final JsonMapper json = JsonMapper.builder().build();
    final GenerationProperties.Provider yaml = new GenerationProperties.Provider(GenerationProperties.Protocol.GEMINI,
            URI.create("https://yaml.example/v1"), "yaml-secret", "yaml-model", Map.of("quality", "high"), List.of("yaml.example"), 100);

    @Test void environmentOverridesYamlWithoutRemovingOtherProvidersAndPreservesJsonTypes() {
        var env = new MockEnvironment().withProperty("CLOSET_YUMG_BASE_URL", "https://env.example/v1")
                .withProperty("CLOSET_YUMG_API_KEY", "env-secret").withProperty("CLOSET_YUMG_PROTOCOL", "OPENAI_IMAGES")
                .withProperty("CLOSET_YUMG_DEFAULT_MODEL", "env-model").withProperty("CLOSET_YUMG_TIMEOUT_SECONDS", "200")
                .withProperty("CLOSET_YUMG_IMAGE_HOSTS", "images.example, cdn.example")
                .withProperty("CLOSET_YUMG_DEFAULTS_JSON", "{\"quality\":\"medium\",\"count\":1,\"enabled\":true}");
        var result = GenerationConfiguration.withEnvironment(new GenerationProperties(Map.of("yumg", yaml, "other", yaml)), env, json);
        var provider = result.providers().get("yumg");
        assertEquals(URI.create("https://env.example/v1"), provider.baseUrl()); assertEquals("env-secret", provider.apiKey());
        assertEquals("env-model", provider.defaultModel()); assertEquals(200, provider.timeoutSeconds());
        assertEquals(GenerationProperties.Protocol.OPENAI_IMAGES, provider.protocol());
        assertEquals(List.of("images.example", "cdn.example"), provider.imageHosts());
        assertEquals(Map.of("quality", "medium", "count", 1, "enabled", true), provider.defaults());
        assertEquals(yaml, result.providers().get("other")); assertFalse(provider.toString().contains("secret"));
    }
    @Test void emptyEnvironmentPreservesYamlAndNoAiConfigurationIsValid() {
        var original = new GenerationProperties(Map.of("yumg", yaml));
        assertEquals(original, GenerationConfiguration.withEnvironment(original, new MockEnvironment().withProperty("CLOSET_YUMG_API_KEY", ""), json));
        assertTrue(GenerationConfiguration.withEnvironment(new GenerationProperties(Map.of()), new MockEnvironment(), json).providers().isEmpty());
    }
    @Test void pureEnvironmentWorksAndInvalidDefaultsDoNotExposeTheirContent() {
        var env = new MockEnvironment().withProperty("CLOSET_YUMG_BASE_URL", "https://env.example/v1").withProperty("CLOSET_YUMG_API_KEY", "secret");
        assertTrue(new GenerationConfiguration().generationProperties(env, json).providers().get("yumg").configured());
        env.withProperty("CLOSET_YUMG_DEFAULTS_JSON", "private-invalid-json");
        var error = assertThrows(IllegalArgumentException.class, () -> GenerationConfiguration.withEnvironment(new GenerationProperties(Map.of()), env, json));
        assertFalse(error.getMessage().contains("private")); assertNull(error.getCause());
    }
}
