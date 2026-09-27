package com.smartcloset.api;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.ArrayList;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real HTTP/ONNX run. Requires local model; performs no network downloads. */
@EnabledIfSystemProperty(named = "closet.samples", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "closet.storage-dir=target/sample-run")
class SampleComparisonTest {
    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private final Path report = Path.of("reports/latest");
    private static final String[] SAMPLES = {"white-shirt-wood", "white-shirt-light", "yellow-shirt-clutter", "clothes-on-bed"};

    record Outcome(String sample, String method, int status, long httpMillis, JsonNode response) {}

    @Test void compareDownloadedPhotosOverHttpAndSaveArtifacts() throws Exception {
        Files.createDirectories(report);
        var outcomes = new ArrayList<Outcome>();
        StringBuilder html = new StringBuilder("""
                <!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>衣物分割实验</title><style>
                body{font:16px/1.6 system-ui,sans-serif;max-width:1280px;margin:40px auto;padding:0 20px;background:#f5f5f3;color:#202622}
                h1{margin-bottom:8px}p{color:#505952}section{margin:32px 0}figure{margin:0;min-width:0}img{width:100%;height:440px;object-fit:contain;background:repeating-conic-gradient(#ddd 0% 25%,#fff 0% 50%) 0/20px 20px}
                .grid{display:grid;grid-template-columns:repeat(3,1fr);gap:16px}figcaption{font-size:14px;overflow-wrap:anywhere}pre{white-space:pre-wrap}@media(max-width:700px){.grid{grid-template-columns:1fr}}
                </style><h1>衣物分割实验</h1><p>真实 HTTP 调用；原图 / GrabCut / U²-Net。格子代表透明背景。耗时包含解码、分割和保存，首次模型调用还包含加载权重。以下不是准确率评测。</p>
                <p>浅色上衣是完整单件样本；木地板图是衣物出画的局部照片，黄色图含杂物，床上图含重叠、多件、出画衣物。后三张用于观察失败。模型不验证主体是否为衣服。</p>
                """);
        int successes = 0;
        BufferedImage overview = new BufferedImage(1050, SAMPLES.length * 390, BufferedImage.TYPE_INT_RGB);
        Graphics2D overviewGraphics = overview.createGraphics();
        overviewGraphics.setColor(Color.WHITE); overviewGraphics.fillRect(0, 0, overview.getWidth(), overview.getHeight());
        int row = 0;
        try {
            for (String sample : SAMPLES) {
                byte[] input = Files.readAllBytes(Path.of("samples", sample + ".jpg"));
                Path sampleDir = report.resolve(sample); Files.createDirectories(sampleDir);
                Files.write(sampleDir.resolve("input.jpg"), input);
                html.append("<section><h2>").append(sample).append("</h2><div class=grid><figure><img src=\"")
                        .append(sample).append("/input.jpg\"><figcaption>原图</figcaption></figure>");
                paintCell(overviewGraphics, ImageIO.read(new java.io.ByteArrayInputStream(input)), 0, row, sample + " / original");
                int column = 1;
                for (String method : new String[]{"GRABCUT", "U2NET"}) {
                    long start = System.nanoTime();
                    var response = upload(input, method);
                    long elapsed = (System.nanoTime() - start) / 1_000_000;
                    var body = json.readTree(response.body());
                    outcomes.add(new Outcome(sample, method, response.statusCode(), elapsed, body));
                    assertNotEquals(500, response.statusCode(), body.toString());
                    assertNotEquals(503, response.statusCode(), body.toString());
                    Path dir = sampleDir.resolve(method); Files.createDirectories(dir);
                    Files.write(dir.resolve("response.json"), response.body());
                    if (response.statusCode() == 201) {
                        successes++;
                        for (String field : new String[]{"originalUrl", "maskUrl", "cutoutUrl", "croppedUrl"}) {
                            var fileResponse = client.send(HttpRequest.newBuilder(uri(body.path(field).asText())).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                            assertEquals(200, fileResponse.statusCode());
                            Files.write(dir.resolve(field.replace("Url", ".png")), fileResponse.body());
                        }
                        var cutout = ImageIO.read(dir.resolve("cutout.png").toFile());
                        assertTrue(cutout.getColorModel().hasAlpha());
                        assertEquals(body.path("width").asInt(), cutout.getWidth());
                        if (sample.equals("white-shirt-light") && method.equals("U2NET")) {
                            assertTrue(body.path("foregroundRatio").asDouble() > .10);
                            assertTrue(body.path("foregroundRatio").asDouble() < .85);
                            assertEquals(0, cutout.getRGB(0, 0) >>> 24);
                        }
                        html.append("<figure><img src=\"").append(sample).append('/').append(method).append("/cutout.png\"><figcaption>")
                                .append(method).append(" · ").append(body.path("processingMillis").asLong()).append(" ms · 前景比例 ")
                                .append(body.path("foregroundRatio").asDouble()).append("<br>提醒：").append(body.path("warnings")).append("</figcaption></figure>");
                        paintCell(overviewGraphics, cutout, column, row, method + " / " + body.path("processingMillis").asLong() + " ms");
                    } else {
                        html.append("<figure><pre>").append(method).append(" HTTP ").append(response.statusCode()).append("<br>").append(body).append("</pre></figure>");
                    }
                    column++;
                }
                html.append("</div></section>"); row++;
            }
        } finally { overviewGraphics.dispose(); }
        assertTrue(successes >= 6, "Most sample runs should produce inspectable output");
        ImageIO.write(overview, "png", report.resolve("comparison.png").toFile());
        Files.writeString(report.resolve("index.html"), html.append("</html>").toString());
        json.writerWithDefaultPrettyPrinter().writeValue(report.resolve("results.json").toFile(), outcomes);
    }

    @Test void modelRefinementUsesOriginalCoordinatesAndKeepsOutsideTransparent() throws Exception {
        var uploaded = upload(Files.readAllBytes(Path.of("samples/white-shirt-light.jpg")), "U2NET");
        assertEquals(201, uploaded.statusCode());
        var original = json.readTree(uploaded.body());
        var response = client.send(HttpRequest.newBuilder(uri("/api/segmentations/" + original.path("id").asText() + "/refine"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"method\":\"U2NET\",\"region\":{\"x\":20,\"y\":200,\"width\":1160,\"height\":1400}}"))
                .build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(201, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        var refined = json.readTree(response.body());
        assertNotEquals(original.path("id").asText(), refined.path("id").asText());
        var bytes = client.send(HttpRequest.newBuilder(uri(refined.path("cutoutUrl").asText())).GET().build(), HttpResponse.BodyHandlers.ofByteArray()).body();
        var cutout = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        assertEquals(original.path("width").asInt(), cutout.getWidth());
        assertEquals(original.path("height").asInt(), cutout.getHeight());
        assertEquals(0, cutout.getRGB(0, 0) >>> 24);
        assertEquals(0, cutout.getRGB(600, 1800) >>> 24);
        assertTrue((cutout.getRGB(600, 1000) >>> 24) > 200);
    }

    private void paintCell(Graphics2D g, BufferedImage image, int col, int row, String label) {
        int x = col * 350, y = row * 390;
        for (int cy = y + 30; cy < y + 380; cy += 10) for (int cx = x; cx < x + 340; cx += 10) {
            g.setColor(((cx / 10 + cy / 10) % 2 == 0) ? new Color(225, 225, 225) : Color.WHITE); g.fillRect(cx, cy, 10, 10);
        }
        g.setColor(Color.DARK_GRAY); g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13)); g.drawString(label, x + 5, y + 20);
        double scale = Math.min(330.0 / image.getWidth(), 340.0 / image.getHeight());
        int w = (int) (image.getWidth() * scale), h = (int) (image.getHeight() * scale);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, x + (340 - w) / 2, y + 30 + (350 - h) / 2, w, h, null);
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private HttpResponse<byte[]> upload(byte[] image, String method) throws Exception {
        var body = new ByteArrayOutputStream();
        body.write("--samples\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sample.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        body.write(image); body.write("\r\n--samples--\r\n".getBytes(StandardCharsets.UTF_8));
        return client.send(HttpRequest.newBuilder(uri("/api/segmentations?method=" + method)).timeout(Duration.ofMinutes(2))
                .header("Content-Type", "multipart/form-data; boundary=samples").POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
}
