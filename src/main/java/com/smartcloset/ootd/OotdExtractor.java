package com.smartcloset.ootd;

import tools.jackson.databind.JsonNode;

/** One multimodal call per sample photo (D47). Replaced by a fake in tests; the real one reuses the recognition provider. */
public interface OotdExtractor {
    record Extraction(JsonNode raw, String model, String promptVersion, JsonNode usage) {}
    Extraction extract(byte[] png);
}
