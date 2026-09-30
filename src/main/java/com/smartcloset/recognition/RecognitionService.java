package com.smartcloset.recognition;

import com.smartcloset.generation.*;
import com.smartcloset.image.ProcessingException;
import com.smartcloset.workflow.WorkflowFiles;
import java.io.*;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;
import static com.smartcloset.recognition.Recognition.*;

@Service
public class RecognitionService {
    public record ProviderInfo(String id, String protocol, String defaultModel, boolean configured, boolean preferred) {}
    private final RecognitionProperties properties;
    private final GenerationProperties credentials;
    private final RecognitionSchema schema;
    private final ObjectMapper json;
    private final String prompt;
    private final BoundedHttp http = new BoundedHttp();
    public RecognitionService(RecognitionProperties properties, GenerationProperties credentials, RecognitionSchema schema, ObjectMapper json) throws IOException {
        this.properties = properties; this.credentials = credentials; this.schema = schema; this.json = json;
        try (var in = new ClassPathResource("recognition/" + RecognitionSchema.VERSION + ".txt").getInputStream()) { prompt = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }
    public List<ProviderInfo> providers() {
        return properties.providers().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e -> {
            var c = credentials.providers().get(e.getValue().credentialProvider());
            return new ProviderInfo(e.getKey(), e.getValue().protocol().name(), e.getValue().defaultModel(), c != null && c.configured(), e.getKey().equals(properties.defaultProvider()));
        }).toList();
    }
    private RecognitionProperties.Provider provider(String id) {
        var p = properties.providers().get(id);
        if (p == null) throw new ProcessingException(HttpStatus.SERVICE_UNAVAILABLE, "RECOGNITION_NOT_CONFIGURED", "请在后端配置识图服务商和模型。");
        credential(p); return p;
    }
    private GenerationProperties.Provider credential(RecognitionProperties.Provider p) {
        var c = credentials.providers().get(p.credentialProvider());
        if (c == null || !c.configured()) throw new ProcessingException(HttpStatus.SERVICE_UNAVAILABLE, "RECOGNITION_NOT_CONFIGURED", "识图服务商尚未配置密钥。");
        return c;
    }
    private String revision(String id) {
        var p = provider(id); var c = credential(p);
        return WorkflowFiles.hash(json.writeValueAsBytes(List.of(p.protocol(), c.baseUrl(), p.maxOutputTokens(), Objects.toString(p.reasoningEffort(), ""), c.timeoutSeconds(), "image-long-side-1600-white-v1")));
    }
    public Prepared prepare(Request request) {
        String id = request == null || request.provider() == null || request.provider().isBlank() ? properties.defaultProvider() : request.provider();
        if (id == null) throw new ProcessingException(HttpStatus.SERVICE_UNAVAILABLE, "RECOGNITION_NOT_CONFIGURED", "请配置默认识图服务商。");
        var p = provider(id);
        String model = request == null || request.model() == null || request.model().isBlank() ? p.defaultModel() : request.model();
        if (model == null || !model.matches("[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,159}") || model.contains("..")) throw ProcessingException.invalid("识图模型名称无效。");
        if (p.protocol() == RecognitionProperties.Protocol.GEMINI && model.replaceFirst("^models/", "").contains("/")) throw ProcessingException.invalid("Gemini 模型名称不能包含路径。");
        return new Prepared(id, model, revision(id), RecognitionSchema.VERSION + ":" + WorkflowFiles.hash(prompt.getBytes(StandardCharsets.UTF_8)), RecognitionSchema.VERSION, prompt, schema.schema());
    }
    private HttpRequest.Builder builder(String id, String path) {
        var p = provider(id); var c = credential(p);
        var b = HttpRequest.newBuilder(c.endpoint(path)).timeout(Duration.ofSeconds(c.timeoutSeconds()));
        return p.protocol() == RecognitionProperties.Protocol.GEMINI ? b.header("x-goog-api-key", c.apiKey()) : b.header("Authorization", "Bearer " + c.apiKey());
    }
    public List<String> models(String id) {
        var p = provider(id); var response = http.send(builder(id, "/models").GET().build(), 2 * 1024 * 1024);
        var body = body(response);
        var models = new ArrayList<String>();
        for (var item : body.path(p.protocol() == RecognitionProperties.Protocol.GEMINI ? "models" : "data")) {
            String value = item.path(p.protocol() == RecognitionProperties.Protocol.GEMINI ? "name" : "id").asText("");
            if (!value.isBlank() && value.length() <= 160) models.add(value);
        }
        return models;
    }
    public Output recognize(Prepared r, byte[] png) {
        if (!Objects.equals(r.providerRevision(), revision(r.provider()))) throw new ProcessingException(HttpStatus.CONFLICT, "PROVIDER_CONFIG_CHANGED", "识图服务商配置已改变，请重新提交。");
        var p = provider(r.provider()); var fields = new LinkedHashMap<String, Object>(); String path;
        String encoded = Base64.getEncoder().encodeToString(visionImage(png));
        if (p.protocol() == RecognitionProperties.Protocol.OPENAI_CHAT) {
            path = "/chat/completions";
            fields.put("model", r.model()); fields.put("max_completion_tokens", p.maxOutputTokens());
            if (p.reasoningEffort() != null) fields.put("reasoning_effort", p.reasoningEffort());
            fields.put("messages", List.of(Map.of("role", "system", "content", r.prompt()), Map.of("role", "user", "content", List.of(
                Map.of("type", "text", "text", "识别这张照片中的单件衣物，输出 JSON。"),
                Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64," + encoded))))));
            fields.put("response_format", Map.of("type", "json_schema", "json_schema", Map.of("name", "garment_metadata", "strict", true, "schema", r.schema())));
        } else {
            path = "/models/" + r.model().replaceFirst("^models/", "") + ":generateContent";
            fields.put("systemInstruction", Map.of("parts", List.of(Map.of("text", r.prompt()))));
            fields.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", "识别这张照片中的单件衣物。"),
                Map.of("inlineData", Map.of("mimeType", "image/png", "data", encoded))))));
            fields.put("generationConfig", Map.of("responseMimeType", "application/json", "responseJsonSchema", r.schema(), "maxOutputTokens", p.maxOutputTokens()));
        }
        var response = http.send(builder(r.provider(), path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(fields))).build(), 1024 * 1024);
        var body = body(response); String content;
        if (p.protocol() == RecognitionProperties.Protocol.OPENAI_CHAT) {
            var choice = body.path("choices").path(0); var message = choice.path("message");
            if (!message.path("refusal").isMissingNode() && !message.path("refusal").isNull()) throw failure("RECOGNITION_REFUSED", "模型拒绝识别该图片。");
            if (!"stop".equals(choice.path("finish_reason").asText())) throw failure("RECOGNITION_INCOMPLETE", "模型响应被截断或未正常结束，未更新元信息。");
            content = message.path("content").asText("");
        } else {
            var candidate = body.path("candidates").path(0);
            if (!"STOP".equals(candidate.path("finishReason").asText())) throw failure("RECOGNITION_INCOMPLETE", "模型未正常完成，可能被过滤或截断。");
            var text = new StringBuilder(); for (var part : candidate.path("content").path("parts")) if (!part.path("thought").asBoolean(false)) text.append(part.path("text").asText(""));
            content = text.toString();
        }
        JsonNode raw;
        try { raw = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(content); } catch (Exception e) { throw failure("RECOGNITION_INVALID_OUTPUT", "模型没有返回有效 JSON，未更新元信息。"); }
        return new Output(schema.validate(raw), raw, body.has("usage") ? body.get("usage") : body.get("usageMetadata"),
            response.headers().firstValue("x-request-id").orElse(body.path("id").asText(null)));
    }
    private byte[] visionImage(byte[] png) {
        try {
            var source=ImageIO.read(new ByteArrayInputStream(png));
            double scale=Math.min(1d,1600d/Math.max(source.getWidth(),source.getHeight()));
            var target=new BufferedImage(Math.max(1,(int)Math.round(source.getWidth()*scale)),Math.max(1,(int)Math.round(source.getHeight()*scale)),BufferedImage.TYPE_INT_RGB);
            var g=target.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,target.getWidth(),target.getHeight());
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(source,0,0,target.getWidth(),target.getHeight(),null);g.dispose();
            var out=new ByteArrayOutputStream();ImageIO.write(target,"png",out);return out.toByteArray();
        } catch (IOException|RuntimeException e) { throw failure("RECOGNITION_INVALID_IMAGE","识图输入无法读取。"); }
    }
    private JsonNode body(HttpResponse<byte[]> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw failure("PROVIDER_HTTP_" + response.statusCode(), "识图上游返回 HTTP " + response.statusCode() + "，未自动重试。");
        try { return json.readTree(response.body()); } catch (Exception e) { throw failure("PROVIDER_INVALID_RESPONSE", "识图上游响应无法解析。"); }
    }
    private ProcessingException failure(String code, String message) { return new ProcessingException(HttpStatus.BAD_GATEWAY, code, message); }
}
