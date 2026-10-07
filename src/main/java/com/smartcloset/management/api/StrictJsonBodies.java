package com.smartcloset.management.api;

import com.smartcloset.management.ConditionalOnManagementEnabled;
import com.smartcloset.management.ManagementException;
import jakarta.validation.Validator;
import java.util.Comparator;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/**
 * The contract declares additionalProperties: false. The application-wide mapper ignores unknown fields,
 * so management bodies are read with a strict reader instead of changing end-user behaviour.
 */
@Component
@ConditionalOnManagementEnabled
public class StrictJsonBodies {
    private final ObjectMapper json;
    private final Validator validator;
    public StrictJsonBodies(ObjectMapper json, Validator validator) { this.json = json; this.validator = validator; }

    public <T> T read(byte[] body, Class<T> type) {
        T value;
        try {
            value = json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).readValue(body == null || body.length == 0 ? "{}".getBytes() : body);
        } catch (Exception e) { throw ManagementException.malformed(); }
        if (value == null) throw ManagementException.malformed();
        var violations = validator.validate(value).stream()
                .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                .map(v -> Map.of("field", v.getPropertyPath().toString(), "message", v.getMessage())).toList();
        if (!violations.isEmpty()) throw ManagementException.invalid(violations);
        return value;
    }
}
