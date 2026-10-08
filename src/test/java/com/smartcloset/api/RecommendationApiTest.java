package com.smartcloset.api;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcloset.security.ClosetAccountService;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=optional:file:./target/no-local-secrets.yml", "closet.model-path=target/absent-recommend-model.onnx"})
class RecommendationApiTest {
    @TempDir static Path storage;
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) { registry.add("closet.storage-dir", () -> storage.toString()); }

    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired ClosetAccountService accounts;
    final JsonMapper json = JsonMapper.builder().build();

    final class Session {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf;
        HttpResponse<String> send(String method, String path, Object body, boolean withCsrf) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", "application/json");
            if (withCsrf && !"GET".equals(method)) builder.header("X-XSRF-TOKEN", csrf);
            var publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
            return client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
        }
        JsonNode ok(String method, String path, Object body) throws Exception {
            var response = send(method, path, body, true);
            assertEquals(200, response.statusCode(), path + " → " + response.body());
            return json.readTree(response.body());
        }
    }

    private Session signIn(String username) throws Exception {
        accounts.create(username, "recommend-test-password", "USER");
        var s = new Session();
        s.csrf = json.readTree(s.send("GET", "/api/auth/csrf", null, false).body()).path("token").asText();
        assertEquals(200, s.send("POST", "/api/auth/login", Map.of("username", username, "password", "recommend-test-password"), true).statusCode());
        assertEquals(204, s.send("POST", "/api/auth/password", Map.of("currentPassword", "recommend-test-password", "newPassword", "recommend-test-password"), true).statusCode());
        return s;
    }

    private String garment(String owner, Map<String, List<String>> fields) {
        String id = UUID.randomUUID().toString(), job = UUID.randomUUID().toString(), asset = UUID.randomUUID().toString(); long now = System.currentTimeMillis();
        db.update("INSERT INTO workflow_job(id,fingerprint,status,created_at_ms,updated_at_ms,request_json,requires_review,version,owner_username) VALUES(?,?,'SUCCEEDED',?,?,'{}',0,0,?)", job, job, now, now, owner);
        db.update("INSERT INTO image_asset(id,job_id,file_name,sha256,byte_size,width,height,kind,review_status,created_at_ms) VALUES(?,?,'input.png','fixture',1,64,96,'INPUT','APPROVED',?)", asset, job, now);
        db.update("INSERT INTO garment(id,source_asset_id,display_asset_id,source_origin,overrides_json,display_user_selected,revision,created_at_ms,updated_at_ms,owner_username,closet_status) VALUES(?,?,?,'USER_UPLOAD',?,0,0,?,?,?,'ACTIVE')",
            id, asset, asset, json.writeValueAsString(fields), now, now, owner);
        db.update("UPDATE workflow_job SET garment_id=? WHERE id=?", id, job);
        return id;
    }

    private Map<String, String> seed(String owner) {
        var ids = new LinkedHashMap<String, String>();
        ids.put("shirt", garment(owner, Map.of("category", List.of("衬衫"), "primaryColor", List.of("白色"), "styles", List.of("通勤"), "seasons", List.of("春", "秋"))));
        ids.put("tee", garment(owner, Map.of("category", List.of("T恤"), "primaryColor", List.of("黑色"), "styles", List.of("休闲"), "seasons", List.of("夏", "秋"))));
        ids.put("sweater", garment(owner, Map.of("category", List.of("毛衣"), "primaryColor", List.of("藏蓝色"), "styles", List.of("通勤", "休闲"), "seasons", List.of("秋", "冬"))));
        ids.put("trousers", garment(owner, Map.of("category", List.of("长裤"), "primaryColor", List.of("黑色"), "styles", List.of("通勤"), "seasons", List.of("春", "秋", "冬"))));
        ids.put("jeans", garment(owner, Map.of("category", List.of("牛仔裤"), "primaryColor", List.of("蓝色"), "styles", List.of("休闲"), "seasons", List.of("春", "秋"))));
        ids.put("red", garment(owner, Map.of("category", List.of("半身裙"), "primaryColor", List.of("红色"), "styles", List.of("甜美"), "seasons", List.of("春", "秋"))));
        ids.put("loafers", garment(owner, Map.of("category", List.of("皮鞋"), "primaryColor", List.of("黑色"), "styles", List.of("通勤"))));
        ids.put("sneakers", garment(owner, Map.of("category", List.of("运动鞋"), "primaryColor", List.of("白色"), "styles", List.of("休闲"))));
        return ids;
    }

    @Test void recommendExplainsLogsAndAcceptsFeedbackWithinTheAccount() throws Exception {
        String owner = "rec-" + UUID.randomUUID().toString().substring(0, 8);
        var me = signIn(owner); var ids = seed(owner);
        var view = me.ok("POST", "/api/recommendations", Map.of("occasion", "COMMUTE", "temperatureC", 18, "avoidColors", List.of("红色")));
        var outfits = view.path("outfits");
        assertEquals(3, outfits.size(), view.toString());
        assertTrue(view.path("intent").path("description").asText().contains("通勤"));
        for (var o : outfits) {
            assertFalse(o.path("logId").asText().isBlank());
            assertEquals(7, o.path("dimensions").size());
            assertFalse(o.path("highlights").isEmpty());
            for (var g : o.path("garments")) assertNotEquals(ids.get("red"), g.path("id").asText());
        }
        var top = outfits.get(0);
        assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM recommendation_log WHERE owner_username=?", Integer.class, owner));
        assertTrue(db.queryForObject("SELECT score_vector_json FROM recommendation_log WHERE id=?", String.class, top.path("logId").asText()).contains("\"color\""));

        me.ok("POST", "/api/recommendations/logs/" + top.path("logId").asText() + "/feedback", Map.of("verdict", "DOWN", "reasons", List.of("COLOR_CLASH")));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM recommendation_feedback WHERE owner_username=?", Integer.class, owner));

        String runId = view.path("runId").asText();
        var swapTarget = top.path("garments").get(0).path("id").asText();
        var swap = me.ok("GET", "/api/recommendations/" + runId + "/outfits/" + top.path("key").asText() + "/swap?garmentId=" + swapTarget, null);
        for (var alt : swap.path("alternatives")) assertNotEquals(swapTarget, alt.path("garment").path("id").asText());
        if (!swap.path("alternatives").isEmpty()) {
            var adoptIds = new ArrayList<String>(); for (var g : swap.path("alternatives").get(0).path("outfit").path("garments")) adoptIds.add(g.path("id").asText());
            var adopted = me.ok("POST", "/api/recommendations/" + runId + "/outfits", Map.of("garmentIds", adoptIds));
            assertFalse(adopted.path("logId").asText().isBlank());
        }
        me.ok("GET", "/api/recommendations/" + runId + "/more?count=2", null);
        me.ok("POST", "/api/recommendations/events", Map.of("events", List.of(Map.of("kind", "OPEN_WORKSPACE", "logId", top.path("logId").asText()))));
        var wear = me.ok("POST", "/api/wear-logs", Map.of("garmentIds", List.of(ids.get("shirt"), ids.get("trousers"))));
        assertEquals(2, wear.path("garmentIds").size());

        var settings = me.ok("PATCH", "/api/style-profile", Map.of("boldness", "BOLD", "block", "color:NAVY", "city", "杭州"));
        assertEquals("BOLD", settings.path("boldness").asText());
        assertEquals("杭州", settings.path("city").asText());
        var profile = me.ok("GET", "/api/style-profile", null);
        assertTrue(profile.path("templates").path("pendingReview").asInt() >= 50);

        var other = signIn("rec-other-" + UUID.randomUUID().toString().substring(0, 8));
        assertEquals(404, other.send("POST", "/api/recommendations/logs/" + top.path("logId").asText() + "/feedback", Map.of("verdict", "UP"), true).statusCode());
        assertEquals(410, other.send("GET", "/api/recommendations/" + runId + "/more", null, true).statusCode());
        assertEquals(403, me.send("POST", "/api/recommendations", Map.of(), false).statusCode());
    }

    @Test void wardrobeChangeInvalidatesCachedRun() throws Exception {
        String owner = "rec-change-" + UUID.randomUUID().toString().substring(0, 8);
        var me = signIn(owner); seed(owner);
        var view = me.ok("POST", "/api/recommendations", Map.of("occasion", "DAILY"));
        garment(owner, Map.of("category", List.of("T恤"), "primaryColor", List.of("白色")));
        assertEquals(409, me.send("GET", "/api/recommendations/" + view.path("runId").asText() + "/more", null, true).statusCode());
    }

    @Test void invalidIntentIsRejected() throws Exception {
        var me = signIn("rec-bad-" + UUID.randomUUID().toString().substring(0, 8));
        assertEquals(400, me.send("POST", "/api/recommendations", Map.of("temperatureC", 99), true).statusCode());
        assertEquals(400, me.send("POST", "/api/recommendations", Map.of("count", 9), true).statusCode());
    }
}
