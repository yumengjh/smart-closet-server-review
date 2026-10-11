package com.smartcloset.ootd;

import com.smartcloset.agent.OutfitRules.Role;
import com.smartcloset.image.Images;
import com.smartcloset.image.ProcessingException;
import com.smartcloset.recommend.FeatureTables;
import com.smartcloset.recommend.TemplateCatalog;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Whole-outfit samples (design 2026-10-09 §6, D47~D49): admin uploads a photo with its source and licence, one model
 * call extracts ootd-v1, an admin reviews it, and the photo is deleted once the review is done. Approved samples feed
 * statistics and can become template drafts that go through the normal template review.
 */
@Service
public class OotdSampleService {
    public static final List<String> LICENSES = List.of("OWN", "PERMITTED", "OPEN", "RESEARCH", "UNKNOWN");
    public static final long MAX_BYTES = 10L * 1024 * 1024;
    static final int MAX_SIDE = 1600;
    public record Sample(String id, String status, String sourceUrl, String license, String note, boolean hasImage, String extractorVersion, String model,
                         JsonNode extraction, JsonNode reviewed, String error, String rejectReason, String presentation, String createdBy, long createdAtMs,
                         long updatedAtMs, String reviewedBy, Long reviewedAtMs, String templateId) {
        /** What review and statistics read: the admin's edit when there is one, otherwise the model's extraction. */
        public JsonNode current() { return reviewed != null ? reviewed : extraction; }
    }

    private static final Logger log = LoggerFactory.getLogger(OotdSampleService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate db;
    private final OotdExtractor extractor;
    private final TemplateCatalog templates;
    private final com.smartcloset.storage.BlobStore store;

    public OotdSampleService(JdbcTemplate db, OotdExtractor extractor, TemplateCatalog templates, com.smartcloset.storage.BlobStore store) {
        this.db = db; this.extractor = extractor; this.templates = templates; this.store = store;
    }

    // ---- upload and extraction ----

    public Sample upload(String admin, byte[] bytes, String sourceUrl, String license, String note) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw ProcessingException.invalid("图片不能为空，且不能超过 10 MB。");
        String url = sourceUrl == null ? "" : sourceUrl.strip();
        if (url.length() > 1000 || !url.isEmpty() && !url.matches("(?i)https?://\\S+")) throw ProcessingException.invalid("来源链接须为 http(s) 地址，最长 1000 字。");
        String lic = license == null || license.isBlank() ? "UNKNOWN" : license.strip().toUpperCase(Locale.ROOT);
        if (!LICENSES.contains(lic)) throw ProcessingException.invalid("许可只能是 " + String.join("、", LICENSES) + "。");
        String remark = note == null ? "" : note.strip();
        if (remark.length() > 500) throw ProcessingException.invalid("备注最多 500 字。");
        String sha = sha256(bytes);
        var existing = db.queryForList("SELECT id FROM ootd_sample WHERE image_sha256=?", String.class, sha);
        if (!existing.isEmpty()) throw new ProcessingException(HttpStatus.CONFLICT, "OOTD_SAMPLE_DUPLICATE", "这张图已经收过了（样本 " + existing.getFirst().substring(0, 8) + "）。");
        // JPEG/PNG only, orientation applied and long side capped; only this re-encoded copy is stored, never the original file.
        byte[] png = png(Images.sampled(bytes, MAX_SIDE).image());
        String id = UUID.randomUUID().toString(); long now = System.currentTimeMillis();
        try { store.put("ootd-samples/" + id + ".png", png, "image/png"); }
        catch (IOException e) { throw new ProcessingException(HttpStatus.INTERNAL_SERVER_ERROR, "OOTD_SAMPLE_STORAGE", "样本图片无法保存。"); }
        db.update("INSERT INTO ootd_sample(id,status,source_url,license,note,image_sha256,image_path,created_by,created_at_ms,updated_at_ms) VALUES(?,'PENDING',?,?,?,?,?,?,?,?)",
            id, url.isEmpty() ? null : url, lic, remark.isEmpty() ? null : remark, sha, id + ".png", admin, now, now);
        return extract(id, admin);
    }

    /** Runs (or re-runs after a failure) the single model call for a sample whose photo is still stored. */
    public Sample extract(String id, String admin) {
        var sample = get(id);
        if (!Set.of("PENDING", "FAILED").contains(sample.status())) throw ProcessingException.invalid("只有待提取或提取失败的样本可以重新提取。");
        byte[] png = image(id);
        OotdExtractor.Extraction out;
        try { out = extractor.extract(png); }
        catch (ProcessingException e) {
            db.update("UPDATE ootd_sample SET status='FAILED',error_message=?,updated_at_ms=? WHERE id=?", cut(e.getMessage(), 500), System.currentTimeMillis(), id);
            return get(id);
        }
        recordUsage(admin, out);
        ObjectNode result;
        try { result = OotdSchema.normalize(out.raw()); }
        catch (ProcessingException e) {
            db.update("UPDATE ootd_sample SET status='FAILED',error_message=?,model=?,updated_at_ms=? WHERE id=?", "模型返回的结构不完整：" + cut(e.getMessage(), 400), out.model(), System.currentTimeMillis(), id);
            return get(id);
        }
        String reason = result.path("rejectReason").asText();
        long now = System.currentTimeMillis();
        if (OotdSchema.AUTO_REJECT.contains(reason)) {
            // Likely minors, crowds, collages and non-photos: the photo goes now and nothing about the person is kept.
            deleteImage(id);
            db.update("UPDATE ootd_sample SET status='REJECTED',reject_reason=?,extractor_version=?,model=?,extraction_json=NULL,error_message=NULL,image_path=NULL,reviewed_by='auto',reviewed_at_ms=?,updated_at_ms=? WHERE id=?",
                reason, out.promptVersion(), out.model(), now, now, id);
            return get(id);
        }
        db.update("UPDATE ootd_sample SET status='EXTRACTED',extraction_json=?,reject_reason=?,presentation=?,extractor_version=?,model=?,error_message=NULL,updated_at_ms=? WHERE id=?",
            JSON.writeValueAsString(result), "NONE".equals(reason) ? null : reason, result.path("presentation").asText(), out.promptVersion(), out.model(), now, id);
        return get(id);
    }

    private void recordUsage(String admin, OotdExtractor.Extraction out) {
        var u = out.usage();
        Long input = number(u, "prompt_tokens", "promptTokenCount", "input_tokens"), output = number(u, "completion_tokens", "candidatesTokenCount", "output_tokens");
        try { db.update("INSERT INTO model_usage(id,owner_username,feature,model,input_tokens,output_tokens,created_at_ms) VALUES(?,?,'OOTD_EXTRACT',?,?,?,?)",
                UUID.randomUUID().toString(), admin, out.model(), input, output, System.currentTimeMillis()); }
        catch (RuntimeException e) { log.warn("ootd usage not recorded: {}", e.getClass().getSimpleName()); }
    }
    private static Long number(JsonNode usage, String... keys) {
        if (usage == null) return null;
        for (String k : keys) if (usage.path(k).isNumber()) return usage.path(k).asLong();
        return null;
    }

    // ---- review ----

    public Sample update(String id, JsonNode edited) {
        var sample = get(id);
        if (!"EXTRACTED".equals(sample.status())) throw ProcessingException.invalid("只有待审核的样本可以修改。");
        var result = OotdSchema.normalize(edited);
        db.update("UPDATE ootd_sample SET reviewed_json=?,presentation=?,reject_reason=?,updated_at_ms=? WHERE id=?", JSON.writeValueAsString(result),
            result.path("presentation").asText(), "NONE".equals(result.path("rejectReason").asText()) ? null : result.path("rejectReason").asText(), System.currentTimeMillis(), id);
        return get(id);
    }

    /** APPROVED or REJECTED; either way the photo is deleted now (D47). */
    public Sample review(String id, String decision, String admin) {
        var sample = get(id);
        if (!Set.of("EXTRACTED", "FAILED").contains(sample.status())) throw ProcessingException.invalid("这个样本已经审核过了。");
        String d = decision == null ? "" : decision.strip().toUpperCase(Locale.ROOT);
        if (!Set.of("APPROVED", "REJECTED").contains(d)) throw ProcessingException.invalid("审核结果只能是 APPROVED 或 REJECTED。");
        if ("APPROVED".equals(d)) {
            if ("FAILED".equals(sample.status())) throw ProcessingException.invalid("提取失败的样本不能通过，请重新提取或拒绝。");
            var current = sample.current();
            if (!current.path("usable").asBoolean()) throw ProcessingException.invalid("这个样本标为不可用（" + current.path("rejectReason").asText() + "），请在结果里改为可用后再通过，或直接拒绝。");
            if (current.path("items").isEmpty()) throw ProcessingException.invalid("物品清单为空，不能通过。");
        }
        deleteImage(id);
        long now = System.currentTimeMillis();
        db.update("UPDATE ootd_sample SET status=?,reviewed_json=COALESCE(reviewed_json,extraction_json),image_path=NULL,reviewed_by=?,reviewed_at_ms=?,updated_at_ms=? WHERE id=?", d, admin, now, now, id);
        return get(id);
    }

    public void delete(String id) {
        get(id);
        deleteImage(id);
        db.update("DELETE FROM ootd_sample WHERE id=?", id);
    }

    // ---- reads ----

    public List<Sample> list(String status, int limit) {
        int n = Math.max(1, Math.min(500, limit));
        return status == null || status.isBlank()
            ? db.query("SELECT * FROM ootd_sample ORDER BY created_at_ms DESC LIMIT ?", this::row, n)
            : db.query("SELECT * FROM ootd_sample WHERE status=? ORDER BY created_at_ms DESC LIMIT ?", this::row, status.strip().toUpperCase(Locale.ROOT), n);
    }

    public Sample get(String id) {
        return db.query("SELECT * FROM ootd_sample WHERE id=?", this::row, id).stream().findFirst()
            .orElseThrow(() -> new ProcessingException(HttpStatus.NOT_FOUND, "OOTD_SAMPLE_NOT_FOUND", "没有找到这个样本。"));
    }

    public byte[] image(String id) {
        var name = db.queryForList("SELECT image_path FROM ootd_sample WHERE id=?", String.class, id);
        if (name.isEmpty() || name.getFirst() == null) throw new ProcessingException(HttpStatus.NOT_FOUND, "OOTD_SAMPLE_IMAGE_GONE", "原图已在审核后删除。");
        try { byte[] bytes = store.get("ootd-samples/" + name.getFirst()); if (bytes == null) throw new IOException("missing"); return bytes; }
        catch (IOException | IllegalArgumentException e) { throw new ProcessingException(HttpStatus.NOT_FOUND, "OOTD_SAMPLE_IMAGE_GONE", "原图文件不存在。"); }
    }

    private Sample row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        Object reviewedAt = rs.getObject("reviewed_at_ms");
        return new Sample(rs.getString("id"), rs.getString("status"), rs.getString("source_url"), rs.getString("license"), rs.getString("note"), rs.getString("image_path") != null,
            rs.getString("extractor_version"), rs.getString("model"), tree(rs.getString("extraction_json")), tree(rs.getString("reviewed_json")), rs.getString("error_message"),
            rs.getString("reject_reason"), rs.getString("presentation"), rs.getString("created_by"), rs.getLong("created_at_ms"), rs.getLong("updated_at_ms"),
            rs.getString("reviewed_by"), reviewedAt == null ? null : ((Number) reviewedAt).longValue(), rs.getString("template_id"));
    }
    private static JsonNode tree(String raw) { return raw == null ? null : JSON.readTree(raw); }

    // ---- template drafts (D49) ----

    static final Set<Role> SLOT_ROLES = EnumSet.of(Role.TOP, Role.BOTTOM, Role.ONE_PIECE, Role.OVERALL, Role.SHOES, Role.HEADWEAR, Role.SOCKS, Role.HAT, Role.BAG);
    static final Set<Role> BODY = EnumSet.of(Role.TOP, Role.BOTTOM, Role.ONE_PIECE, Role.OVERALL);
    /** Core nouns, most specific first: a slot matches the user's garments by this keyword, not by the sample's full wording. */
    static final List<String> CORE = List.of("JSK", "背带裙", "背带裤", "OP", "SK", "套装", "小黑裙", "连衣裙", "百褶", "半身裙", "牛仔裤", "西裤", "阔腿裤", "运动裤", "短裤", "长裤",
        "T恤", "衬衫", "卫衣", "毛衣", "针织", "背心", "Polo", "运动鞋", "帆布鞋", "乐福", "玛丽珍", "高跟", "凉鞋", "靴", "皮鞋", "连裤袜", "裤袜", "过膝袜", "短袜", "袜",
        "发箍", "发夹", "发带", "贝雷帽", "棒球帽", "渔夫帽", "帽", "托特包", "斜挎包", "双肩包", "包");
    static final Map<String, String> OCCASION = Map.of("日常", "DAILY", "通勤", "COMMUTE", "约会", "DATE", "运动", "SPORT", "户外", "OUTDOOR", "正式", "FORMAL", "度假", "TRAVEL", "居家", "HOME");

    public Map<String, Object> templateDraft(String id, String kind, String text, String admin) {
        var sample = get(id);
        if (!"APPROVED".equals(sample.status())) throw ProcessingException.invalid("只有已通过的样本可以生成模板草稿。");
        if ("UNKNOWN".equals(sample.license())) throw ProcessingException.invalid("许可未知的样本只参与统计，不能生成模板（D49）。");
        if (sample.templateId() != null) throw new ProcessingException(HttpStatus.CONFLICT, "OOTD_TEMPLATE_EXISTS", "这个样本已经生成过模板草稿：" + sample.templateId());
        String k = kind == null || kind.isBlank() ? "CLASSIC" : kind.strip().toUpperCase(Locale.ROOT);
        if (!Set.of("CLASSIC", "TREND").contains(k)) throw ProcessingException.invalid("类型只能是 CLASSIC 或 TREND。");
        var definition = draft(sample, k, text);
        String templateId = definition.path("id").asText();
        db.update("INSERT INTO outfit_template_custom(template_id,definition_json,source_sample_id,created_by,created_at_ms) VALUES(?,?,?,?,?)",
            templateId, JSON.writeValueAsString(definition), id, admin, System.currentTimeMillis());
        db.update("UPDATE ootd_sample SET template_id=?,updated_at_ms=? WHERE id=?", templateId, System.currentTimeMillis(), id);
        templates.reload();
        return Map.of("templateId", templateId, "definition", definition);
    }

    static ObjectNode draft(Sample sample, String kind, String text) {
        var current = sample.current();
        var slots = JSON.createArrayNode(); var names = new ArrayList<String>(); var used = EnumSet.noneOf(Role.class);
        boolean body = false;
        for (var item : current.path("items")) {
            Role role;
            try { role = Role.valueOf(item.path("role").asText()); } catch (IllegalArgumentException e) { continue; }
            if (!SLOT_ROLES.contains(role) || !used.add(role)) continue;
            String category = item.path("category").asText("");
            var family = FeatureTables.family(item.path("primaryColor").asText(""));
            boolean accessory = !BODY.contains(role) && role != Role.SHOES;
            if (accessory && family == FeatureTables.Family.UNKNOWN) continue;   // an accessory slot without a colour says too little
            var slot = JSON.createObjectNode();
            slot.set("roles", JSON.createArrayNode().add(role.name()));
            CORE.stream().filter(c -> category.toUpperCase(Locale.ROOT).contains(c.toUpperCase(Locale.ROOT))).findFirst()
                .ifPresent(c -> slot.set("categoryAny", JSON.createArrayNode().add(c)));
            if (family != FeatureTables.Family.UNKNOWN && family != FeatureTables.Family.MULTI) {
                var colors = JSON.createArrayNode().add(family.name());
                if (family == FeatureTables.Family.DENIM) colors.add("BLUE"); else if (family == FeatureTables.Family.BLUE) colors.add("DENIM");
                slot.set("colorAny", colors);
            }
            var patterns = new ArrayList<String>(); item.path("patterns").forEach(p -> patterns.add(p.asText()));
            if (patterns.contains("纯色")) slot.put("patternMax", 0);
            else if (!patterns.isEmpty()) slot.put("patternMin", 1);
            slots.add(slot);
            body |= BODY.contains(role);
            names.add(item.path("primaryColor").asText("") + category);
        }
        if (!body || slots.size() < 2) throw ProcessingException.invalid("样本里至少要有一件主体衣物和另一件可用物品（外套与内搭不进模板槽位），才能生成模板。");
        var d = JSON.createObjectNode();
        d.put("id", "sample-" + sample.id().substring(0, 8));
        String label = text != null && !text.isBlank() ? text.strip() : !current.path("highlights").asText("").isBlank() ? current.path("highlights").asText() : String.join("+", names);
        d.put("text", label.length() > 60 ? label.substring(0, 60) : label);
        d.put("bonus", 3); d.put("enabled", true); d.put("reviewStatus", "PENDING_REVIEW"); d.put("kind", kind);
        if ("TREND".equals(kind)) { var today = LocalDate.now(); d.put("validFrom", today.toString()); d.put("validUntil", today.plusMonths(6).toString()); }
        d.put("reference", "来自整套穿搭样本 " + sample.id().substring(0, 8) + (sample.sourceUrl() == null ? "" : "（" + sample.sourceUrl() + "）"));
        d.set("slots", slots);
        String presentation = current.path("presentation").asText("");
        if ("FEMININE".equals(presentation)) d.set("audience", JSON.createArrayNode().add("FEMALE"));
        if ("MASCULINE".equals(presentation)) d.set("audience", JSON.createArrayNode().add("MALE"));
        if (!current.path("seasons").isEmpty()) d.set("seasons", current.path("seasons").deepCopy());
        var occasions = JSON.createArrayNode();
        current.path("occasions").forEach(o -> { var v = OCCASION.get(o.asText()); if (v != null) occasions.add(v); });
        if (!occasions.isEmpty()) d.set("occasions", occasions);
        return d;
    }

    // ---- statistics (approved samples only) ----

    public Map<String, Object> stats(String presentation) {
        var approved = list("APPROVED", 500).stream().map(Sample::current)
            .filter(c -> presentation == null || presentation.isBlank() || presentation.equalsIgnoreCase(c.path("presentation").asText())).toList();
        var out = new LinkedHashMap<String, Object>();
        int n = approved.size();
        out.put("approved", n);
        out.put("byStatus", db.queryForList("SELECT status, COUNT(*) AS n FROM ootd_sample GROUP BY status").stream()
            .collect(Collectors.toMap(r -> String.valueOf(r.get("status")), r -> ((Number) r.get("n")).intValue(), (a, b) -> a, TreeMap::new)));
        out.put("presentation", count(approved, c -> List.of(c.path("presentation").asText())));
        // Share of samples that contain each position at least once (accessories included).
        var roles = new TreeMap<String, Integer>();
        for (var c : approved) { var seen = new HashSet<String>(); c.path("items").forEach(i -> seen.add(i.path("role").asText())); seen.forEach(r -> roles.merge(r, 1, Integer::sum)); }
        out.put("roles", roles);
        out.put("bodies", top(count(approved, OotdSampleService::bodyKey), 10));
        out.put("colorPairs", top(count(approved, OotdSampleService::colorKey), 10));
        out.put("styles", top(count(approved, c -> values(c.path("styles"))), 12));
        out.put("seasons", count(approved, c -> values(c.path("seasons"))));
        out.put("occasions", count(approved, c -> values(c.path("occasions"))));
        out.put("stance", count(approved, c -> List.of(c.path("pose").path("stance").asText())));
        out.put("bodyAngle", count(approved, c -> List.of(c.path("pose").path("bodyAngle").asText())));
        out.put("gaze", count(approved, c -> List.of(c.path("pose").path("gaze").asText())));
        out.put("shot", count(approved, c -> List.of(c.path("framing").path("shot").asText())));
        out.put("setting", count(approved, c -> List.of(c.path("scene").path("setting").asText())));
        out.put("hair", count(approved, c -> List.of(c.path("hair").path("length").asText())));
        out.put("expression", count(approved, c -> List.of(c.path("expression").asText())));
        // Vocabulary candidates: things the recommender cannot place from their category name (design §6.1 ③).
        var unknown = new LinkedHashMap<String, Map<String, Object>>();
        int mismatches = 0;
        for (var c : approved) for (var i : c.path("items")) {
            String engine = i.path("engineRole").asText(), role = i.path("role").asText();
            if ("OTHER".equals(engine)) {
                String key = i.path("category").asText("").isBlank() ? i.path("description").asText("") : i.path("category").asText();
                var e = unknown.computeIfAbsent(key, x -> new LinkedHashMap<>(Map.of("category", x, "role", role, "description", i.path("description").asText(""), "count", 0)));
                e.put("count", (Integer) e.get("count") + 1);
            } else if (!engine.equals(role) && !("INNER".equals(role) && "TOP".equals(engine))) mismatches++;
        }
        out.put("unknownItems", unknown.values().stream().sorted(Comparator.comparingInt((Map<String, Object> m) -> -(Integer) m.get("count"))).limit(20).toList());
        out.put("roleMismatches", mismatches);
        return out;
    }

    private static List<String> values(JsonNode array) { var out = new ArrayList<String>(); array.forEach(v -> out.add(v.asText())); return out; }
    private static List<String> bodyKey(JsonNode c) {
        var parts = new ArrayList<String>();
        for (String r : List.of("TOP", "OVERALL", "BOTTOM", "ONE_PIECE"))
            for (var i : c.path("items")) if (r.equals(i.path("role").asText())) { parts.add(core(i.path("category").asText(""))); break; }
        return parts.isEmpty() ? List.of() : List.of(String.join(" + ", parts));
    }
    private static List<String> colorKey(JsonNode c) {
        String upper = null, lower = null;
        for (var i : c.path("items")) {
            String r = i.path("role").asText(); var f = FeatureTables.family(i.path("primaryColor").asText(""));
            if (f == FeatureTables.Family.UNKNOWN) continue;
            if (upper == null && Set.of("TOP", "ONE_PIECE").contains(r)) upper = FeatureTables.label(f);
            if (lower == null && Set.of("BOTTOM", "OVERALL").contains(r)) lower = FeatureTables.label(f);
        }
        return upper == null ? List.of() : List.of(lower == null ? upper + "（连衣）" : upper + " + " + lower);
    }
    private static String core(String category) {
        return CORE.stream().filter(c -> category.toUpperCase(Locale.ROOT).contains(c.toUpperCase(Locale.ROOT))).findFirst().orElse(category.isBlank() ? "？" : category);
    }
    private static Map<String, Integer> count(List<JsonNode> samples, java.util.function.Function<JsonNode, List<String>> keys) {
        var out = new TreeMap<String, Integer>();
        for (var c : samples) for (var k : new LinkedHashSet<>(keys.apply(c))) if (!k.isBlank()) out.merge(k, 1, Integer::sum);
        return out;
    }
    private static List<Map<String, Object>> top(Map<String, Integer> counts, int limit) {
        return counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .limit(limit).map(e -> Map.<String, Object>of("key", e.getKey(), "count", e.getValue())).toList();
    }

    // ---- files ----

    private void deleteImage(String id) {
        try { store.delete("ootd-samples/" + id + ".png"); }
        catch (IOException e) { log.warn("ootd sample image not deleted: {}", id); }
    }
    private static byte[] png(java.awt.image.BufferedImage image) {
        try { var out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out); return out.toByteArray(); }
        catch (IOException e) { throw ProcessingException.invalid("图片无法转换。"); }
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String cut(String s, int max) { s = s == null ? "" : s; return s.length() > max ? s.substring(0, max) : s; }
}
