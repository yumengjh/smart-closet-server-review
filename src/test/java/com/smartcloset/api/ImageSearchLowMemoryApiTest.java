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

/** Low memory (design 模型内存与图像向量 G2/G17): the model is installed but not loaded; everything falls back and says so. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=optional:file:./target/no-local-secrets.yml", "closet.model-path=target/absent-image-search-model.onnx",
            "closet.embedding-model-path=target/absent-embedding.onnx", "closet.duplicate-check-background=false", "closet.image-model-min-free-mb=99999999",
            // The stand-in text tower gives small cosines; real model values are in application.yml.
            "closet.image-text-threshold=0.0", "closet.image-text-margin=0.05", "closet.image-text-window=0.5"})
class ImageSearchLowMemoryApiTest {
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

    @Test void fallsBackAndTellsTheFrontEnd() throws Exception {
        String owner = "imglow-" + UUID.randomUUID().toString().substring(0, 8);
        var me = signIn(owner);
        byte[] navyTee = Shapes.png(Shapes.tee(NAVY, true));
        String a = garment(owner, "T恤", "藏蓝", navyTee);
        garment(owner, "T恤", "红色", Shapes.png(Shapes.tee(RED, true)));
        index(owner);
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM garment_image_embedding WHERE owner_username=?", Integer.class, owner) == 0 ? 1 : 0, "nothing encoded");
        var status = vectors.status(owner);
        assertEquals("LOW_MEMORY", status.get("state"));
        assertTrue(String.valueOf(status.get("message")).contains("内存紧张"));
        assertFalse((Boolean) status.get("textSearch"));

        // Duplicate check does not wait for vectors that cannot be computed now.
        String b = garment(owner, "T恤", "藏蓝", navyTee);
        index(owner);
        assertEquals(1, checks.check(owner));
        assertEquals("/garment/" + b, me.ok("GET", "/api/notifications", null).path("items").get(0).path("href").asText());

        var pic = me.multipart("/api/garments/search", navyTee, Map.of());
        assertEquals("LOW_MEMORY", pic.path("imageModel").path("state").asText());
        assertTrue(pic.path("imageModel").path("message").asText().contains("内存紧张"), pic.toString());
        assertTrue(Set.of(a, b).contains(pic.path("items").get(0).path("garment").path("id").asText()), "colour/silhouette still finds it");
        assertTrue(pic.path("note").asText().contains("认不出"));
        assertEquals("LOW_MEMORY", me.ok("GET", "/api/garments/" + a + "/similar", null).path("imageModel").path("state").asText());
        assertEquals("LOW_MEMORY", me.ok("GET", "/api/garments/duplicates", null).path("imageModel").path("state").asText());
        assertEquals("LOW_MEMORY", me.ok("GET", "/api/garments/search?q=" + URLEncoder.encode("火焰", StandardCharsets.UTF_8), null).path("imageModel").path("state").asText());
    }
}
