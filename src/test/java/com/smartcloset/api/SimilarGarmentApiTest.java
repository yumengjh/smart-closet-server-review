package com.smartcloset.api;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcloset.security.ClosetAccountService;
import com.smartcloset.similar.GarmentVisuals;
import com.smartcloset.similar.Shapes;
import com.smartcloset.workflow.WorkflowFiles;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

/** Similar garments and basic search by image end to end with stored images (design 相似衣物 §8). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=optional:file:./target/no-local-secrets.yml", "closet.model-path=target/absent-similar-model.onnx", "closet.embedding-model-path=target/absent-embedding.onnx"})
class SimilarGarmentApiTest {
    @TempDir static Path storage;
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) { registry.add("closet.storage-dir", () -> storage.toString()); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired ClosetAccountService accounts;
    @Autowired GarmentVisuals visuals;
    final JsonMapper json = JsonMapper.builder().build();
    static final Color NAVY = new Color(30, 40, 90), RED = new Color(200, 30, 40), BLUE = new Color(60, 100, 170);

    final class Session {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf;
        HttpResponse<String> send(String method, String path, Object body) throws Exception {
            var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", "application/json");
            if (!"GET".equals(method)) b.header("X-XSRF-TOKEN", csrf);
            return client.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        }
        JsonNode ok(String method, String path, Object body) throws Exception { var r = send(method, path, body); assertEquals(200, r.statusCode(), path + " → " + r.body()); return json.readTree(r.body()); }
        JsonNode search(byte[] image, String role) throws Exception {
            String boundary = "b" + UUID.randomUUID().toString().replace("-", "");
            var out = new ByteArrayOutputStream();
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"q.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(image); out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            var r = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/garments/search-by-image" + (role == null ? "" : "?role=" + role)))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).header("X-XSRF-TOKEN", csrf).POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), r.body());
            return json.readTree(r.body());
        }
    }
    Session signIn(String user) throws Exception {
        accounts.create(user, "similar-test-password", "USER");
        var s = new Session();
        s.csrf = json.readTree(s.send("GET", "/api/auth/csrf", null).body()).path("token").asText();
        assertEquals(200, s.send("POST", "/api/auth/login", Map.of("username", user, "password", "similar-test-password")).statusCode());
        assertEquals(204, s.send("POST", "/api/auth/password", Map.of("currentPassword", "similar-test-password", "newPassword", "similar-test-password")).statusCode());
        return s;
    }

    String garment(String owner, String category, byte[] png, String status) throws Exception {
        String id = UUID.randomUUID().toString(), job = UUID.randomUUID().toString(); long now = System.currentTimeMillis();
        String asset = asset(owner, job, png, now);
        db.update("INSERT INTO garment(id,source_asset_id,display_asset_id,source_origin,overrides_json,display_user_selected,revision,created_at_ms,updated_at_ms,owner_username,closet_status,name) VALUES(?,?,?,'USER_UPLOAD',?,0,0,?,?,?,?,?)",
            id, asset, asset, json.writeValueAsString(Map.of("category", List.of(category), "patterns", List.of("纯色"))), now, now, owner, status, category + "-" + id.substring(0, 4));
        db.update("UPDATE workflow_job SET garment_id=? WHERE id=?", id, job);
        return id;
    }
    String asset(String owner, String job, byte[] png, long now) throws Exception {
        String asset = UUID.randomUUID().toString();
        if (db.queryForObject("SELECT COUNT(*) FROM workflow_job WHERE id=?", Integer.class, job) == 0)
            db.update("INSERT INTO workflow_job(id,fingerprint,status,created_at_ms,updated_at_ms,request_json,requires_review,version,owner_username) VALUES(?,?,'SUCCEEDED',?,?,'{}',0,0,?)", job, job, now, now, owner);
        String name = "step-" + db.queryForObject("SELECT COUNT(*) FROM image_asset WHERE job_id=?", Integer.class, job) + ".png";   // stored names are fixed (step-0..5)
        Files.createDirectories(storage.resolve("workflows").resolve(job)); Files.write(storage.resolve("workflows").resolve(job).resolve(name), png);
        db.update("INSERT INTO image_asset(id,job_id,file_name,sha256,byte_size,width,height,kind,review_status,created_at_ms) VALUES(?,?,?,?,?,200,200,'BASE_STICKER','APPROVED',?)", asset, job, name, WorkflowFiles.hash(png), png.length, now);
        return asset;
    }

    @Test void duplicatesSimilarDraftFlagsDismissalAndSearch() throws Exception {
        String owner = "sim-" + UUID.randomUUID().toString().substring(0, 8);
        var me = signIn(owner);
        byte[] navyTee = Shapes.png(Shapes.tee(NAVY, true));
        String a = garment(owner, "T恤", navyTee, "ACTIVE"), b = garment(owner, "T恤", navyTee, "ACTIVE");
        String red = garment(owner, "T恤", Shapes.png(Shapes.tee(RED, true)), "ACTIVE");
        String jeans = garment(owner, "牛仔裤", Shapes.png(Shapes.trousers(BLUE, true)), "ACTIVE");
        String draft = garment(owner, "T恤", navyTee, "DRAFT");
        visuals.index(owner, 100);
        assertTrue(visuals.pendingOwners().getOrDefault(owner, 0) == 0);

        var similar = me.ok("GET", "/api/garments/" + a + "/similar", null);
        var items = similar.path("items");
        assertEquals(b, items.get(0).path("id").asText());
        assertEquals("DUPLICATE", items.get(0).path("level").asText());
        assertTrue(items.get(0).path("reasons").toString().contains("几乎相同"));
        assertTrue(items.get(0).path("displayUrl").asText().startsWith("/api/"));
        for (var i : items) { assertNotEquals(jeans, i.path("id").asText(), "trousers are never compared with a tee"); assertNotEquals(draft, i.path("id").asText(), "drafts are not suggested as similar"); }
        for (var i : items) if (i.path("id").asText().equals(red)) assertNotEquals("DUPLICATE", i.path("level").asText());
        assertTrue(similar.path("ready").path("image").asBoolean());

        var dupes = me.ok("GET", "/api/garments/duplicates", null).path("items");
        assertEquals(1, dupes.size(), dupes.toString());
        assertEquals(Set.of(a, b), Set.of(dupes.get(0).path("a").path("id").asText(), dupes.get(0).path("b").path("id").asText()));

        var flags = me.ok("GET", "/api/garments/similar-flags?ids=" + draft + "&ids=" + red, null).path("flags");
        assertTrue(Set.of(a, b).contains(flags.path(draft).path("id").asText()), flags.toString());
        assertTrue(flags.path(red).isMissingNode());

        // "Not a duplicate" is remembered for the unordered pair.
        me.ok("POST", "/api/garments/similar-dismissals", Map.of("garmentA", b, "garmentB", a));
        assertEquals(0, me.ok("GET", "/api/garments/duplicates", null).path("items").size());
        var after = me.ok("GET", "/api/garments/" + a + "/similar", null).path("items").get(0);
        assertEquals("VERY_SIMILAR", after.path("level").asText());
        assertTrue(after.path("reasons").toString().contains("不是重复"));

        // Search by image: the same cut-out finds the navy tees first; a position filter keeps trousers out.
        var found = me.search(navyTee, null).path("items");
        assertTrue(Set.of(a, b).contains(found.get(0).path("id").asText()), found.toString());
        assertEquals("TRANSPARENT", me.search(navyTee, null).path("matting").asText());
        var onlyBottoms = me.search(navyTee, "BOTTOM").path("items");
        for (var i : onlyBottoms) assertEquals(jeans, i.path("id").asText());
        // A flat picture on a light background is matted locally (GRABCUT here) and still finds the tee.
        var flat = me.search(Shapes.png(Shapes.tee(NAVY, false)), "TOP");
        assertEquals("GRABCUT", flat.path("matting").asText());
        assertTrue(flat.path("note").asText().contains("认不出"));

        // Another user cannot look at these garments.
        var other = signIn("sim-other-" + UUID.randomUUID().toString().substring(0, 6));
        assertEquals(404, other.send("GET", "/api/garments/" + a + "/similar", null).statusCode());
        assertEquals(404, other.send("POST", "/api/garments/similar-dismissals", Map.of("garmentA", a, "garmentB", red)).statusCode());
    }

    @Test void changingTheDisplayImageQueuesARefingerprint() throws Exception {
        String owner = "simx-" + UUID.randomUUID().toString().substring(0, 8);
        signIn(owner);
        String a = garment(owner, "T恤", Shapes.png(Shapes.tee(NAVY, true)), "ACTIVE");
        visuals.index(owner, 100);
        assertFalse(visuals.pendingOwners().containsKey(owner));
        String job = db.queryForObject("SELECT id FROM workflow_job WHERE garment_id=?", String.class, a);
        String asset = asset(owner, job, Shapes.png(Shapes.tee(RED, true)), System.currentTimeMillis());
        db.update("UPDATE garment SET display_asset_id=? WHERE id=?", asset, a);
        assertEquals(1, visuals.pendingOwners().get(owner));
        visuals.index(owner, 100);
        assertFalse(visuals.pendingOwners().containsKey(owner));
    }
}
