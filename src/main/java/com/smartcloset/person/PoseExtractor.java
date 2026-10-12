package com.smartcloset.person;

import tools.jackson.databind.JsonNode;

/** One multimodal call per uploaded pose picture. Replaced by a fake in tests; the real one reuses the recognition provider. */
public interface PoseExtractor {
    record Extraction(JsonNode raw, String model) {}
    Extraction extract(byte[] png);
}
