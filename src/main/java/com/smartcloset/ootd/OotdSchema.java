package com.smartcloset.ootd;

import com.smartcloset.agent.OutfitRules;
import com.smartcloset.image.ProcessingException;
import com.smartcloset.recognition.MetadataValues;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Whole-outfit sample structure (design 2026-10-09 §6.3, D47): items worn or carried plus presentation (pose, framing,
 * scene, hair, expression), extracted once. Never asks for faces, identity, age or body measurements.
 * {@link #normalize} is lenient on vocabulary (unknown enum values fall back to UNKNOWN/OTHER, long text is cut) and
 * strict on shape, so a model answer and an admin's manual edit go through the same gate.
 */
public final class OotdSchema {
    public static final String VERSION = "ootd-v1";
    static final List<String> REJECT = List.of("NONE", "MULTIPLE_PEOPLE", "NOT_FULL_OUTFIT", "COLLAGE", "MINOR_LIKELY", "NOT_PHOTO", "HEAVY_EDIT", "OTHER");
    /** Rejections that delete the photo at once (D47): never keep a photo of a likely minor, a crowd or a collage. */
    static final Set<String> AUTO_REJECT = Set.of("MINOR_LIKELY", "MULTIPLE_PEOPLE", "COLLAGE", "NOT_PHOTO");
    static final List<String> PRESENTATION = List.of("FEMININE", "MASCULINE", "NEUTRAL", "UNKNOWN");
    static final List<String> ROLES = Arrays.stream(OutfitRules.Role.values()).map(Enum::name).toList();
    static final List<String> WEAR = List.of("TUCKED", "HALF_TUCKED", "UNTUCKED", "OPEN", "CLOSED", "NA");
    static final List<String> VISIBILITY = List.of("FULL", "PARTIAL");
    static final List<String> CONFIDENCE = List.of("HIGH", "MEDIUM", "LOW");
    static final List<String> SEASONS = List.of("春", "夏", "秋", "冬");
    static final List<String> OCCASIONS = List.of("日常", "通勤", "约会", "运动", "户外", "正式", "度假", "居家", "校园", "派对");
    static final List<String> STANCE = List.of("STANDING", "WALKING", "SITTING", "SQUATTING", "LEANING", "OTHER", "UNKNOWN");
    static final List<String> BODY_ANGLE = List.of("FRONT", "THREE_QUARTER", "SIDE", "BACK", "UNKNOWN");
    static final List<String> GAZE = List.of("CAMERA", "AWAY", "DOWN", "UP", "HIDDEN", "UNKNOWN");
    static final List<String> SHOT = List.of("FULL_BODY", "THREE_QUARTER", "HALF_BODY", "CLOSE_UP", "UNKNOWN");
    static final List<String> CAMERA = List.of("EYE_LEVEL", "HIGH", "LOW", "UNKNOWN");
    static final List<String> SETTING = List.of("STREET", "INDOOR", "STUDIO", "NATURE", "CAFE", "CAMPUS", "MALL", "HOME", "BEACH", "OTHER", "UNKNOWN");
    static final List<String> HAIR_LENGTH = List.of("SHORT", "MEDIUM", "LONG", "TIED", "COVERED", "UNKNOWN");
    static final List<String> EXPRESSION = List.of("SMILE", "NEUTRAL", "COOL", "PLAYFUL", "HIDDEN", "UNKNOWN");
    static final int MAX_ITEMS = 20;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PROMPT = load();
    private OotdSchema() {}

    public static String prompt() { return PROMPT; }

    private static String load() {
        try (InputStream in = OotdSchema.class.getResourceAsStream("/ootd/" + VERSION + ".txt")) {
            return new String(Objects.requireNonNull(in, "ootd prompt").readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    // ---- JSON schema for structured output (every property required, no extras; "unknown" is a value, not null) ----

    private static Map<String, Object> object(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties, "required", new ArrayList<>(properties.keySet()), "additionalProperties", false);
    }
    private static Map<String, Object> en(List<String> values, String description) { return Map.of("type", "string", "enum", values, "description", description); }
    private static Map<String, Object> text(int max, String description) { return Map.of("type", "string", "maxLength", max, "description", description); }
    private static Map<String, Object> list(Map<String, Object> item, int max) { return Map.of("type", "array", "items", item, "maxItems", max); }

    public static JsonNode schema() {
        var item = new LinkedHashMap<String, Object>();
        item.put("role", en(ROLES, "穿戴位置。OVERALL=背带裙/背带裤/JSK（需内搭）；HEADWEAR=发箍发夹等头饰；HANDHELD=手里拿的非包物品（玩偶、伞、相机）；SOCKS=袜子或裤袜；认不出用 OTHER。"));
        item.put("category", text(30, "中文品类名，例如 衬衫、百褶裙、洛丽塔JSK、连裤袜、发箍、小熊玩偶；示例不是限制。"));
        item.put("primaryColor", text(12, "主色，例如 白色、藏蓝色、米色；看不清填空字符串。"));
        item.put("secondaryColors", list(text(12, "辅色"), 4));
        item.put("patterns", list(text(12, "图案，例如 纯色、格纹、条纹、碎花、波点"), 4));
        item.put("fit", text(12, "版型，例如 修身、合体、宽松、廓形；看不清填空字符串。"));
        item.put("length", text(12, "长度，例如 短款、常规、中长款、长款、及膝、过膝；不适用填空字符串。"));
        item.put("wear", en(WEAR, "上装是否塞进下装（TUCKED/HALF_TUCKED/UNTUCKED），外套是否敞开（OPEN/CLOSED），其他 NA。"));
        item.put("visibility", en(VISIBILITY, "FULL=完整可见；PARTIAL=被遮挡或被画面裁切。"));
        item.put("confidence", en(CONFIDENCE, "对这一项判断的把握。"));
        item.put("description", text(120, "一句话描述款式细节，不描述人。"));
        var pose = new LinkedHashMap<String, Object>();
        pose.put("stance", en(STANCE, "姿态")); pose.put("bodyAngle", en(BODY_ANGLE, "身体朝向镜头的角度")); pose.put("gaze", en(GAZE, "视线"));
        pose.put("hands", text(60, "手部动作，例如 一手插兜一手拎包。")); pose.put("description", text(120, "姿势的抽象描述，例如 重心在后脚、微侧身。不描述长相。"));
        pose.put("tendency", en(PRESENTATION, "这个姿势常见于哪种穿搭呈现（软标签）。"));
        var framing = new LinkedHashMap<String, Object>();
        framing.put("shot", en(SHOT, "景别")); framing.put("cameraAngle", en(CAMERA, "机位高低")); framing.put("mirrorSelfie", Map.of("type", "boolean", "description", "是否对镜自拍"));
        var scene = new LinkedHashMap<String, Object>();
        scene.put("setting", en(SETTING, "场景")); scene.put("description", text(80, "场景描述，不含能定位到具体地点或个人的信息。"));
        var hair = new LinkedHashMap<String, Object>();
        hair.put("length", en(HAIR_LENGTH, "发长")); hair.put("description", text(60, "发型，例如 低双马尾、短发微卷。"));
        var root = new LinkedHashMap<String, Object>();
        root.put("usable", Map.of("type", "boolean", "description", "是否是一个人、一整套日常穿搭的照片。"));
        root.put("rejectReason", en(REJECT, "不可用的原因；可用时为 NONE。人物明显未成年时必须为 MINOR_LIKELY。"));
        root.put("presentation", en(PRESENTATION, "整套穿搭的呈现风格（女性化/男性化/中性），不是对人的性别判断。"));
        root.put("seasons", list(en(SEASONS, "季节"), 4));
        root.put("occasions", list(en(OCCASIONS, "场合"), 4));
        root.put("styles", list(text(12, "风格，例如 休闲、通勤、甜美、复古、学院、洛丽塔、街头"), 6));
        root.put("items", list(object(item), MAX_ITEMS));
        root.put("pose", object(pose)); root.put("framing", object(framing)); root.put("scene", object(scene)); root.put("hair", object(hair));
        root.put("expression", en(EXPRESSION, "表情"));
        root.put("highlights", text(120, "这套搭配的亮点，一句话。"));
        return JSON.valueToTree(object(root));
    }

    // ---- normalisation ----

    /** Validates shape and cleans vocabulary; adds {@code engineRole} per item (what the recommender would read from the category). */
    public static ObjectNode normalize(JsonNode in) {
        if (in == null || !in.isObject()) throw invalid("结构化结果必须是 JSON 对象。");
        var out = JSON.createObjectNode();
        out.put("usable", in.path("usable").asBoolean(false));
        out.put("rejectReason", pick(in.path("rejectReason"), REJECT, "OTHER"));
        if (out.path("usable").asBoolean() && !"NONE".equals(out.path("rejectReason").asText())) out.put("usable", false);
        if (!out.path("usable").asBoolean() && "NONE".equals(out.path("rejectReason").asText())) out.put("rejectReason", "OTHER");
        out.put("presentation", pick(in.path("presentation"), PRESENTATION, "UNKNOWN"));
        out.set("seasons", enums(in.path("seasons"), SEASONS, 4));
        out.set("occasions", enums(in.path("occasions"), OCCASIONS, 4));
        out.set("styles", texts(in.path("styles"), 6, 12));
        var items = JSON.createArrayNode();
        var raw = in.path("items");
        if (!raw.isMissingNode() && !raw.isArray()) throw invalid("items 必须是数组。");
        if (raw.size() > MAX_ITEMS) throw invalid("物品最多 " + MAX_ITEMS + " 件。");
        for (var i : raw) {
            if (!i.isObject()) throw invalid("每件物品必须是对象。");
            var item = JSON.createObjectNode();
            String category = cut(i.path("category").asText(""), 30);
            String role = pick(i.path("role"), ROLES, "OTHER");
            item.put("role", role);
            item.put("category", category);
            String engine = OutfitRules.role(Map.of("category", category.isBlank() ? List.of() : List.of(category))).name();
            item.put("engineRole", engine);
            item.put("primaryColor", color(i.path("primaryColor").asText("")));
            var secondary = JSON.createArrayNode(); for (var c : texts(i.path("secondaryColors"), 4, 12)) if (!c.asText().isBlank()) secondary.add(color(c.asText()));
            item.set("secondaryColors", secondary);
            item.set("patterns", texts(i.path("patterns"), 4, 12));
            item.put("fit", cut(i.path("fit").asText(""), 12));
            item.put("length", cut(i.path("length").asText(""), 12));
            item.put("wear", pick(i.path("wear"), WEAR, "NA"));
            item.put("visibility", pick(i.path("visibility"), VISIBILITY, "PARTIAL"));
            item.put("confidence", pick(i.path("confidence"), CONFIDENCE, "LOW"));
            item.put("description", cut(i.path("description").asText(""), 120));
            if (category.isBlank() && "OTHER".equals(role) && item.path("description").asText().isBlank()) continue;
            items.add(item);
        }
        out.set("items", items);
        var pose = JSON.createObjectNode(); var p = in.path("pose");
        pose.put("stance", pick(p.path("stance"), STANCE, "UNKNOWN")); pose.put("bodyAngle", pick(p.path("bodyAngle"), BODY_ANGLE, "UNKNOWN")); pose.put("gaze", pick(p.path("gaze"), GAZE, "UNKNOWN"));
        pose.put("hands", cut(p.path("hands").asText(""), 60)); pose.put("description", cut(p.path("description").asText(""), 120)); pose.put("tendency", pick(p.path("tendency"), PRESENTATION, "UNKNOWN"));
        out.set("pose", pose);
        var framing = JSON.createObjectNode(); var f = in.path("framing");
        framing.put("shot", pick(f.path("shot"), SHOT, "UNKNOWN")); framing.put("cameraAngle", pick(f.path("cameraAngle"), CAMERA, "UNKNOWN")); framing.put("mirrorSelfie", f.path("mirrorSelfie").asBoolean(false));
        out.set("framing", framing);
        var scene = JSON.createObjectNode(); var s = in.path("scene");
        scene.put("setting", pick(s.path("setting"), SETTING, "UNKNOWN")); scene.put("description", cut(s.path("description").asText(""), 80));
        out.set("scene", scene);
        var hair = JSON.createObjectNode(); var h = in.path("hair");
        hair.put("length", pick(h.path("length"), HAIR_LENGTH, "UNKNOWN")); hair.put("description", cut(h.path("description").asText(""), 60));
        out.set("hair", hair);
        out.put("expression", pick(in.path("expression"), EXPRESSION, "UNKNOWN"));
        out.put("highlights", cut(in.path("highlights").asText(""), 120));
        return out;
    }

    private static ProcessingException invalid(String message) { return ProcessingException.invalid(message); }
    private static String pick(JsonNode n, List<String> allowed, String fallback) {
        String v = n.isString() ? n.asText().strip().toUpperCase(Locale.ROOT) : "";
        if (allowed.contains(v)) return v;
        String raw = n.isString() ? n.asText().strip() : "";
        return allowed.contains(raw) ? raw : fallback;
    }
    private static ArrayNode enums(JsonNode n, List<String> allowed, int max) {
        var out = JSON.createArrayNode(); var seen = new HashSet<String>();
        if (n.isArray()) for (var v : n) { String s = v.asText("").strip(); if (allowed.contains(s) && seen.add(s) && out.size() < max) out.add(s); }
        return out;
    }
    private static ArrayNode texts(JsonNode n, int max, int length) {
        var out = JSON.createArrayNode(); var seen = new HashSet<String>();
        if (n.isArray()) for (var v : n) { String s = cut(v.asText(""), length); if (!s.isBlank() && seen.add(s) && out.size() < max) out.add(s); }
        return out;
    }
    private static String cut(String s, int max) { s = s == null ? "" : s.strip(); return s.length() > max ? s.substring(0, max) : s; }
    private static String color(String s) { s = cut(s, 12); return s.isBlank() ? "" : MetadataValues.canonical("primaryColor", s); }
}
