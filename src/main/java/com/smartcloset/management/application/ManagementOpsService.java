package com.smartcloset.management.application;

import com.smartcloset.ClosetProperties;
import com.smartcloset.agent.AgentProperties;
import com.smartcloset.generation.GenerationProperties;
import com.smartcloset.image.U2NetEngine;
import com.smartcloset.management.ConditionalOnManagementEnabled;
import com.smartcloset.management.ManagementException;
import com.smartcloset.management.ManagementProperties;
import com.smartcloset.recognition.RecognitionService;
import com.smartcloset.semantic.SemanticIndex;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Operations view (design 管理接口二期 §4, M3): is the service healthy, what needs attention, and why. Read-only; no
 * secrets, provider addresses or user names in the status. Thresholds come from closet.management.ops.
 */
@Service
@ConditionalOnManagementEnabled
public class ManagementOpsService {
    public static final Set<String> TASK_STATUSES = new LinkedHashSet<>(List.of("QUEUED", "RUNNING", "WAITING_QUOTA", "CANCEL_REQUESTED", "SUCCEEDED", "FAILED", "UNKNOWN", "INTERRUPTED", "CANCELLED"));
    static final List<String> ACTIVE = List.of("QUEUED", "RUNNING", "WAITING_QUOTA", "CANCEL_REQUESTED");
    static final List<String> FAILED = List.of("FAILED", "UNKNOWN", "INTERRUPTED");
    public record Issue(String code, String message) {}
    private record Probe(boolean reachable, Long latencyMs, long checkedAtMs) {}

    private final JdbcTemplate jdbc;
    private final ManagementProperties properties;
    private final ClosetProperties closet;
    private final ObjectProvider<U2NetEngine> segmentation;
    private final ObjectProvider<SemanticIndex> semantic;
    private final ObjectProvider<RecognitionService> recognition;
    private final ObjectProvider<GenerationProperties> generation;
    private final ObjectProvider<AgentProperties> agent;
    private ObjectProvider<com.smartcloset.similar.ClipModels> clip;
    private final String databaseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private volatile Probe probe;

    public ManagementOpsService(JdbcTemplate jdbc, ManagementProperties properties, ClosetProperties closet, ObjectProvider<U2NetEngine> segmentation,
                                ObjectProvider<SemanticIndex> semantic, ObjectProvider<RecognitionService> recognition, ObjectProvider<GenerationProperties> generation,
                                ObjectProvider<AgentProperties> agent, @Value("${spring.datasource.url:}") String databaseUrl) {
        this.jdbc = jdbc; this.properties = properties; this.closet = closet; this.segmentation = segmentation; this.semantic = semantic;
        this.recognition = recognition; this.generation = generation; this.agent = agent; this.databaseUrl = databaseUrl;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void imageModel(ObjectProvider<com.smartcloset.similar.ClipModels> clip) { this.clip = clip; }

    public Map<String, Object> status() {
        var issues = new ArrayList<Issue>();
        var ops = properties.ops();
        var out = new LinkedHashMap<String, Object>();
        var runtime = ManagementFactory.getRuntimeMXBean();
        var app = new LinkedHashMap<String, Object>();
        String version = ManagementOpsService.class.getPackage().getImplementationVersion();
        app.put("version", version == null ? "unknown" : version);
        app.put("startedAtMs", runtime.getStartTime()); app.put("uptimeMs", runtime.getUptime()); app.put("java", Runtime.version().toString());

        var memory = ManagementFactory.getMemoryMXBean(); var os = ManagementFactory.getOperatingSystemMXBean();
        var jvm = new LinkedHashMap<String, Object>();
        jvm.put("heapUsedBytes", memory.getHeapMemoryUsage().getUsed()); jvm.put("heapMaxBytes", memory.getHeapMemoryUsage().getMax());
        jvm.put("nonHeapUsedBytes", memory.getNonHeapMemoryUsage().getUsed()); jvm.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        jvm.put("processors", os.getAvailableProcessors()); jvm.put("systemLoadAverage", round(os.getSystemLoadAverage()));
        jvm.put("processCpuLoad", os instanceof com.sun.management.OperatingSystemMXBean sun ? round(sun.getProcessCpuLoad()) : null);
        // Model sessions live outside the Java heap: the resident size is what the machine actually pays (Linux only).
        jvm.put("processRssBytes", rssBytes());
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) { jvm.put("systemMemoryTotalBytes", sun.getTotalMemorySize()); jvm.put("systemMemoryFreeBytes", sun.getFreeMemorySize()); }

        var storage = new LinkedHashMap<String, Object>();
        try {
            var store = Files.getFileStore(Files.createDirectories(closet.storageDir()));
            long total = store.getTotalSpace(), usable = store.getUsableSpace();
            double used = total == 0 ? 0 : round((total - usable) / (double) total);
            storage.put("totalBytes", total); storage.put("usableBytes", usable); storage.put("usedRatio", used);
            if (used >= ops.diskUsedWarn()) issues.add(new Issue("DISK_FULL", "存储所在磁盘已用 " + Math.round(used * 100) + "%"));
        } catch (IOException | RuntimeException e) { storage.put("error", "无法读取存储目录所在磁盘"); }
        storage.put("databaseBytes", databaseBytes());

        var models = new LinkedHashMap<String, Object>();
        var seg = segmentation.getIfAvailable();
        boolean segPresent = seg != null && seg.modelPresent();
        models.put("segmentation", Map.of("present", segPresent, "loaded", seg != null && seg.loaded(),
            "idleUnloadMinutes", seg == null ? 0 : seg.idleUnload().toMinutes(), "unloads", seg == null ? 0 : seg.unloads()));
        if (!segPresent) issues.add(new Issue("SEGMENTATION_MODEL_MISSING", "抠图模型未安装，抠图会改用 GRABCUT 或提示未安装"));
        var index = semantic.getIfAvailable();
        boolean embedding = index != null && index.available();
        var emb = new LinkedHashMap<String, Object>(); emb.put("available", embedding); emb.put("version", index == null ? null : index.model());
        models.put("embedding", emb);
        if (!embedding) issues.add(new Issue("EMBEDDING_MODEL_MISSING", "本地向量模型不可用，搜索与推荐降级为字段加关键词"));

        // Image-text model (optional): missing is not an issue, low memory or a broken file is (search silently degrades).
        var clipModels = clip == null ? null : clip.getIfAvailable();
        String imageVersion = clipModels == null ? null : clipModels.imageVersion();
        if (clipModels != null) {
            var image = new LinkedHashMap<String, Object>(clipModels.status()); image.put("version", imageVersion);
            models.put("image", image);
            var state = clipModels.imageState();
            if (state == com.smartcloset.similar.ClipModels.State.LOW_MEMORY) issues.add(new Issue("IMAGE_MODEL_LOW_MEMORY", "可用内存不足，图文模型没有加载，图像识别已回退为颜色轮廓比对"));
            if (state == com.smartcloset.similar.ClipModels.State.FAILED) issues.add(new Issue("IMAGE_MODEL_FAILED", "图文模型文件无法加载，图像识别已回退为颜色轮廓比对"));
        }
        long active = count("SELECT COUNT(*) FROM garment WHERE closet_status='ACTIVE' AND owner_username IS NOT NULL");
        long indexed = embedding ? count("SELECT COUNT(*) FROM garment_embedding e JOIN garment g ON g.id=e.garment_id WHERE g.closet_status='ACTIVE' AND e.model_version=? AND e.garment_revision=g.revision", index.model()) : 0;
        long measured = count("SELECT COUNT(*) FROM garment_color c JOIN garment g ON g.id=c.garment_id AND g.display_asset_id=c.display_asset_id WHERE g.closet_status='ACTIVE'");
        double semanticCoverage = active == 0 ? 1 : round(indexed / (double) active);
        if (embedding && semanticCoverage < ops.semanticCoverageWarn()) issues.add(new Issue("SEMANTIC_INDEX_BEHIND", "语义索引覆盖 " + Math.round(semanticCoverage * 100) + "%，后台仍在补算"));
        long pictured = imageVersion == null ? 0 : count("SELECT COUNT(*) FROM garment_image_embedding e JOIN garment g ON g.id=e.garment_id AND g.display_asset_id=e.display_asset_id WHERE g.closet_status='ACTIVE' AND e.model_version=?", imageVersion);
        out.put("indexes", Map.of("semantic", Map.of("indexed", indexed, "total", active, "coverage", semanticCoverage),
            "color", Map.of("measured", measured, "total", active, "coverage", active == 0 ? 1 : round(measured / (double) active)),
            "image", Map.of("indexed", pictured, "total", active, "coverage", active == 0 || imageVersion == null ? (imageVersion == null ? 0 : 1) : round(pictured / (double) active))));

        long now = System.currentTimeMillis(), dayAgo = now - Duration.ofDays(1).toMillis();
        var byStatus = new LinkedHashMap<String, Long>(); ACTIVE.forEach(s -> byStatus.put(s, 0L));
        var byKind = new TreeMap<String, Map<String, Long>>();
        jdbc.query("SELECT kind,status,COUNT(*) FROM execution_task WHERE status IN ('QUEUED','RUNNING','WAITING_QUOTA','CANCEL_REQUESTED') GROUP BY kind,status", r -> {
            byStatus.merge(r.getString(2), r.getLong(3), Long::sum);
            byKind.computeIfAbsent(r.getString(1), k -> new LinkedHashMap<>(Map.of("queued", 0L, "running", 0L, "waitingQuota", 0L, "failed24h", 0L)))
                .merge(switch (r.getString(2)) { case "QUEUED" -> "queued"; case "RUNNING", "CANCEL_REQUESTED" -> "running"; default -> "waitingQuota"; }, r.getLong(3), Long::sum);
        });
        long[] failed = {0};
        jdbc.query("SELECT kind,COUNT(*) FROM execution_task WHERE status IN ('FAILED','UNKNOWN','INTERRUPTED') AND updated_at_ms>=? GROUP BY kind", r -> {
            failed[0] += r.getLong(2);
            byKind.computeIfAbsent(r.getString(1), k -> new LinkedHashMap<>(Map.of("queued", 0L, "running", 0L, "waitingQuota", 0L, "failed24h", 0L))).put("failed24h", r.getLong(2));
        }, dayAgo);
        Long oldest = jdbc.queryForObject("SELECT MIN(created_at_ms) FROM execution_task WHERE status='QUEUED'", Long.class);
        Long oldestAge = oldest == null ? null : now - oldest;
        var queue = new LinkedHashMap<String, Object>();
        queue.put("byStatus", byStatus);
        queue.put("byKind", byKind.entrySet().stream().map(e -> { var m = new LinkedHashMap<String, Object>(); m.put("kind", e.getKey()); m.putAll(e.getValue()); return m; }).toList());
        queue.put("oldestQueuedAgeMs", oldestAge); queue.put("failed24h", failed[0]);
        if (oldestAge != null && oldestAge > ops.queueBacklogWarn().toMillis()) issues.add(new Issue("QUEUE_BACKLOG", "最早排队的任务已等待 " + oldestAge / 60_000 + " 分钟"));
        if (failed[0] >= ops.failedTasksWarn()) issues.add(new Issue("TASK_FAILURES", "近 24 小时失败任务 " + failed[0] + " 个"));

        var providers = new LinkedHashMap<String, Object>();
        var rec = recognition.getIfAvailable();
        var recInfo = rec == null ? List.<RecognitionService.ProviderInfo>of() : rec.providers();
        var preferred = recInfo.stream().filter(RecognitionService.ProviderInfo::preferred).findFirst();
        boolean recognitionReady = preferred.map(RecognitionService.ProviderInfo::configured).orElse(false);
        var recMap = new LinkedHashMap<String, Object>(); recMap.put("defaultProvider", preferred.map(RecognitionService.ProviderInfo::id).orElse(null)); recMap.put("configured", recognitionReady);
        providers.put("recognition", recMap);
        if (!recognitionReady) issues.add(new Issue("RECOGNITION_NOT_CONFIGURED", "识图服务商未配置密钥，识图与整套样本提取不可用"));
        var gen = generation.getIfAvailable();
        providers.put("generation", gen == null ? List.of() : gen.providers().entrySet().stream().sorted(Map.Entry.comparingByKey())
            .map(e -> Map.of("id", e.getKey(), "configured", e.getValue().configured())).toList());
        var p = agentProbe();
        var agentMap = new LinkedHashMap<String, Object>(); agentMap.put("reachable", p.reachable()); agentMap.put("latencyMs", p.latencyMs()); agentMap.put("checkedAtMs", p.checkedAtMs());
        providers.put("agentRuntime", agentMap);
        if (!p.reachable()) issues.add(new Issue("AGENT_RUNTIME_UNREACHABLE", "助手运行时连不上，聊天不可用"));

        out.put("queue", queue);
        out.put("providers", providers);
        out.put("sessions", Map.of("active", count("SELECT COUNT(*) FROM SPRING_SESSION"),
            "onlineLast5Min", count("SELECT COUNT(DISTINCT PRINCIPAL_NAME) FROM SPRING_SESSION WHERE PRINCIPAL_NAME IS NOT NULL AND LAST_ACCESS_TIME>=?", now - 300_000)));
        var result = new LinkedHashMap<String, Object>();
        result.put("status", issues.isEmpty() ? "UP" : "DEGRADED");
        result.put("issues", issues);
        result.put("app", app); result.put("jvm", jvm); result.put("storage", storage); result.put("models", models);
        result.putAll(out);
        result.put("generatedAtMs", now);
        return result;
    }

    /** Compact form for the dashboard. */
    public Map<String, Object> summary() {
        var s = status();
        @SuppressWarnings("unchecked") var queue = (Map<String, Object>) s.get("queue");
        @SuppressWarnings("unchecked") var byStatus = (Map<String, Long>) queue.get("byStatus");
        return Map.of("status", s.get("status"), "issues", ((List<?>) s.get("issues")).size(),
            "queueBacklog", byStatus.getOrDefault("QUEUED", 0L) + byStatus.getOrDefault("WAITING_QUOTA", 0L), "failed24h", queue.get("failed24h"));
    }

    private Probe agentProbe() {
        var cached = probe; long now = System.currentTimeMillis();
        if (cached != null && now - cached.checkedAtMs() < 15_000) return cached;
        var config = agent.getIfAvailable();
        Probe next;
        if (config == null) next = new Probe(false, null, now);
        else {
            long started = System.nanoTime();
            try {
                var response = http.send(HttpRequest.newBuilder(config.runtimeUrl().resolve("/health")).timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.discarding());
                next = new Probe(response.statusCode() / 100 == 2, (System.nanoTime() - started) / 1_000_000, now);
            } catch (IOException e) { next = new Probe(false, null, now); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); next = new Probe(false, null, now); }
        }
        probe = next;
        return next;
    }

    static Long rssBytes() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status")))
                if (line.startsWith("VmRSS:")) return Long.parseLong(line.replaceAll("[^0-9]", "")) * 1024;
        } catch (IOException | RuntimeException ignored) { }
        return null;
    }

    private Long databaseBytes() {
        if (!databaseUrl.startsWith("jdbc:h2:file:")) return null;
        String path = databaseUrl.substring("jdbc:h2:file:".length()).split(";", 2)[0];
        try { var file = Path.of(path + ".mv.db"); return Files.isRegularFile(file) ? Files.size(file) : null; }
        catch (IOException | RuntimeException e) { return null; }
    }

    public Map<String, Object> tasks(List<String> statuses, String kind, int page, int size) {
        var wanted = statuses == null || statuses.isEmpty() ? List.of("FAILED") : statuses.stream().map(s -> s.strip().toUpperCase(Locale.ROOT)).distinct().toList();
        if (!TASK_STATUSES.containsAll(wanted)) throw ManagementException.invalid("status 只能是 " + String.join("、", TASK_STATUSES) + "。");
        if (kind != null && !kind.matches("[A-Z_]{1,40}")) throw ManagementException.invalid("kind 格式无效。");
        var where = new StringBuilder(" WHERE status IN (" + String.join(",", Collections.nCopies(wanted.size(), "?")) + ")");
        var args = new ArrayList<Object>(wanted);
        if (kind != null) { where.append(" AND kind=?"); args.add(kind); }
        long total = count("SELECT COUNT(*) FROM execution_task" + where, args.toArray());
        var pageArgs = new ArrayList<>(args); pageArgs.add(size); pageArgs.add((long) (page - 1) * size);
        var items = jdbc.query("SELECT t.id,t.kind,t.owner_username,t.status,t.stage,t.pool,t.priority,t.wait_reason,t.error_message,t.created_at_ms,t.updated_at_ms,t.next_run_at_ms,"
            + "(SELECT COUNT(*) FROM execution_attempt a WHERE a.task_id=t.id) FROM execution_task t" + where.toString().replace(" status", " t.status").replace(" kind", " t.kind")
            + " ORDER BY t.updated_at_ms DESC, t.id LIMIT ? OFFSET ?", (r, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("id", r.getString(1)); m.put("kind", r.getString(2)); m.put("ownerUsername", r.getString(3)); m.put("status", r.getString(4));
                m.put("stage", r.getInt(5)); m.put("pool", r.getString(6)); m.put("priority", r.getInt(7)); m.put("waitReason", r.getString(8));
                String error = r.getString(9); m.put("errorMessage", error == null ? null : error.substring(0, Math.min(300, error.length())));
                m.put("attempts", r.getInt(13)); m.put("createdAtMs", r.getLong(10)); m.put("updatedAtMs", r.getLong(11)); m.put("nextRunAtMs", r.getLong(12));
                return m;
            }, pageArgs.toArray());
        return Map.of("items", items, "total", total, "page", page, "size", size);
    }

    public Map<String, Object> operationLog(LocalDate from, LocalDate to, String action, String target, int page, int size) {
        var range = ManagementInsightService.range(from, to);
        var where = new StringBuilder(" WHERE occurred_at_ms>=? AND occurred_at_ms<?");
        var args = new ArrayList<Object>(List.of(range.fromMs(), range.toMsExclusive()));
        if (action != null && !action.isBlank()) { where.append(" AND action=?"); args.add(action.strip()); }
        if (target != null && !target.isBlank()) { where.append(" AND target_username=?"); args.add(target.strip()); }
        long total = count("SELECT COUNT(*) FROM management_operation_log" + where, args.toArray());
        var pageArgs = new ArrayList<>(args); pageArgs.add(size); pageArgs.add((long) (page - 1) * size);
        var items = jdbc.query("SELECT id,occurred_at_ms,key_id,operator,request_id,action,target_username,result,detail FROM management_operation_log" + where
            + " ORDER BY occurred_at_ms DESC, id LIMIT ? OFFSET ?", (r, n) -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("id", r.getString(1)); m.put("occurredAtMs", r.getLong(2)); m.put("keyId", r.getString(3)); m.put("operator", r.getString(4));
                m.put("requestId", r.getString(5)); m.put("action", r.getString(6)); m.put("targetUsername", r.getString(7)); m.put("result", r.getString(8)); m.put("detail", r.getString(9));
                return m;
            }, pageArgs.toArray());
        return Map.of("items", items, "total", total, "page", page, "size", size);
    }

    private long count(String sql, Object... args) { Long v = jdbc.queryForObject(sql, Long.class, args); return v == null ? 0 : v; }
    private static double round(double v) { return Double.isFinite(v) ? Math.round(v * 1000) / 1000.0 : -1; }
}
