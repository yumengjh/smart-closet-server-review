package com.smartcloset.generation;

import com.smartcloset.ClosetProperties;
import com.smartcloset.image.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import javax.imageio.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;
import static com.smartcloset.generation.GenerationProperties.*;

@Service
public class GenerationService {
    public record Request(String provider, String model, String prompt, Map<String, Object> parameters) {}
    public record Prepared(String provider, String model, String prompt, Map<String, Object> parameters, Protocol protocol) {}
    public record Output(byte[] png, String provider, String model, Protocol protocol, JsonNode usage, String requestId) {
        public Output(byte[] png, String provider, String model, Protocol protocol, JsonNode usage) {
            this(png, provider, model, protocol, usage, null);
        }
    }
    public record ProviderInfo(String id, Protocol protocol, String defaultModel, boolean configured,
                               Map<String, Object> defaults, List<String> modes) {}
    public record ModelInfo(String id, boolean imageCandidate) {}
    private static final int BODY_LIMIT = 32 * 1024 * 1024;
    private final GenerationProperties properties;
    private final ClosetProperties closet;
    private final ObjectMapper json;
    private final BoundedHttp http = new BoundedHttp();
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.smartcloset.processing.ProcessingResources resources;
    private <T>T limited(String account,java.util.function.Supplier<T> call){return resources==null?call.get():resources.exchange(account,call);}
    public GenerationService(GenerationProperties properties, ClosetProperties closet, ObjectMapper json) {
        this.properties = properties; this.closet = closet; this.json = json;
    }
    public List<ProviderInfo> providers() {
        return properties.providers().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> new ProviderInfo(e.getKey(), e.getValue().protocol(), e.getValue().defaultModel(),
                        e.getValue().configured(), e.getValue().defaults(), List.of("TEXT_TO_IMAGE", "IMAGE_TO_IMAGE"))).toList();
    }
    private Provider provider(String id) {
        var p = id == null ? null : properties.providers().get(id);
        if (p == null) throw ProcessingException.invalid("未知的生图服务商，请先在后端配置 provider。");
        if (!p.configured()) throw new ProcessingException(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_NOT_CONFIGURED", "该服务商尚未配置密钥。");
        return p;
    }
    public Prepared prepare(Request r) {
        return prepare(r, true);
    }
    public String revision(String id) {
        var p = provider(id);
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest((p.protocol().name() + "\n" + p.baseUrl()).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public Prepared frozen(Request r, String revision) {
        if (revision != null && !revision.equals(revision(r.provider())))
            throw new ProcessingException(HttpStatus.CONFLICT, "PROVIDER_CONFIG_CHANGED", "服务商协议或地址已改变，请重新创建任务。");
        return prepare(r, false);
    }
    private Prepared prepare(Request r, boolean includeDefaults) {
        if (r == null) throw ProcessingException.invalid("GENERATE 步骤需要 generation 配置。");
        var p = provider(r.provider());
        String model = r.model() == null || r.model().isBlank() ? p.defaultModel() : r.model();
        if (model == null || !model.matches("[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,159}") || model.contains(".."))
            throw ProcessingException.invalid("请填写有效的模型标识。");
        if (r.prompt() == null || r.prompt().isBlank() || r.prompt().length() > 12000)
            throw ProcessingException.invalid("prompt 不能为空且不能超过 12000 个字符。");
        if (p.protocol() == Protocol.GEMINI && (model.startsWith("models/") ? model.substring(7) : model).contains("/"))
            throw ProcessingException.invalid("Gemini 模型名称不应包含路径。");
        Map<String, Object> params = new LinkedHashMap<>(includeDefaults ? p.defaults() : Map.of());
        if (r.parameters() != null) params.putAll(r.parameters());
        if (json.writeValueAsBytes(params).length > 16384) throw ProcessingException.invalid("模型参数过大。");
        Set<String> reserved = Set.of("model", "prompt", "image", "images", "mask", "n", "stream", "contents", "tools",
                "systeminstruction", "generationconfig", "messages", "input", "authorization", "apikey", "baseurl", "headers", "endpoint");
        for (var entry : params.entrySet()) {
            String key = entry.getKey();
            if (!key.matches("[A-Za-z][A-Za-z0-9_]{0,63}") || reserved.contains(key.replace("_", "").toLowerCase(Locale.ROOT)))
                throw ProcessingException.invalid("模型参数包含保留字段，请使用独立的 model/prompt 和工作流输入；每次仅生成一张图片。");
            if (p.protocol() == Protocol.OPENAI_IMAGES && !(entry.getValue() instanceof String || entry.getValue() instanceof Number || entry.getValue() instanceof Boolean))
                throw ProcessingException.invalid("OpenAI Images 参数只能为字符串、数值或布尔值。");
        }
        if (p.protocol() == Protocol.GEMINI) {
            if (params.containsKey("candidateCount") && !Objects.equals(params.get("candidateCount"), 1))
                throw ProcessingException.invalid("candidateCount 只能为 1。");
            params.put("candidateCount", 1);
            params.put("responseModalities", List.of("TEXT", "IMAGE"));
        } else if (params.containsKey("output_format") && !Set.of("png", "jpeg").contains(params.get("output_format"))) {
            throw ProcessingException.invalid("output_format 目前支持 png 或 jpeg。");
        }
        return new Prepared(r.provider(), model, r.prompt(), Collections.unmodifiableMap(params), p.protocol());
    }
    public record Reference(String purpose,String label,byte[] png) {}
    public Output generate(Prepared r, byte[] input) {return generateReferences(r,input==null?List.of():List.of(new Reference("INPUT","参考图片",input)));}
    public Output generateReferences(Prepared r,List<Reference> references) {
        if(references==null||references.size()>20||references.stream().mapToLong(v -> v.png().length).sum()>32L*1024*1024)throw ProcessingException.invalid("参考图片数量或总大小超限。");
        var p = provider(r.provider());
        HttpRequest request = p.protocol() == Protocol.OPENAI_IMAGES ? openai(p, r, references) : gemini(p, r, references);
        var response = limited(r.provider(),()->http.send(request, BODY_LIMIT));
        JsonNode body = parse(response);
        byte[] image = null;
        if (p.protocol() == Protocol.OPENAI_IMAGES) {
            var data = body.path("data");
            if (data.isArray() && data.size() > 0) {
                var first = data.get(0);
                if (first.path("b64_json").isString()) image = decode(first.path("b64_json").asText());
                else if (first.path("url").isString()) image = download(p, first.path("url").asText());
            }
        } else {
            for (var candidate : body.path("candidates")) for (var part : candidate.path("content").path("parts")) {
                var inline = part.has("inlineData") ? part.path("inlineData") : part.path("inline_data");
                if (image == null && inline.path("data").isString()) image = decode(inline.path("data").asText());
            }
        }
        if (image == null) throw failure("PROVIDER_NO_IMAGE", "服务商未返回图片，可能被过滤、只返回了文本或模型不支持生图。");
        if(resources!=null)resources.usage(body.has("usage")?body.get("usage"):body.get("usageMetadata"));
        return new Output(normalize(image), r.provider(), r.model(), p.protocol(),
                body.has("usage") ? body.get("usage") : body.get("usageMetadata"),
                response.headers().firstValue("x-request-id").orElse(response.headers().firstValue("request-id").orElse(null)));
    }
    private HttpRequest.Builder builder(Provider p, URI uri) {
        var b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(p.timeoutSeconds()));
        return p.protocol() == Protocol.GEMINI ? b.header("x-goog-api-key", p.apiKey()) : b.header("Authorization", "Bearer " + p.apiKey());
    }
    private HttpRequest openai(Provider p, Prepared r, List<Reference> references) {
        var fields = new LinkedHashMap<String, Object>(r.parameters());
        fields.put("model", r.model()); fields.put("prompt", r.prompt()); fields.put("n", 1);
        if (references.isEmpty()) return builder(p, p.endpoint("/images/generations")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(fields))).build();
        String boundary = "closet-" + UUID.randomUUID();
        var bytes = new ByteArrayOutputStream();
        fields.forEach((key, value) -> {
            bytes.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + key + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
        });
        for(int index=0;index<references.size();index++) {
            var reference=references.get(index);
            String filename = references.size()==1 && reference.purpose().equals("INPUT") ? "input.png" : "reference-"+index+".png";
            bytes.writeBytes(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+(references.size()==1?"image":"image[]")+"\"; filename=\""+filename+"\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            bytes.writeBytes(reference.png());bytes.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        bytes.writeBytes(("--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        return builder(p, p.endpoint("/images/edits")).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build();
    }
    private HttpRequest gemini(Provider p, Prepared r, List<Reference> references) {
        var parts = new ArrayList<Map<String, Object>>(); parts.add(Map.of("text", r.prompt()));
        for(var reference:references){if(!reference.purpose().equals("INPUT"))parts.add(Map.of("text",reference.purpose()+": "+reference.label()));parts.add(Map.of("inlineData",Map.of("mimeType","image/png","data",Base64.getEncoder().encodeToString(reference.png()))));}
        var body = Map.of("contents", List.of(Map.of("role", "user", "parts", parts)), "generationConfig", r.parameters());
        String model = r.model().startsWith("models/") ? r.model().substring(7) : r.model();
        if (model.contains("/")) throw ProcessingException.invalid("Gemini 模型名称不应包含路径。");
        return builder(p, p.endpoint("/models/" + model + ":generateContent")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
    }
    public List<ModelInfo> models(String id) {
        var p = provider(id);
        var body = parse(limited(id,()->http.send(builder(p, p.endpoint("/models")).GET().build(), 2 * 1024 * 1024)));
        var list = new ArrayList<ModelInfo>();
        for (var node : body.path(p.protocol() == Protocol.GEMINI ? "models" : "data")) {
            String model = node.path(p.protocol() == Protocol.GEMINI ? "name" : "id").asText("");
            if (model.length() <= 200 && !model.isBlank()) list.add(new ModelInfo(model,
                    model.toLowerCase(Locale.ROOT).matches(".*(image|dall|flux|imagen|banana|seedream|recraft|stable|ideogram).*")));
        }
        return list; // Heuristic candidate flag only; no paid capability probes here.
    }
    private JsonNode parse(HttpResponse<byte[]> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw failure("PROVIDER_HTTP_" + response.statusCode(), providerErrorMessage(response.statusCode())).retryAfter(response.headers().firstValue("retry-after").orElse("30"));
        try { return json.readTree(response.body()); }
        catch (Exception e) { throw failure("PROVIDER_INVALID_RESPONSE", "服务商返回了无法解析的数据。"); }
    }
    private String providerErrorMessage(int status) {
        String detail = switch (status) {
            case 504 -> "服务商网关等待上游超时（HTTP 504）。请检查云端反向代理和 API 网关的上游超时设置；单独增加本机等待时间无法修复此错误。上游可能仍在处理或已计费。";
            case 429 -> "服务商拒绝了当前请求（HTTP 429），可能达到速率、并发或额度限制，请查看服务商日志。";
            case 401, 403 -> "服务商认证或访问权限校验失败（HTTP " + status + "），请核对服务端密钥与模型权限。";
            case 400, 422 -> "服务商不接受当前请求（HTTP " + status + "），请核对模型、图片和生成参数。";
            case 404 -> "服务商未找到请求的接口或模型（HTTP 404），请核对协议、API 地址和模型 ID。";
            case 413 -> "服务商拒绝了过大的请求（HTTP 413），请检查上游上传限制或减小输入图片。";
            case 502 -> "服务商网关收到无效的上游响应（HTTP 502），请检查服务商到上游模型的连接与日志。";
            case 503 -> "服务商暂时无法处理请求（HTTP 503），请检查可用上游和服务容量。";
            default -> "服务商返回 HTTP " + status + "，请根据状态码检查服务商日志。";
        };
        return detail + "不会自动重试。";
    }
    private byte[] decode(String data) {
        try { return Base64.getDecoder().decode(data); }
        catch (IllegalArgumentException e) { throw failure("PROVIDER_INVALID_IMAGE", "服务商返回的图片编码无效。"); }
    }
    private byte[] download(Provider p, String value) {
        URI url;
        try { url = URI.create(value); } catch (Exception e) { throw failure("PROVIDER_INVALID_URL", "服务商图片地址无效。"); }
        if (!"https".equals(url.getScheme()) || url.getHost() == null || url.getUserInfo() != null
                || url.getFragment() != null || (url.getPort() != -1 && url.getPort() != 443)
                || p.imageHosts().stream().noneMatch(host -> host.equalsIgnoreCase(url.getHost())))
            throw failure("PROVIDER_IMAGE_HOST_NOT_ALLOWED", "服务商返回了图片 URL；请在后端 image-hosts 中配置可信的图片域名，或使用 base64 输出。");
        try {
            for (var ip : InetAddress.getAllByName(url.getHost()))
                if (ip.isAnyLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress() || ip.isSiteLocalAddress() || ip.isMulticastAddress())
                    throw failure("PROVIDER_IMAGE_HOST_NOT_ALLOWED", "图片地址不能指向内网。");
        } catch (UnknownHostException e) { throw failure("PROVIDER_INVALID_URL", "图片域名解析失败。"); }
        // Signed image URLs must never receive the provider's API key. Redirects are disabled.
        var response = http.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(p.timeoutSeconds())).GET().build(), 20 * 1024 * 1024);
        if (response.statusCode() != 200) throw failure("PROVIDER_IMAGE_DOWNLOAD_FAILED", "服务商图片下载失败。");
        return response.body();
    }
    private byte[] normalize(byte[] bytes) {
        // Inspect dimensions before decoding; preserve provider alpha, strip metadata, store only PNG.
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw failure("PROVIDER_INVALID_IMAGE", "服务商未返回可读取的 PNG/JPEG 图片。");
            var reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (!Set.of("png", "jpeg").contains(format) || w < 32 || h < 32 || w > 8192 || h > 8192 || (long) w * h > closet.maxPixels())
                    throw failure("PROVIDER_INVALID_IMAGE", "服务商图片格式或尺寸不受支持。");
                var output = new ByteArrayOutputStream(); ImageIO.write(reader.read(0), "png", output); return output.toByteArray();
            } finally { reader.dispose(); }
        } catch (IOException e) { throw failure("PROVIDER_INVALID_IMAGE", "服务商图片解码失败。"); }
    }
    private ProcessingException failure(String code, String message) { return new ProcessingException(HttpStatus.BAD_GATEWAY, code, message); }
}
