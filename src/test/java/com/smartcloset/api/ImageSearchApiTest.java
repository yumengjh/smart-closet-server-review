package com.smartcloset.api;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcloset.security.ClosetAccountService;
import com.smartcloset.similar.*;
import com.smartcloset.workflow.WorkflowFiles;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
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

/**
 * Image-text model end to end with the stand-in ONNX models (design 模型内存与图像向量 §7~§10): image vectors after
 * adoption, the image part in similar garments, the duplicate notification on adoption (once per pair, summary for
 * batches), the one-click scan, the super search (picture, picture + words, words matching pictures) and notifications.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=optional:file:./target/no-local-secrets.yml", "closet.model-path=target/absent-image-search-model.onnx",
            "closet.embedding-model-path=target/absent-embedding.onnx", "closet.duplicate-check-background=false", "closet.image-model-min-free-mb=1",
            // The stand-in text tower gives small cosines; real model values are in application.yml.
            "closet.image-text-threshold=0.0", "closet.image-text-margin=0.05", "closet.image-text-window=0.5"})
class ImageSearchApiTest {
    @TempDir static Path storage;
    @TempDir static Path models;
    static final Color NAVY = new Color(30, 40, 90), RED = new Color(200, 30, 40), BLUE = new Color(60, 100, 170);

    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) throws Exception {
        for (var f : List.of("clip-image.onnx", "clip-text.onnx", "clip-vocab.txt")) {
            Files.copy(Path.of("src/test/resources/onnx", f), models.resolve(f), StandardCopyOption.REPLACE_EXISTING);
            Files.setLastModifiedTime(models.resolve(f), FileTime.fromMillis(System.currentTimeMillis() - 60_000));
        }
        registry.add("closet.storage-dir", () -> storage.toString());
        registry.add("closet.image-model-path", () -> models.resolve("clip-image.onnx").toString());
        registry.add("closet.image-text-model-path", () -> models.resolve("clip-text.onnx").toString());
        registry.add("closet.image-text-vocab-path", () -> models.resolve("clip-vocab.txt").toString());
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired ClosetAccountService accounts;
    @Autowired GarmentVisuals visuals;
    @Autowired GarmentImageVectors vectors;
    @Autowired DuplicateChecks checks;
    @Autowired DuplicateScans scans;
    final JsonMapper json = JsonMapper.builder().build();

    final class Session {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf;
        HttpResponse<String> send(String method, String path, Object body) throws Exception {
            var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", "application/json");
            if (!"GET".equals(method)) b.header("X-XSRF-TOKEN", csrf);
            return client.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        }
        JsonNode ok(String method, String path, Object body) throws Exception { var r = send(method, path, body); assertEquals(200, r.statusCode(), path + " → " + r.body()); return json.readTree(r.body()); }
        JsonNode multipart(String path, byte[] image, Map<String, String> fields) throws Exception {
            String boundary = "b" + UUID.randomUUID().toString().replace("-", "");
            var out = new ByteArrayOutputStream();
            for (var f : fields.entrySet()) out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + f.getKey() + "\"\r\n\r\n" + f.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
            if (image != null) {
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"q.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(image); out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            var r = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).header("X-XSRF-TOKEN", csrf).POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), r.body());
            return json.readTree(r.body());
        }
    }
    Session signIn(String user) throws Exception {
        accounts.create(user, "image-search-password", "USER");
        var s = new Session();
        s.csrf = json.readTree(s.send("GET", "/api/auth/csrf", null).body()).path("token").asText();
        assertEquals(200, s.send("POST", "/api/auth/login", Map.of("username", user, "password", "image-search-password")).statusCode());
        assertEquals(204, s.send("POST", "/api/auth/password", Map.of("currentPassword", "image-search-password", "newPassword", "image-search-password")).statusCode());
        return s;
    }

    String garment(String owner, String category, String color, byte[] png) throws Exception {
        String id = UUID.randomUUID().toString(), job = UUID.randomUUID().toString(), asset = UUID.randomUUID().toString(); long now = System.currentTimeMillis();
        db.update("INSERT INTO workflow_job(id,fingerprint,status,created_at_ms,updated_at_ms,request_json,requires_review,version,owner_username) VALUES(?,?,'SUCCEEDED',?,?,'{}',0,0,?)", job, job, now, now, owner);
        Files.createDirectories(storage.resolve("workflows").resolve(job)); Files.write(storage.resolve("workflows").resolve(job).resolve("step-0.png"), png);
        db.update("INSERT INTO image_asset(id,job_id,file_name,sha256,byte_size,width,height,kind,review_status,created_at_ms) VALUES(?,?,'step-0.png',?,?,200,200,'BASE_STICKER','APPROVED',?)", asset, job, WorkflowFiles.hash(png), png.length, now);
        db.update("INSERT INTO garment(id,source_asset_id,display_asset_id,source_origin,overrides_json,display_user_selected,revision,created_at_ms,updated_at_ms,owner_username,closet_status,name) VALUES(?,?,?,'USER_UPLOAD',?,0,0,?,?,?,'ACTIVE',?)",
            id, asset, asset, json.writeValueAsString(Map.of("category", List.of(category), "primaryColor", List.of(color))), now, now, owner, category + "-" + id.substring(0, 4));
        db.update("UPDATE workflow_job SET garment_id=? WHERE id=?", id, job);
        return id;
    }
    /** Background steps 2 and 4 (the queue does the same on its own, a little later). */
    void index(String owner) { visuals.index(owner, 100); vectors.index(owner, 100); }

    @Test void vectorsSimilarDuplicateNotificationScanAndSuperSearch() throws Exception {
        String owner = "img-" + UUID.randomUUID().toString().substring(0, 8);
        var me = signIn(owner);
        byte[] navyTee = Shapes.png(Shapes.tee(NAVY, true));
        String a = garment(owner, "T恤", "藏蓝", navyTee);
        String red = garment(owner, "T恤", "红色", Shapes.png(Shapes.tee(RED, true)));
        String jeans = garment(owner, "牛仔裤", "蓝色", Shapes.png(Shapes.trousers(BLUE, true)));
        index(owner);
        assertEquals(0, vectors.pending(owner));
        assertEquals("READY", vectors.status(owner).get("state"));
        assertTrue((Boolean) vectors.status(owner).get("textSearch"));
        checks.check(owner);   // the three first garments: nothing alike yet
        assertEquals(0, me.ok("GET", "/api/notifications", null).path("unread").asInt());

        // Adopting the same tee again: one notification pointing at the new garment, never twice for the pair.
        String b = garment(owner, "T恤", "藏蓝", navyTee);
        index(owner);
        assertEquals(1, checks.check(owner));
        var list = me.ok("GET", "/api/notifications", null);
        assertEquals(1, list.path("unread").asInt(), list.toString());
        var n = list.path("items").get(0);
        assertEquals("DUPLICATE", n.path("kind").asText());
        assertEquals("/garment/" + b, n.path("href").asText());
        assertTrue(n.path("body").asText().contains("可能和衣柜里的"));
        assertEquals(0, checks.check(owner), "checked once per display image");

        var similar = me.ok("GET", "/api/garments/" + a + "/similar", null);
        var first = similar.path("items").get(0);
        assertEquals(b, first.path("id").asText());
        assertTrue(first.path("parts").has("image"), first.toString());
        assertEquals("READY", similar.path("imageModel").path("state").asText());
        assertTrue(similar.path("ready").path("imageVector").asBoolean());

        // Words alone also match the pictures themselves (the stand-in text tower maps 火 to red; 1 red of 4).
        var words = me.multipart("/api/garments/search", null, Map.of("q", "火焰"));
        assertTrue(words.path("items").size() > 0, words.toString());
        for (var i : words.path("items")) {
            assertNotEquals(jeans, i.path("garment").path("id").asText());
            assertTrue(i.path("reasons").toString().contains("图片像"), i.toString());
        }
        assertEquals("READY", words.path("imageModel").path("state").asText());
        // A batch of more than three likely duplicates becomes one summary.
        for (int i = 0; i < 4; i++) garment(owner, "T恤", "红色", Shapes.png(Shapes.tee(RED, true)));
        index(owner);
        checks.check(owner);
        list = me.ok("GET", "/api/notifications", null);
        assertEquals("DUPLICATE_SUMMARY", list.path("items").get(0).path("kind").asText(), list.toString());
        assertEquals("/settings?section=tidy", list.path("items").get(0).path("href").asText());

        // One-click scan: runs as a task, reports the pairs as a notification, one run per user at a time.
        var started = me.ok("POST", "/api/garments/duplicate-scan", null).path("scan");
        assertEquals(started.path("id").asText(), me.ok("POST", "/api/garments/duplicate-scan", null).path("scan").path("id").asText());
        scans.run(started.path("id").asText());
        var done = me.ok("GET", "/api/garments/duplicate-scan", null);
        assertEquals("DONE", done.path("scan").path("status").asText(), done.toString());
        assertTrue(done.path("scan").path("pairs").asInt() >= 2);
        assertEquals("DUPLICATE_SCAN", me.ok("GET", "/api/notifications", null).path("items").get(0).path("kind").asText());

        // Notifications: read one, read all, never someone else's.
        String nid = me.ok("GET", "/api/notifications", null).path("items").get(0).path("id").asText();
        int unread = me.ok("GET", "/api/notifications", null).path("unread").asInt();
        assertEquals(unread - 1, me.ok("POST", "/api/notifications/" + nid + "/read", null).path("unread").asInt());
        var other = signIn("img-other-" + UUID.randomUUID().toString().substring(0, 6));
        assertEquals(404, other.send("POST", "/api/notifications/" + nid + "/read", null).statusCode());
        assertEquals(0, other.ok("GET", "/api/notifications", null).path("items").size());
        assertEquals(0, me.ok("POST", "/api/notifications/read-all", null).path("unread").asInt());
        // Deleting hides a notice (one or all); someone else cannot delete it.
        int shown = me.ok("GET", "/api/notifications", null).path("items").size();
        assertEquals(404, other.send("DELETE", "/api/notifications/" + nid, null).statusCode());
        me.ok("DELETE", "/api/notifications/" + nid, null);
        var afterDelete = me.ok("GET", "/api/notifications", null);
        assertEquals(shown - 1, afterDelete.path("items").size());
        for (var i : afterDelete.path("items")) assertNotEquals(nid, i.path("id").asText());
        me.ok("DELETE", "/api/notifications", null);
        assertEquals(0, me.ok("GET", "/api/notifications", null).path("items").size());

        // Super search with a picture: model-based look first, same response shape as the text search.
        var pic = me.multipart("/api/garments/search", navyTee, Map.of());
        assertEquals("IMAGE", pic.path("mode").asText());
        assertTrue(Set.of(a, b).contains(pic.path("items").get(0).path("garment").path("id").asText()), pic.toString());
        assertEquals("READY", pic.path("imageModel").path("state").asText());
        assertTrue(pic.path("note").asText().contains("图像识别"));
        // Picture + words: the words filter by colour.
        var picRed = me.multipart("/api/garments/search", navyTee, Map.of("q", "红色"));
        assertEquals("IMAGE_TEXT", picRed.path("mode").asText());
        assertTrue(picRed.path("items").size() > 0);
        for (var i : picRed.path("items")) assertTrue(i.path("garment").path("effective").path("primaryColor").toString().contains("红"), i.toString());
        // The old endpoint stays and uses the model too.
        var old = me.multipart("/api/garments/search-by-image", navyTee, Map.of());
        assertTrue(Set.of(a, b).contains(old.path("items").get(0).path("id").asText()));
        assertNotNull(red);
    }
}
