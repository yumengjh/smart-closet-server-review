package com.smartcloset.api;

import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Fixed, visually screened real photos. Reference polygons are used only after inference. */
@EnabledIfSystemProperty(named = "closet.round2", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "closet.storage-dir=target/round2-run")
class FlatLayComparisonTest {
    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private final Path root = Path.of("reports/round2");
    record Score(double iou, double retained, double precision) {}
    record Outcome(String sample, String title, String method, int status, long httpMillis, Score referenceScore, JsonNode response) {}

    @Test void compareFixedCompleteGarments() throws Exception {
        var samples = json.readTree(Path.of("samples/round2/manifest.json").toFile());
        assertTrue(samples.size() >= 6, "Need several screened complete garments");
        Files.createDirectories(root);
        var outcomes = new ArrayList<Outcome>();
        var errors = new ArrayList<String>();
        var html = new StringBuilder("""
                <!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>完整单件衣物 · 第二轮对比</title><style>
                body{font:16px/1.65 system-ui,sans-serif;max-width:1440px;margin:36px auto;padding:0 24px;background:#f5f5f3;color:#202622}
                h1{margin-bottom:8px}p{color:#505952}section{margin:36px 0}figure{margin:0;min-width:0}
                img{width:100%;height:330px;object-fit:contain;background:repeating-conic-gradient(#ddd 0% 25%,#fff 0% 50%) 0/20px 20px}
                .grid{display:grid;grid-template-columns:repeat(3,1fr);gap:16px}figcaption{font-size:14px;overflow-wrap:anywhere}a{color:#235c48}
                .note{background:white;padding:18px 24px;border-radius:12px}pre{white-space:pre-wrap}summary{cursor:pointer}@media(max-width:800px){.grid{grid-template-columns:1fr}}
                </style><h1>完整单件衣物 · 第二轮对比</h1>
                <div class=note><p>这些原图在运行算法前已逐张检查并固定：衣物完整入镜、单件、没有其他物体遮挡。自然褶皱、影子和周围的家庭环境保留。</p>
                <p>沿用原来的两种自动处理方式与参数，没有把参考轮廓输入算法。点击图片可查看原尺寸。格子表示透明背景，接口成功不代表抠图合格。</p>
                <p>衣物保留比例、前景准确比例与轮廓重合度 IoU，均相对于 aRTF 提供的半自动参考标注计算；边缘与孔洞仍需目视检查。这是一小批选定样本，不是通用准确率。</p>
                <p><a href="REVIEW.md">逐图检查结论</a> · <a href="../../samples/round2/SOURCES.md">来源与选图记录</a> · <a href="../latest/index.html">保留的第一轮结果</a></p></div>
                """);
        BufferedImage overview = new BufferedImage(1440, samples.size() * 330, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = overview.createGraphics();
        graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, overview.getWidth(), overview.getHeight());
        int row = 0;
        try {
            for (JsonNode sample : samples) {
                String id = sample.path("id").asText(), title = sample.path("title").asText();
                byte[] input = Files.readAllBytes(Path.of("samples/round2", id + ".png"));
                assertEquals(sample.path("sha256").asText(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input)), "Input changed after selection");
                BufferedImage original = ImageIO.read(new ByteArrayInputStream(input));
                assertEquals(sample.path("width").asInt(), original.getWidth());
                assertEquals(sample.path("height").asInt(), original.getHeight());
                BufferedImage reference = reference(sample);
                Path dir = root.resolve(id); Files.createDirectories(dir);
                Files.write(dir.resolve("input.png"), input);
                ImageIO.write(reference, "png", dir.resolve("reference.png").toFile());
                html.append("<section><h2>").append(escape(title)).append("</h2><p>").append(escape(sample.path("observation").asText())).append("</p><div class=grid>");
                html.append(figure(id + "/input.png", "原图 · " + id));
                cell(graphics, original, 0, row, title + " / 原图");
                int col = 1;
                for (String method : new String[]{"GRABCUT", "U2NET"}) {
                    long start = System.nanoTime();
                    var response = upload(input, method);
                    long elapsed = (System.nanoTime() - start) / 1_000_000;
                    var body = json.readTree(response.body());
                    Path output = dir.resolve(method); Files.createDirectories(output);
                    Files.write(output.resolve("response.json"), response.body());
                    Score score = null;
                    if (response.statusCode() == 201) {
                        BufferedImage cutout = null;
                        for (String field : new String[]{"cutoutUrl", "maskUrl", "croppedUrl"}) {
                            var download = client.send(HttpRequest.newBuilder(uri(body.path(field).asText())).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                            assertEquals(200, download.statusCode());
                            Files.write(output.resolve(field.replace("Url", ".png")), download.body());
                            if (field.equals("cutoutUrl")) cutout = ImageIO.read(new ByteArrayInputStream(download.body()));
                        }
                        assertNotNull(cutout);
                        assertEquals(original.getWidth(), cutout.getWidth()); assertEquals(original.getHeight(), cutout.getHeight());
                        score = score(cutout, reference);
                        String label = method + " · " + body.path("processingMillis").asLong() + " ms<br>参考衣物保留 " + percent(score.retained())
                                + " · 前景准确 " + percent(score.precision()) + " · IoU " + String.format(java.util.Locale.ROOT, "%.3f", score.iou());
                        html.append(figure(id + "/" + method + "/cutout.png", label));
                        cell(graphics, cutout, col, row, method + " / IoU " + String.format(java.util.Locale.ROOT, "%.3f", score.iou()));
                    } else {
                        html.append("<figure><pre>").append(method).append(" HTTP ").append(response.statusCode()).append("\n").append(escape(body.toString())).append("</pre></figure>");
                        if (response.statusCode() != 422) errors.add(id + " " + method + " HTTP " + response.statusCode());
                    }
                    outcomes.add(new Outcome(id, title, method, response.statusCode(), elapsed, score, body));
                    col++;
                }
                html.append("</div><details><summary>参考标注与机器结果</summary><p><a href=\"").append(id)
                        .append("/reference.png\">参考蒙版</a> · <a href=\"").append(id).append("/GRABCUT/response.json\">GrabCut 返回值</a> · <a href=\"")
                        .append(id).append("/U2NET/response.json\">U2Net 返回值</a></p></details></section>");
                ImageIO.write(overview.getSubimage(0, row * 330, 1440, 330), "png", dir.resolve("comparison.png").toFile());
                row++;
            }
        } finally { graphics.dispose(); }
        Files.writeString(root.resolve("index.html"), html.append("</html>").toString());
        ImageIO.write(overview, "png", root.resolve("comparison.png").toFile());
        json.writerWithDefaultPrettyPrinter().writeValue(root.resolve("results.json").toFile(), outcomes);
        assertEquals(samples.size() * 2, outcomes.size());
        assertTrue(errors.isEmpty(), errors.toString());
    }

    private static BufferedImage reference(JsonNode sample) {
        BufferedImage mask = new BufferedImage(sample.path("width").asInt(), sample.path("height").asInt(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = mask.createGraphics();
        try {
            g.setColor(Color.WHITE);
            for (JsonNode polygon : sample.path("polygons")) {
                Path2D path = new Path2D.Double();
                path.moveTo(polygon.get(0).get(0).asDouble(), polygon.get(0).get(1).asDouble());
                for (int i = 1; i < polygon.size(); i++) path.lineTo(polygon.get(i).get(0).asDouble(), polygon.get(i).get(1).asDouble());
                path.closePath(); g.fill(path);
            }
        } finally { g.dispose(); }
        return mask;
    }

    private static Score score(BufferedImage predicted, BufferedImage reference) {
        long intersection = 0, prediction = 0, truth = 0;
        for (int y = 0; y < predicted.getHeight(); y++) for (int x = 0; x < predicted.getWidth(); x++) {
            boolean p = (predicted.getRGB(x, y) >>> 24) >= 128;
            boolean t = reference.getRaster().getSample(x, y, 0) >= 128;
            if (p) prediction++; if (t) truth++; if (p && t) intersection++;
        }
        if (truth == 0) throw new IllegalArgumentException("Empty reference");
        return new Score((double) intersection / (prediction + truth - intersection), (double) intersection / truth,
                prediction == 0 ? 0 : (double) intersection / prediction);
    }

    @Test void referenceMetricsCountMissingAndExtraPixels() {
        var truth = new BufferedImage(4, 4, BufferedImage.TYPE_BYTE_GRAY);
        truth.getRaster().setSample(1, 1, 0, 255); truth.getRaster().setSample(2, 1, 0, 255);
        var prediction = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        prediction.setRGB(1, 1, 0xffffffff); prediction.setRGB(1, 2, 0xffffffff);
        var score = score(prediction, truth);
        assertEquals(1.0 / 3, score.iou(), 1e-9); assertEquals(.5, score.retained()); assertEquals(.5, score.precision());
    }

    private static String percent(double value) { return String.format(java.util.Locale.ROOT, "%.1f%%", value * 100); }
    private static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
    private static String figure(String path, String caption) { return "<figure><a href=\"" + path + "\"><img src=\"" + path + "\"></a><figcaption>" + caption + "</figcaption></figure>"; }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }

    private HttpResponse<byte[]> upload(byte[] image, String method) throws Exception {
        var body = new ByteArrayOutputStream();
        body.write("--flatlay\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sample.png\"\r\nContent-Type: image/png\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        body.write(image); body.write("\r\n--flatlay--\r\n".getBytes(StandardCharsets.UTF_8));
        return client.send(HttpRequest.newBuilder(uri("/api/segmentations?method=" + method)).timeout(Duration.ofMinutes(2))
                .header("Content-Type", "multipart/form-data; boundary=flatlay").POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private void cell(Graphics2D g, BufferedImage image, int col, int row, String label) {
        int x = col * 480, y = row * 330;
        for (int cy = y + 36; cy < y + 320; cy += 10) for (int cx = x; cx < x + 470; cx += 10) {
            g.setColor((cx / 10 + cy / 10) % 2 == 0 ? new Color(225, 225, 225) : Color.WHITE); g.fillRect(cx, cy, 10, 10);
        }
        g.setColor(Color.DARK_GRAY); g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 17)); g.drawString(label, x + 6, y + 26);
        double scale = Math.min(460.0 / image.getWidth(), 274.0 / image.getHeight());
        int w = (int) (scale * image.getWidth()), h = (int) (scale * image.getHeight());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, x + (470 - w) / 2, y + 36 + (284 - h) / 2, w, h, null);
    }
}
