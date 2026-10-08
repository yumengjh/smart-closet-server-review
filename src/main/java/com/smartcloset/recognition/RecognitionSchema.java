package com.smartcloset.recognition;

import com.smartcloset.image.ProcessingException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;
import static com.smartcloset.recognition.Recognition.*;

@Component
public class RecognitionSchema {
    public static final String VERSION = "garment-v6";
    public static boolean requiresTarget(String version) { return Set.of("garment-v3", "garment-v4", "garment-v5", VERSION).contains(Objects.toString(version, "")); }
    /** Drawing style of the recognised image itself (D29): read in the same call, so no extra quota. */
    public static final List<String> IMAGE_STYLES = List.of("PHOTO", "ILLUSTRATION", "SKETCH", "OTHER");
    public record Field(String key, String label, List<String> options, boolean multiple, boolean suggestion) {}
    public static final List<Field> FIELDS = List.of(
        field("category", "品类", false, false, "T恤,衬衫,卫衣,毛衣,背心,夹克,西装外套,大衣,羽绒服,连衣裙,半身裙,长裤,短裤,连体衣,运动鞋,靴子,凉鞋,帽子,棒球帽,贝雷帽,渔夫帽,手提包,斜挎包,背包,围巾,腰带,袜子,眼镜,上装,下装,其他衣物"),
        field("primaryColor", "主色", false, false, "黑色,白色,灰色,米色,棕色,红色,橙色,黄色,绿色,蓝色,藏蓝色,紫色,粉色,多色"),
        field("secondaryColors", "辅色", true, false, "黑色,白色,灰色,米色,棕色,红色,橙色,黄色,绿色,蓝色,藏蓝色,紫色,粉色"),
        field("patterns", "图案", true, false, "纯色,条纹,格纹,波点,花卉,图形印花,文字,标志,迷彩,动物纹,拼色,其他"),
        field("neckline", "领型", false, false, "圆领,V领,翻领,立领,高领,方领,一字领,连帽,其他"),
        field("sleeveLength", "袖长", false, false, "无袖,短袖,五分袖,七分袖,长袖"),
        field("garmentLength", "衣物长度", false, false, "短款,常规,中长款,长款"),
        field("closure", "门襟", true, false, "套头,纽扣,拉链,系带,按扣,其他"),
        field("texture", "表面纹理", true, false, "平滑,针织纹理,牛仔纹理,绒面感,皮革感,光泽,蕾丝,网眼,其他"),
        field("fit", "版型建议（需核对）", false, true, "修身,合体,宽松,廓形"),
        field("thickness", "厚薄线索（需核对）", false, true, "薄,常规,厚"),
        field("wearingModes", "穿着用途建议（需核对）", true, true, "可作内搭,可作外搭,适合单穿"),
        field("styles", "风格建议", true, true, "休闲,运动,通勤,正式,复古,甜美,户外"),
        field("seasons", "季节建议", true, true, "春,夏,秋,冬"),
        field("brand", "品牌（需标签）", false, false, ""),
        field("size", "尺码（需标签）", false, false, ""),
        field("materialComposition", "成分（需标签）", false, false, ""),
        field("careInstructions", "洗护（需标签）", false, false, ""),
        field("visibleText", "可见文字", true, false, "")
    );
    private final ObjectMapper json;
    public RecognitionSchema(ObjectMapper json) { this.json = json; }
    private static Field field(String key, String label, boolean multiple, boolean suggestion, String options) {
        return new Field(key, label, options.isEmpty() ? List.of() : List.of(options.split(",")), multiple, suggestion);
    }
    public static Field field(String key) { return FIELDS.stream().filter(f -> f.key().equals(key)).findFirst().orElseThrow(() -> ProcessingException.invalid("未知元信息字段：" + key)); }
    private Map<String, Object> object(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties, "required", new ArrayList<>(properties.keySet()), "additionalProperties", false);
    }
    public JsonNode schema() {
        var attrs = new LinkedHashMap<String, Object>();
        for (var f : FIELDS) {
            Map<String, Object> item = Map.of("type", "string", "description", f.options().isEmpty()
                ? "依据照片填写，无法确定时留空。"
                : "常见示例：" + String.join("、", f.options()) + "。示例不是限制，可以填写更准确的新名称。");
            attrs.put(f.key(), object(Map.of("values", Map.of("type", "array", "items", item, "maxItems", f.multiple() ? 12 : 1),
                "status", Map.of("type", "string", "enum", Arrays.stream(State.values()).filter(state -> state != (f.suggestion() ? State.OBSERVED : State.INFERRED)).map(Enum::name).toList()), "evidence", Map.of("type", "string", "minLength", 1, "maxLength", 600))));
        }
        var coordinate = Map.of("type", "number", "minimum", 0, "maximum", 1);
        var target = object(Map.of("description", Map.of("type", "string"), "worn", Map.of("type", "boolean"),
            "bounds", object(Map.of("x", coordinate, "y", coordinate, "width", coordinate, "height", coordinate))));
        return json.valueToTree(object(Map.of("assessment", Map.of("type", "string", "enum", Arrays.stream(Assessment.values()).map(Enum::name).toList()),
            "imageStyle", Map.of("type", "string", "enum", IMAGE_STYLES, "description", "这张图片本身的画面风格：PHOTO 实物照片/抠图/逼真渲染，ILLUSTRATION 卡通插画，SKETCH 简笔线稿，OTHER 无法判断。"),
            "target", Map.of("anyOf", List.of(target, Map.of("type", "null"))),
            "attributes", object(attrs), "issues", Map.of("type", "array", "items", object(Map.of(
                "code", Map.of("type", "string"), "message", Map.of("type", "string"),
                "affectedFields", Map.of("type", "array", "items", Map.of("type", "string", "enum", FIELDS.stream().map(Field::key).toList())),
                "suggestedAction", Map.of("type", "string")))))));
    }
    public Result validate(JsonNode node) {
        return validate(node, false);
    }
    public Result validate(JsonNode node, boolean requireTarget) {
        String section="识别结果结构";
        try {
            var expected = new HashSet<>(requireTarget || node.has("target") ? Set.of("assessment", "attributes", "issues", "target") : Set.of("assessment", "attributes", "issues"));
            // Outputs before garment-v6 have no imageStyle; it stays optional so stored and replayed results still validate.
            if (node.has("imageStyle")) expected.add("imageStyle");
            keys(node, expected);
            var assessment = Assessment.valueOf(text(node.get("assessment"), 40));
            String imageStyle = null;
            if (node.has("imageStyle")) { imageStyle = text(node.get("imageStyle"), 20); if (!IMAGE_STYLES.contains(imageStyle)) imageStyle = "OTHER"; }
            var attrs = node.get("attributes"); keys(attrs, new HashSet<>(FIELDS.stream().map(Field::key).toList()));
            var result = new LinkedHashMap<String, Attribute>();
            for (var f : FIELDS) {
                section=f.label();
                try {
                    var a = attrs.get(f.key()); keys(a, Set.of("values", "status", "evidence"));
                    var state = State.valueOf(text(a.get("status"), 30));
                    var values = a.get("values");
                    if (!values.isArray() || values.size() > (f.multiple() ? 12 : 1)) throw new IllegalArgumentException();
                    var list = new ArrayList<String>();
                    for (var value : values) list.add(text(value, 400));
                    list = new ArrayList<>(normalizeValues(f, list));
                    boolean absent = Set.of(State.UNKNOWN, State.NOT_APPLICABLE, State.CONFLICT).contains(state);
                    if (absent != list.isEmpty()) throw new IllegalArgumentException();
                    String evidence = text(a.get("evidence"), 600);
                    if (evidence.isBlank()) throw new IllegalArgumentException();
                    if (f.suggestion() && !absent && state != State.INFERRED) throw new IllegalArgumentException();
                    if (!f.suggestion() && state == State.INFERRED) throw new IllegalArgumentException();
                    result.put(f.key(), new Attribute(List.copyOf(list), state, evidence));
                } catch (IllegalArgumentException | NullPointerException | ProcessingException invalid) {
                    if (!Set.of("fit", "thickness", "wearingModes").contains(f.key())) throw invalid;
                    // Optional visual suggestions cannot invalidate otherwise usable recognition.
                    // Do not repair an unsupported inference into a claimed observed fact.
                    result.put(f.key(), new Attribute(List.of(), State.UNKNOWN, "建议格式或依据不可靠，已留空，请按实际衣物核对。"));
                }
            }
            section="识别问题列表";
            var issues = node.get("issues"); if (!issues.isArray() || issues.size() > 12) throw new IllegalArgumentException();
            var warnings = new ArrayList<Issue>();
            for (var issue : issues) {
                keys(issue, Set.of("code", "message", "affectedFields", "suggestedAction"));
                var fields = issue.get("affectedFields"); if (!fields.isArray() || fields.size() > FIELDS.size()) throw new IllegalArgumentException();
                var affected = new ArrayList<String>();
                for (var f : fields) { String key = text(f, 50); field(key); affected.add(key); }
                warnings.add(new Issue(text(issue.get("code"), 80), text(issue.get("message"), 600), List.copyOf(affected), text(issue.get("suggestedAction"), 600)));
            }
            section="衣物定位框";
            Target target = null;
            var t = node.get("target");
            if (t != null && !t.isNull()) {
                keys(t, Set.of("description", "worn", "bounds"));
                String description = text(t.get("description"), 160);
                if (description.isBlank() || !t.get("worn").isBoolean()) throw new IllegalArgumentException();
                var b = t.get("bounds"); keys(b, Set.of("x", "y", "width", "height"));
                double x = coordinate(b.get("x")), y = coordinate(b.get("y")), w = coordinate(b.get("width")), h = coordinate(b.get("height"));
                if (w <= 0 || h <= 0 || x + w > 1.000001 || y + h > 1.000001) throw new IllegalArgumentException();
                target = new Target(description, t.get("worn").asBoolean(), new Bounds(x, y, w, h));
            }
            section="衣物判断与属性对应";
            // A narrowly defined contradiction can safely be downgraded to the explicit
            // blocking issue. Never infer an identity, synthesize a target, or retain attributes.
            var blocking = Set.of(Assessment.NO_GARMENT, Assessment.MULTIPLE_GARMENTS, Assessment.UNUSABLE);
            if (!blocking.contains(assessment) && target == null && result.values().stream().allMatch(a -> a.values().isEmpty())) {
                var declared = warnings.stream().map(Issue::code).filter(code -> blocking.stream().anyMatch(a -> a.name().equals(code))).distinct().toList();
                if (declared.size() == 1) {
                    assessment = Assessment.valueOf(declared.getFirst());
                    warnings.add(new Issue("ASSESSMENT_RECONCILED", "模型同时返回完成状态与无法识别原因，已按明确的无法识别原因处理；没有填入衣物属性。", List.of(), "请补充主视衣物说明或核对原图。"));
                }
            }
            boolean blocked = blocking.contains(assessment);
            if (blocked && target != null) throw invalidOutput("模型同时返回无法识别与具体衣物目标，请补充主视衣物说明后重试。");
            if (requireTarget && !blocked && target == null) throw invalidOutput("模型声称识别完成，但没有给出衣物目标，请补充主视衣物说明后重试。");
            var output = new Result(assessment, Collections.unmodifiableMap(result), List.copyOf(warnings), target, imageStyle);
            if (output.blocksImages() && result.values().stream().anyMatch(a -> !a.values().isEmpty())) throw invalidOutput("模型无法确定衣物目标，却填入了衣物属性；本次未采用这些属性，请补充目标说明。");
            section="品类与领型、袖长对应";
            var category = result.get("category").values();
            if (!category.isEmpty() && Set.of("长裤", "短裤", "半身裙", "下装").contains(category.getFirst())
                    && (!result.get("sleeveLength").values().isEmpty() || !result.get("neckline").values().isEmpty())) throw new IllegalArgumentException();
            return output;
        } catch (ProcessingException e) {
            if ("RECOGNITION_INVALID_OUTPUT".equals(e.code())) throw e;
            throw invalidOutput("识别返回的“" + section + "”不符合格式或语义规则，未保存本次识别结果。");
        } catch (Exception e) {
            throw new ProcessingException(HttpStatus.BAD_GATEWAY, "RECOGNITION_INVALID_OUTPUT", "识别返回的“" + section + "”不符合格式或语义规则，未保存本次识别结果。");
        }
    }
    private ProcessingException invalidOutput(String message) { return new ProcessingException(HttpStatus.BAD_GATEWAY, "RECOGNITION_INVALID_OUTPUT", message); }
    public static void validateValues(Field f, List<String> values) {
        if (values == null || values.size() > (f.multiple() ? 12 : 1) || new HashSet<>(values).size() != values.size())
            throw ProcessingException.invalid("字段值数量无效：" + f.label());
        for (String v : values) if (v == null || v.isBlank() || v.length() > 400)
            throw ProcessingException.invalid("字段值须为 1 到 400 字的非空文本：" + f.label());
    }
    public static List<String> normalizeValues(Field f, List<String> values) {
        validateValues(f, values);
        return MetadataValues.canonical(f.key(), values);
    }
    private void keys(JsonNode n, Set<String> expected) {
        if (n == null || !n.isObject() || !new HashSet<>(n.propertyNames()).equals(expected)) throw new IllegalArgumentException();
    }
    private double coordinate(JsonNode n) {
        if (n == null || !n.isNumber()) throw new IllegalArgumentException();
        double value = n.asDouble();
        if (!Double.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException();
        return value;
    }
    private String text(JsonNode n, int max) {
        if (n == null || !n.isString() || n.asText().length() > max) throw new IllegalArgumentException();
        return n.asText();
    }
}
