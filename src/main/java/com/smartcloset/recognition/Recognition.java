package com.smartcloset.recognition;

import java.util.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

public final class Recognition {
    private Recognition() {}
    public enum Assessment { READY, PARTIAL, NO_GARMENT, MULTIPLE_GARMENTS, UNUSABLE }
    public enum State { OBSERVED, INFERRED, UNKNOWN, NOT_APPLICABLE, CONFLICT }
    public record Request(String provider, String model) {}
    public record Prepared(String provider, String model, String providerRevision, String promptVersion,
                           String schemaVersion, String prompt, JsonNode schema) {}
    public record Attribute(List<String> values, State status, String evidence) {}
    public record Issue(String code, String message, List<String> affectedFields, String suggestedAction) {}
    public record Bounds(double x, double y, double width, double height) {}
    public record Target(String description, boolean worn, Bounds bounds) {}
    public record Result(Assessment assessment, Map<String, Attribute> attributes, List<Issue> issues,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Target target) {
        public Result(Assessment assessment, Map<String, Attribute> attributes, List<Issue> issues) { this(assessment, attributes, issues, null); }
        public boolean blocksImages() {
            return assessment == Assessment.NO_GARMENT || assessment == Assessment.MULTIPLE_GARMENTS || assessment == Assessment.UNUSABLE;
        }
    }
    public record Output(Result result, JsonNode raw, JsonNode usage, String requestId) {}
}
