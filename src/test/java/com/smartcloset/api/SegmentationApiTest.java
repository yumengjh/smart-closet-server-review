package com.smartcloset.api;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import com.smartcloset.security.ClosetAccountService;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "closet.model-path=target/absent-test-model.onnx")
class SegmentationApiTest {
    @TempDir static Path storage;
    @LocalServerPort int port;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
    private final JsonMapper json = JsonMapper.builder().build();
    @Autowired ClosetAccountService accounts;
    private String csrf;
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) {
        registry.add("closet.storage-dir", () -> storage.toString());
    }
    @BeforeEach void signInAdministrator() throws Exception {
        accounts.bootstrap("seg-admin", "seg-admin-password");
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        csrf = json.readTree(response.body()).path("token").asText();
        var login = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/login")).header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"seg-admin\",\"password\":\"seg-admin-password\"}")).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, login.statusCode(), new String(login.body(), StandardCharsets.UTF_8));
        var password = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/password")).header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString("{\"currentPassword\":\"seg-admin-password\",\"newPassword\":\"seg-admin-password\"}")).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(204, password.statusCode());
    }
    private HttpRequest.Builder csrf(HttpRequest.Builder request) { return request.header("X-XSRF-TOKEN", csrf); }

    private HttpResponse<byte[]> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    private byte[] fixture() throws Exception {
        BufferedImage image = new BufferedImage(160, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0, 0, 160, 200);
        g.setColor(Color.BLUE); g.fillRect(40, 40, 80, 120); g.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes); return bytes.toByteArray();
    }
    private HttpResponse<byte[]> upload(byte[] bytes, String query) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write("--closet-test\r\nContent-Disposition: form-data; name=\"file\"; filename=\"../../fake.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        body.write(bytes); body.write("\r\n--closet-test--\r\n".getBytes(StandardCharsets.UTF_8));
        return client.send(csrf(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/segmentations?" + query)))
                .header("Content-Type", "multipart/form-data; boundary=closet-test")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test void uploadReadDownloadAndRefineOverRealHttp() throws Exception {
        var response = upload(fixture(), "method=GRABCUT");
        assertEquals(201, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        var result = json.readTree(response.body());
        String location = response.headers().firstValue("Location").orElseThrow();
        assertEquals(200, get(location).statusCode());
        assertEquals("GRABCUT", result.path("method").asText());
        for (String field : new String[]{"originalUrl", "maskUrl", "cutoutUrl", "croppedUrl", "sourceUrl"}) {
            var download = get(result.path(field).asText());
            assertEquals(200, download.statusCode());
            assertNotNull(ImageIO.read(new java.io.ByteArrayInputStream(download.body())));
        }
        var cutout = ImageIO.read(new java.io.ByteArrayInputStream(get(result.path("cutoutUrl").asText()).body()));
        assertEquals(0, cutout.getRGB(0, 0) >>> 24);
        assertEquals(255, cutout.getRGB(80, 100) >>> 24);
        var source = get(result.path("sourceUrl").asText());
        assertArrayEquals(fixture(), source.body());
        var refined = client.send(csrf(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + location + "/refine")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"method\":\"GRABCUT\",\"region\":{\"x\":20,\"y\":20,\"width\":120,\"height\":160}}"))
                .build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(201, refined.statusCode(), new String(refined.body(), StandardCharsets.UTF_8));
        assertNotEquals(result.path("id").asText(), json.readTree(refined.body()).path("id").asText());
        assertEquals(200, get(location).statusCode());
        assertFalse(Files.exists(storage.resolve("fake.jpg")));
    }

    @Test void rejectsCorruptImageMissingParametersAndInvalidMethod() throws Exception {
        assertEquals(400, upload("hello".getBytes(), "method=GRABCUT").statusCode());
        assertEquals(400, upload(fixture(), "method=GRABCUT&x=1").statusCode());
        assertEquals(400, upload(fixture(), "method=UNKNOWN").statusCode());
        assertEquals(400, upload(fixture(), "method=GRABCUT&x=150&y=0&width=100&height=100").statusCode());
    }

    @Test void missingModelReturns503AndDoesNotSilentlyFallback() throws Exception {
        var response = upload(fixture(), "method=U2NET");
        assertEquals(503, response.statusCode());
        assertEquals("MODEL_NOT_INSTALLED", json.readTree(response.body()).path("code").asText());
    }

    @Test void healthMethodsMissingResultAndFileAllowlist() throws Exception {
        assertEquals(200, get("/api/health").statusCode());
        var methods = json.readTree(get("/api/segmentation-methods").body());
        assertFalse(methods.path("clothingClassification").asBoolean());
        assertEquals(404, get("/api/segmentations/00000000-0000-0000-0000-000000000000").statusCode());
        assertEquals(404, get("/api/segmentations/00000000-0000-0000-0000-000000000000/files/result.json").statusCode());
        assertEquals(404, get("/no-such-route").statusCode());
    }

    @Test void rejectsOversizedMultipartUpload() throws Exception {
        var response = upload(new byte[15 * 1024 * 1024 + 1], "method=GRABCUT");
        assertEquals(413, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
    }

    @Test void createsAndCachesStickerWithoutChangingSavedSegmentation() throws Exception {
        var response = upload(fixture(), "method=GRABCUT");
        assertEquals(201, response.statusCode());
        var result = json.readTree(response.body());
        String location = response.headers().firstValue("Location").orElseThrow();
        byte[] originalMetadata = get(location).body();
        byte[] originalCutout = get(result.path("cutoutUrl").asText()).body();
        assertEquals(404, get(location + "/sticker").statusCode());
        assertEquals(404, get(location + "/sticker/image").statusCode());
        var request = csrf(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + location + "/sticker")))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        var created = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(201, created.statusCode(), new String(created.body(), StandardCharsets.UTF_8));
        var sticker = json.readTree(created.body());
        assertEquals(com.smartcloset.image.StickerRenderer.STYLE, sticker.path("style").asText());
        assertEquals(result.path("id").asText(), sticker.path("segmentationId").asText());
        var download = get(sticker.path("imageUrl").asText());
        assertEquals(200, download.statusCode());
        assertEquals("image/png", download.headers().firstValue("Content-Type").orElseThrow());
        var image = ImageIO.read(new java.io.ByteArrayInputStream(download.body()));
        assertEquals(1024, image.getWidth()); assertEquals(1024, image.getHeight());
        assertEquals(0, image.getRGB(0, 0) >>> 24); assertEquals(255, image.getRGB(512, 512) >>> 24);
        var cached = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, cached.statusCode()); assertEquals(sticker, json.readTree(cached.body()));
        assertEquals(sticker, json.readTree(get(location + "/sticker").body()));
        assertArrayEquals(originalMetadata, get(location).body());
        assertArrayEquals(originalCutout, get(result.path("cutoutUrl").asText()).body());
        assertEquals(404, get(location + "/files/sticker-" + com.smartcloset.image.StickerRenderer.STYLE + "/result.json").statusCode());
    }

    @Test void rotatesUploadPersistsCorrectedPixelsAndDoesNotRotateAgainOnRefine() throws Exception {
        var image = ImageIO.read(new java.io.ByteArrayInputStream(fixture()));
        image.setRGB(45, 45, Color.RED.getRGB());
        var bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes);
        var response = upload(bytes.toByteArray(), "method=GRABCUT&rotationDegrees=90");
        assertEquals(201, response.statusCode());
        var result = json.readTree(response.body());
        assertEquals(90, result.path("rotationDegrees").asDouble());
        assertEquals(200, result.path("width").asInt()); assertEquals(160, result.path("height").asInt());
        byte[] correctedBytes = get(result.path("sourceUrl").asText()).body();
        assertArrayEquals(correctedBytes, get(result.path("originalUrl").asText()).body());
        var corrected = ImageIO.read(new java.io.ByteArrayInputStream(correctedBytes));
        assertEquals(Color.RED.getRGB(), corrected.getRGB(154, 45));
        assertFalse(java.util.Arrays.equals(bytes.toByteArray(), correctedBytes));
        Path dir = storage.resolve(result.path("id").asText());
        assertFalse(Files.exists(dir.resolve("source.jpg")));
        String location = response.headers().firstValue("Location").orElseThrow();
        for (String body : new String[]{"{\"method\":\"GRABCUT\"}", "{\"method\":\"GRABCUT\",\"rotationDegrees\":90}"}) {
            var refined = client.send(csrf(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + location + "/refine")))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(201, refined.statusCode());
            var newResult = json.readTree(refined.body());
            var saved = ImageIO.read(new java.io.ByteArrayInputStream(get(newResult.path("originalUrl").asText()).body()));
            if (body.contains("rotationDegrees")) {
                assertEquals(180, newResult.path("rotationDegrees").asDouble());
                assertEquals(160, saved.getWidth()); assertEquals(200, saved.getHeight());
                assertEquals(Color.RED.getRGB(), saved.getRGB(114, 154));
            } else {
                assertEquals(90, newResult.path("rotationDegrees").asDouble());
                assertArrayEquals(correctedBytes, get(newResult.path("originalUrl").asText()).body());
            }
        }
        for (String angle : new String[]{"NaN", "Infinity", "361", "-361"})
            assertEquals(400, upload(fixture(), "method=GRABCUT&rotationDegrees=" + angle).statusCode());
        assertEquals(400, upload(fixture(), "method=GRABCUT&rotationDegrees=90&x=0&y=180&width=50&height=10").statusCode());
    }

    @Test void servesTheLocalStudioWithoutFrontendTooling() throws Exception {
        var page = get("/studio/index.html"); assertEquals(200, page.statusCode());
        assertTrue(new String(page.body(), StandardCharsets.UTF_8).contains("选图与方向校正"));
        assertEquals(200, get("/studio/styles.css").statusCode());
        assertEquals(200, get("/studio/app.js").statusCode());
    }

    @Test void legacyResultsWithoutRotationRemainReadableAndCanBeRefined() throws Exception {
        var response = upload(fixture(), "method=GRABCUT");
        assertEquals(201, response.statusCode());
        var result = (tools.jackson.databind.node.ObjectNode) json.readTree(response.body());
        result.remove("rotationDegrees");
        json.writerWithDefaultPrettyPrinter().writeValue(storage.resolve(result.path("id").asText()).resolve("result.json").toFile(), result);
        String location = response.headers().firstValue("Location").orElseThrow();
        assertEquals(200, get(location).statusCode());
        var refined = client.send(csrf(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + location + "/refine")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"method\":\"GRABCUT\"}")).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(201, refined.statusCode());
        var newResult = json.readTree(refined.body());
        assertEquals(0, newResult.path("rotationDegrees").asDouble());
        assertEquals(160, newResult.path("width").asInt()); assertEquals(200, newResult.path("height").asInt());
        assertArrayEquals(get(result.path("originalUrl").asText()).body(), get(newResult.path("originalUrl").asText()).body());
    }
}
