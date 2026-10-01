package com.smartcloset.api;

import com.smartcloset.security.ClosetAccountService;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=optional:file:./target/no-local-secrets.yml", "closet.model-path=target/absent-outfit-model.onnx"})
class OutfitApiTest {
    @TempDir static Path storage;
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) {
        registry.add("closet.storage-dir", () -> storage.toString());
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ClosetAccountService accounts;
    final CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    final HttpClient client = HttpClient.newBuilder().cookieHandler(cookieManager).build();
    final JsonMapper json = JsonMapper.builder().build();
    String csrf;

    @Test void outfitHistoryLinksServeTheVueShell() throws Exception {
        for (String path : List.of("/outfits", "/outfits/new", "/outfits/" + UUID.randomUUID())) {
            var response = get(path);
            assertEquals(200, response.statusCode(), path);
            assertTrue(response.body().contains("id=\"app\""), path);
        }
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> send(String method, String path, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json");
        if (!"GET".equals(method)) builder.header("X-XSRF-TOKEN", csrf);
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
        return client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }
    private void signIn(String username) throws Exception {
        accounts.create(username, "outfit-test-password", "USER");
        String token = json.readTree(get("/api/auth/csrf").body()).path("token").asText();
        var login = client.send(HttpRequest.newBuilder(uri("/api/auth/login")).header("Content-Type", "application/json").header("X-XSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("username", username, "password", "outfit-test-password")))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode(), login.body());
        var password = client.send(HttpRequest.newBuilder(uri("/api/auth/password")).header("Content-Type", "application/json").header("X-XSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("currentPassword", "outfit-test-password", "newPassword", "outfit-test-password")))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(204, password.statusCode(), password.body());
        csrf = token;
    }
    private UUID activeGarment(String owner) throws Exception {
        UUID garment = UUID.randomUUID(), job = UUID.randomUUID(), asset = UUID.randomUUID();
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO workflow_job(id,fingerprint,status,created_at_ms,updated_at_ms,request_json,requires_review,version,owner_username) VALUES(?,?,?,?,?,'{}',0,0,?)",
                job.toString(), UUID.randomUUID().toString(), "SUCCEEDED", now, now, owner);
        jdbc.update("INSERT INTO image_asset(id,job_id,file_name,sha256,byte_size,width,height,kind,review_status,created_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?)",
                asset.toString(), job.toString(), "input.png", "fixture", 1, 120, 160, "INPUT", "APPROVED", now);
        jdbc.update("INSERT INTO garment(id,source_asset_id,display_asset_id,source_origin,overrides_json,display_user_selected,revision,created_at_ms,updated_at_ms,owner_username,closet_status) VALUES(?,?,?,'USER_UPLOAD','{}',0,0,?,?,?,'ACTIVE')",
                garment.toString(), asset.toString(), asset.toString(), now, now, owner);
        jdbc.update("UPDATE workflow_job SET garment_id=? WHERE id=?", garment.toString(), job.toString());
        Path folder = storage.resolve("workflows").resolve(job.toString());
        Files.createDirectories(folder);
        Files.write(folder.resolve("input.png"), new byte[]{1});
        return garment;
    }
    private Map<String, Object> saveBody(UUID garment, UUID asset, int x, Long revision) {
        var element = new LinkedHashMap<String, Object>();
        element.put("garmentId", garment); element.put("assetId", asset); element.put("role", "TOP");
        element.put("x", x); element.put("y", 320); element.put("width", 440); element.put("height", 580);
        element.put("rotation", 0); element.put("zIndex", 0); element.put("garmentLabel", "白色 衬衫");
        element.put("cropX", 0); element.put("cropY", 0); element.put("cropWidth", 1); element.put("cropHeight", 1);
        var body = new LinkedHashMap<String, Object>();
        body.put("name", "日常搭配"); body.put("scene", Map.of("version", 1, "elements", List.of(element)));
        if (revision != null) body.put("revision", revision);
        return body;
    }

    @Test void savesSnapshotsChecksRevisionsAndKeepsOutfitsPrivate() throws Exception {
        String owner = "outfit-" + UUID.randomUUID().toString().substring(0, 8);
        signIn(owner);
        UUID garment = activeGarment(owner);
        UUID asset = UUID.fromString(jdbc.queryForObject("SELECT source_asset_id FROM garment WHERE id=?", String.class, garment.toString()));

        var created = send("POST", "/api/outfits", saveBody(garment, asset, 420, null));
        assertEquals(200, created.statusCode(), created.body());
        JsonNode outfit = json.readTree(created.body());
        String outfitId = outfit.path("id").asText();
        assertEquals("日常搭配", outfit.path("name").asText());
        assertEquals(0, outfit.path("revision").asLong());
        assertEquals("/api/workflows/" + jdbc.queryForObject("SELECT job_id FROM image_asset WHERE id=?", String.class, asset.toString()) + "/files/input.png",
                outfit.at("/scene/elements/0/assetUrl").asText());
        assertEquals("ACTIVE", outfit.at("/scene/elements/0/garmentStatus").asText());

        var updated = send("PUT", "/api/outfits/" + outfitId, saveBody(garment, asset, 510, 0L));
        assertEquals(200, updated.statusCode(), updated.body());
        assertEquals(1, json.readTree(updated.body()).path("revision").asLong());
        assertEquals(409, send("PUT", "/api/outfits/" + outfitId, saveBody(garment, asset, 520, 0L)).statusCode());
        assertEquals(1, json.readTree(get("/api/outfits/" + outfitId).body()).path("revision").asLong());
        assertEquals(1, json.readTree(get("/api/outfits").body()).size());

        String otherName = "other-" + UUID.randomUUID().toString().substring(0, 8);
        accounts.create(otherName, "outfit-test-password", "USER");
        CookieManager otherCookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient other = HttpClient.newBuilder().cookieHandler(otherCookies).build();
        String otherToken = json.readTree(other.send(HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString()).body()).path("token").asText();
        var otherLogin = other.send(HttpRequest.newBuilder(uri("/api/auth/login")).header("Content-Type", "application/json").header("X-XSRF-TOKEN", otherToken)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("username", otherName, "password", "outfit-test-password")))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, otherLogin.statusCode(), otherLogin.body());
        other.send(HttpRequest.newBuilder(uri("/api/auth/password")).header("Content-Type", "application/json").header("X-XSRF-TOKEN", otherToken)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("currentPassword", "outfit-test-password", "newPassword", "outfit-test-password")))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, other.send(HttpRequest.newBuilder(uri("/api/outfits/" + outfitId)).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());

        assertEquals(204, send("DELETE", "/api/outfits/" + outfitId + "?revision=1", null).statusCode());
        assertEquals(0, json.readTree(get("/api/outfits").body()).size());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM garment WHERE id=?", Integer.class, garment.toString()));
    }

    @Test void rejectsInvalidSceneAndRequiresLoginAndCsrf() throws Exception {
        assertEquals(401, get("/api/outfits").statusCode());
        String owner = "outfit-" + UUID.randomUUID().toString().substring(0, 8);
        signIn(owner);
        var invalidElement = new LinkedHashMap<String, Object>();
        invalidElement.put("garmentId", UUID.randomUUID()); invalidElement.put("assetId", UUID.randomUUID());
        invalidElement.put("role", "TOP"); invalidElement.put("x", -1); invalidElement.put("y", 100);
        invalidElement.put("width", 200); invalidElement.put("height", 200); invalidElement.put("rotation", 0);
        invalidElement.put("zIndex", 0); invalidElement.put("garmentLabel", "衣物");
        invalidElement.put("cropX", 0); invalidElement.put("cropY", 0); invalidElement.put("cropWidth", 1); invalidElement.put("cropHeight", 1);
        Map<String, Object> invalid = Map.of("name", "bad", "scene", Map.of("version", 1, "elements", List.of(invalidElement)));
        assertEquals(400, send("POST", "/api/outfits", invalid).statusCode());
        var noCsrf = client.send(HttpRequest.newBuilder(uri("/api/outfits")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}" )).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, noCsrf.statusCode());
    }
}
