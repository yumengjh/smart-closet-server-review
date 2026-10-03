package com.smartcloset.batch;

import com.smartcloset.recognition.Recognition;
import com.smartcloset.generation.GenerationService;
import java.util.*;
import tools.jackson.databind.JsonNode;

public final class Batch {
    private Batch() {}
    public enum Channel { SINGLE, MULTIPLE }
    public enum Strategy { SMART, FULL, MANUAL }
    public record Upload(Channel channel, Strategy strategy) {}
    public record Marker(UUID sourceId, double x, double y) {}
    public record Detect(List<Marker> markers, String provider, String model) {}
    public record Start(Detect recognition, String generationProvider, String generationModel, boolean reviewBeforeGeneration) {
        public Start(Detect recognition,String provider,String model) { this(recognition,provider,model,false); }
    }
    public record Generate(List<UUID> targetIds, int groupSize, int referenceSide, String sizePolicy,
                           String provider, String model, String quality) {}
    public record Source(UUID id, String name, String originalUrl, String previewUrl, String originalHash, String previewHash,
                         String originalFile, String previewFile, long bytes, int width, int height) {}
    public static class Target {
        public UUID id, sourceId, garmentId, jobId, pendingCallId;
        public Recognition.Result recognition;
        public double visibleFraction;
        public boolean keyDetailsVisible, completion, confirmed;
        public String reason, status = "DETECTED", outputUrl;
        public int slot;
        public Target() {}
        public boolean eligible(Strategy strategy) { return strategy != Strategy.SMART || visibleFraction >= .8 && keyDetailsVisible; }
    }
    public record Metrics(int uploaded,int recognized,int skipped,int generated,int confirmed) {}
    public static class View {
        public UUID id;
        public Channel channel;
        public Strategy strategy;
        public String status = "UPLOADED", error;
        public long createdAtMs;
        public List<Source> sources = new ArrayList<>();
        public List<Target> targets = new ArrayList<>();
        public List<Call> calls = new ArrayList<>();
        public Metrics metrics;
        public Generate automaticGeneration;
        public String automaticGenerationRevision;
        public View() {}
    }
    public record Call(UUID id, String type, String status, String provider, String model, long startedAtMs, Long finishedAtMs,
                       JsonNode usage, Double costUsd, String error, List<UUID> targetIds, String referenceUrl, String atlasUrl,
                       String size, String quality, int referenceSide, String providerRequestId) {}
    public record DetectionPrepared(Recognition.Prepared recognition, Detect request) {}
    public record GenerationPrepared(GenerationService.Prepared generation, String revision, Generate request, List<UUID> targetIds) {}
}
