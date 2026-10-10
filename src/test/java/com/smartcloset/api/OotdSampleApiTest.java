package com.smartcloset.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.smartcloset.image.ProcessingException;
import com.smartcloset.ootd.OotdExtractor;
import com.smartcloset.security.ClosetAccountService;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Whole-outfit samples end to end with a fake model answer (design 2026-10-09 §10). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=optional:file:./target/no-local-secrets.yml", "closet.model-path=target/absent-ootd-model.onnx", "closet.embedding-model-path=target/absent-embedding.onnx"})
class OotdSampleApiTest {
    @TempDir static Path storage;
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) { registry.add("closet.storage-dir", () -> storage.toString()); }

    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired ClosetAccountService accounts;
    @MockitoBean OotdExtractor extractor;
    final JsonMapper json = JsonMapper.builder().build();

    static final String LOLITA = """
        {"usable":true,"rejectReason":"NONE","presentation":"FEMININE","seasons":["秋"],"occasions":["日常","约会"],"styles":["甜美","洛丽塔"],
         "items":[
          {"role":"HEADWEAR","category":"蝴蝶结发箍","primaryColor":"黑色","secondaryColors":[],"patterns":["纯色"],"fit":"","length":"","wear":"NA","visibility":"FULL","confidence":"HIGH","description":"黑色缎面蝴蝶结"},
          {"role":"TOP","category":"衬衫","primaryColor":"白色","secondaryColors":[],"patterns":["纯色"],"fit":"合体","length":"常规","wear":"TUCKED","visibility":"PARTIAL","confidence":"HIGH","description":"泡泡袖白衬衫"},
          {"role":"OVERALL","category":"格纹洛丽塔JSK","primaryColor":"藏蓝","secondaryColors":["白色"],"patterns":["格纹"],"fit":"合体","length":"及膝","wear":"NA","visibility":"FULL","confidence":"HIGH","description":"方领格纹背心裙"},
          {"role":"SOCKS","category":"过膝袜","primaryColor":"白色","secondaryColors":[],"patterns":["纯色"],"fit":"","length":"过膝","wear":"NA","visibility":"FULL","confidence":"MEDIUM","description":""},
          {"role":"SHOES","category":"玛丽珍鞋","primaryColor":"黑色","secondaryColors":[],"patterns":["纯色"],"fit":"","length":"","wear":"NA","visibility":"FULL","confidence":"HIGH","description":"圆头小皮鞋"},
          {"role":"OTHER","category":"毛绒挂件","primaryColor":"粉色","secondaryColors":[],"patterns":[],"fit":"","length":"","wear":"NA","visibility":"PARTIAL","confidence":"LOW","description":"挂在包上的粉色兔子"},
          {"role":"WINGS","category":"","primaryColor":"","secondaryColors":[],"patterns":[],"fit":"","length":"","wear":"WEIRD","visibility":"FULL","confidence":"HIGH","description":"不认识的东西"}],
         "pose":{"stance":"STANDING","bodyAngle":"THREE_QUARTER","gaze":"AWAY","hands":"双手提包在身前","description":"重心在后脚","tendency":"FEMININE"},
         "framing":{"shot":"FULL_BODY","cameraAngle":"EYE_LEVEL","mirrorSelfie":false},"scene":{"setting":"STREET","description":"秋天街边"},
         "hair":{"length":"LONG","description":"低双马尾"},"expression":"SMILE","highlights":"格纹JSK配白衬衫与白色过膝袜"}""";
    static final String MINOR = """
        {"usable":false,"rejectReason":"MINOR_LIKELY","presentation":"UNKNOWN","seasons":[],"occasions":[],"styles":[],"items":[],
         "pose":{"stance":"UNKNOWN","bodyAngle":"UNKNOWN","gaze":"UNKNOWN","hands":"","description":"","tendency":"UNKNOWN"},
         "framing":{"shot":"UNKNOWN","cameraAngle":"UNKNOWN","mirrorSelfie":false},"scene":{"setting":"UNKNOWN","description":""},
         "hair":{"length":"UNKNOWN","description":""},"expression":"UNKNOWN","highlights":""}""";

    final class Session {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf;
        HttpResponse<String> send(String method, String path, Object body) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", "application/json");
            if (!"GET".equals(method)) builder.header("X-XSRF-TOKEN", csrf);
            var publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : json.writeValueAsString(body));
            return client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
        }
        JsonNode ok(String method, String path, Object body) throws Exception {
            var r = send(method, path, body); assertEquals(200, r.statusCode(), path + " → " + r.body()); return json.readTree(r.body());
        }
        HttpResponse<String> upload(byte[] image, String license, String url) throws Exception {
            String boundary = "b" + UUID.randomUUID().toString().replace("-", "");
            var out = new ByteArrayOutputStream();
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"a.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(image);
            out.write(("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"license\"\r\n\r\n" + license + "\r\n").getBytes(StandardCharsets.UTF_8));
            if (url != null) out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"sourceUrl\"\r\n\r\n" + url + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/admin/ootd-samples"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private Session signIn(String role) throws Exception {
        String username = "ootd-" + role.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
        accounts.create(username, "ootd-test-password", role);
        var s = new Session();
        s.csrf = json.readTree(s.send("GET", "/api/auth/csrf", null).body()).path("token").asText();
        assertEquals(200, s.send("POST", "/api/auth/login", Map.of("username", username, "password", "ootd-test-password")).statusCode());
        assertEquals(204, s.send("POST", "/api/auth/password", Map.of("currentPassword", "ootd-test-password", "newPassword", "ootd-test-password")).statusCode());
        return s;
    }
    private static byte[] photo(int seed) throws Exception {
        var image = new BufferedImage(300, 450, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics(); g.setColor(new Color(seed * 37 % 255, 120, 200)); g.fillRect(0, 0, 300, 450); g.setColor(Color.WHITE); g.fillRect(seed % 200, 50, 60, 300); g.dispose();
        var out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out); return out.toByteArray();
    }
    private OotdExtractor.Extraction answer(String raw) {
        return new OotdExtractor.Extraction(json.readTree(raw), "fake-vision", "ootd-v1:test", json.readTree("{\"prompt_tokens\":1200,\"completion_tokens\":800}"));
    }
    private boolean stored(String id) { return Files.exists(storage.resolve("ootd-samples").resolve(id + ".png")); }

    @Test void onlyAdminsReachSamples() throws Exception {
        var user = signIn("USER");
        assertEquals(403, user.send("GET", "/api/admin/ootd-samples", null).statusCode());
        assertEquals(403, user.upload(photo(1), "OWN", null).statusCode());
    }

    @Test void uploadExtractReviewDraftAndStatistics() throws Exception {
        when(extractor.extract(any())).thenReturn(answer(LOLITA));
        var admin = signIn("ADMIN");
        int usageBefore = db.queryForObject("SELECT COUNT(*) FROM model_usage WHERE feature='OOTD_EXTRACT' AND input_tokens=1200 AND output_tokens=800", Integer.class);
        var r = admin.upload(photo(2), "OWN", "https://example.com/look/1");
        assertEquals(200, r.statusCode(), r.body());
        var sample = json.readTree(r.body());
        String id = sample.path("id").asText();
        assertEquals("EXTRACTED", sample.path("status").asText());
        assertTrue(sample.path("hasImage").asBoolean());
        assertTrue(stored(id));
        var items = sample.path("extraction").path("items");
        assertEquals(7, items.size(), items.toString());
        assertEquals("OTHER", items.get(6).path("role").asText(), "an unknown role falls back to OTHER, the description is kept");
        assertEquals("NA", items.get(6).path("wear").asText());
        assertEquals("藏蓝色", items.get(2).path("primaryColor").asText(), "colour names are canonicalised");
        assertEquals("OVERALL", items.get(2).path("engineRole").asText(), "the recommender reads 洛丽塔JSK as a pinafore");
        assertEquals("OTHER", items.get(5).path("role").asText());
        assertEquals(usageBefore + 1, db.queryForObject("SELECT COUNT(*) FROM model_usage WHERE feature='OOTD_EXTRACT' AND input_tokens=1200 AND output_tokens=800", Integer.class));
        assertEquals(200, admin.send("GET", "/api/admin/ootd-samples/" + id + "/image", null).statusCode());

        // Same file again: refused without a second model call.
        assertEquals(409, admin.upload(photo(2), "OWN", null).statusCode());

        // Admin fixes the unknown item and approves; the photo is deleted.
        var edited = (tools.jackson.databind.node.ObjectNode) sample.path("extraction").deepCopy();
        ((tools.jackson.databind.node.ObjectNode) edited.path("items").get(5)).put("role", "HANDHELD");
        assertEquals("HANDHELD", admin.ok("PUT", "/api/admin/ootd-samples/" + id, edited).path("reviewed").path("items").get(5).path("role").asText());
        var approved = admin.ok("POST", "/api/admin/ootd-samples/" + id + "/review", Map.of("decision", "APPROVED"));
        assertEquals("APPROVED", approved.path("status").asText());
        assertFalse(approved.path("hasImage").asBoolean());
        assertFalse(stored(id));
        assertEquals(404, admin.send("GET", "/api/admin/ootd-samples/" + id + "/image", null).statusCode());
        assertEquals(400, admin.send("POST", "/api/admin/ootd-samples/" + id + "/review", Map.of("decision", "REJECTED")).statusCode());

        // Statistics read the approved, edited version.
        var stats = admin.ok("GET", "/api/admin/ootd-samples/stats", null);
        assertTrue(stats.path("approved").asInt() >= 1);
        assertTrue(stats.path("roles").path("OVERALL").asInt() >= 1 && stats.path("roles").path("HANDHELD").asInt() >= 1, stats.toString());
        assertTrue(stats.path("bodies").toString().contains("衬衫 + JSK"), stats.path("bodies").toString());
        assertTrue(stats.path("unknownItems").toString().contains("不认识的东西"), "an item the recommender cannot place is a vocabulary candidate");
        assertFalse(stats.path("unknownItems").toString().contains("毛绒挂件"), "毛绒 already reads as a hand-held object");

        // Template draft goes into the normal review list, pending.
        var draft = admin.ok("POST", "/api/admin/ootd-samples/" + id + "/template-draft", Map.of("kind", "CLASSIC"));
        String templateId = draft.path("templateId").asText();
        var slots = draft.path("definition").path("slots");
        assertEquals(List.of("HEADWEAR", "TOP", "OVERALL", "SOCKS", "SHOES"), slots.valueStream().map(s -> s.path("roles").get(0).asText()).toList());
        assertEquals("JSK", slots.get(2).path("categoryAny").get(0).asText());
        assertEquals("FEMALE", draft.path("definition").path("audience").get(0).asText());
        var list = admin.ok("GET", "/api/admin/outfit-templates", null).path("items");
        var entry = list.valueStream().filter(e -> e.path("id").asText().equals(templateId)).findFirst().orElseThrow();
        assertEquals("PENDING_REVIEW", entry.path("reviewStatus").asText());
        assertEquals(409, admin.send("POST", "/api/admin/ootd-samples/" + id + "/template-draft", Map.of()).statusCode());
        assertEquals(200, admin.send("PATCH", "/api/admin/outfit-templates/" + templateId, Map.of("reviewStatus", "APPROVED")).statusCode());
    }

    @Test void likelyMinorsAreRejectedAndTheirPhotoDeletedAtOnce() throws Exception {
        when(extractor.extract(any())).thenReturn(answer(MINOR));
        var admin = signIn("ADMIN");
        var sample = json.readTree(admin.upload(photo(3), "UNKNOWN", null).body());
        String id = sample.path("id").asText();
        assertEquals("REJECTED", sample.path("status").asText());
        assertEquals("MINOR_LIKELY", sample.path("rejectReason").asText());
        assertTrue(sample.path("extraction").isNull());
        assertFalse(stored(id));
    }

    @Test void failedExtractionKeepsThePhotoForARetry() throws Exception {
        when(extractor.extract(any())).thenThrow(new ProcessingException(HttpStatus.SERVICE_UNAVAILABLE, "RECOGNITION_NOT_CONFIGURED", "识图服务商尚未配置密钥。"));
        var admin = signIn("ADMIN");
        var sample = json.readTree(admin.upload(photo(4), "RESEARCH", null).body());
        String id = sample.path("id").asText();
        assertEquals("FAILED", sample.path("status").asText());
        assertTrue(sample.path("error").asText().contains("密钥"));
        assertTrue(stored(id));
        org.mockito.Mockito.doReturn(answer(LOLITA)).when(extractor).extract(any());
        assertEquals("EXTRACTED", admin.ok("POST", "/api/admin/ootd-samples/" + id + "/extract", null).path("status").asText());
    }

    @Test void unknownLicenceSamplesOnlyCount() throws Exception {
        when(extractor.extract(any())).thenReturn(answer(LOLITA));
        var admin = signIn("ADMIN");
        String id = json.readTree(admin.upload(photo(5), "UNKNOWN", null).body()).path("id").asText();
        admin.ok("POST", "/api/admin/ootd-samples/" + id + "/review", Map.of("decision", "APPROVED"));
        assertEquals(400, admin.send("POST", "/api/admin/ootd-samples/" + id + "/template-draft", Map.of()).statusCode());
        assertEquals(400, admin.upload(photo(6), "STOLEN", null).statusCode());
        assertEquals(400, admin.upload(photo(7), "OWN", "javascript:alert(1)").statusCode());
    }

    @Test void unusableSamplesCannotBeApprovedUntilFixed() throws Exception {
        when(extractor.extract(any())).thenReturn(answer(LOLITA.replace("\"usable\":true,\"rejectReason\":\"NONE\"", "\"usable\":false,\"rejectReason\":\"NOT_FULL_OUTFIT\"")));
        var admin = signIn("ADMIN");
        var sample = json.readTree(admin.upload(photo(8), "OWN", null).body());
        String id = sample.path("id").asText();
        assertEquals("EXTRACTED", sample.path("status").asText(), "only clear-cut rejections are automatic");
        assertEquals(400, admin.send("POST", "/api/admin/ootd-samples/" + id + "/review", Map.of("decision", "APPROVED")).statusCode());
        var fixed = (tools.jackson.databind.node.ObjectNode) sample.path("extraction").deepCopy();
        fixed.put("usable", true); fixed.put("rejectReason", "NONE");
        admin.ok("PUT", "/api/admin/ootd-samples/" + id, fixed);
        assertEquals("APPROVED", admin.ok("POST", "/api/admin/ootd-samples/" + id + "/review", Map.of("decision", "APPROVED")).path("status").asText());
    }
}
