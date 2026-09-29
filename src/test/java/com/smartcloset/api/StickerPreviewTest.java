package com.smartcloset.api;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.ArrayList;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Render existing reviewed cutouts via HTTP. No segmentation model is run or required. */
@EnabledIfSystemProperty(named = "closet.stickers", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "closet.model-path=target/absent-sticker-preview-model.onnx")
class StickerPreviewTest {
    @TempDir static Path storage;
    @LocalServerPort int port;
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) {
        registry.add("closet.storage-dir", () -> storage.toString());
    }
    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void renderReviewedCutoutsAndExportLightAndDarkPreviews() throws Exception {
        Path root = Path.of("reports/stickers"); Files.createDirectories(root);
        var results = new ArrayList<Object>();
        var html = new StringBuilder("""
                <!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>衣物贴纸 · bold-v2</title><style>
                body{font:16px/1.65 system-ui,sans-serif;max-width:1400px;margin:32px auto;padding:0 24px;background:#f4f4f1;color:#28322d}
                section{margin:36px 0}.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:16px}figure{margin:0}
                img{width:100%;height:360px;object-fit:contain;border-radius:18px;background:#f0ede6}.dark{background:#26323e}
                a{color:#235c48}figcaption{font-size:14px}@media(max-width:800px){.grid{grid-template-columns:1fr}}
                </style><h1>衣物贴纸 · bold-v2</h1><p>固定 1024×1024 透明画布，按主体等比例居中，32 像素暖白外描边；阴影不透明度 32%，向右 8、向下 18 像素。浅/深背景只用于这里的展示，没有写入 PNG。</p>
                <p>从第二轮保留的 U²-Net 抠图独立生成，不再次推理。原来的褶皱、阴影及分割误差也会保留；贴纸化不修复缺失衣物。</p>
                <p><a href="README.md">接口与检查结论</a> · <a href="../round2/index.html">分割对比</a> · <a href="../../samples/round2/SOURCES.md">原照片来源与 CC BY 4.0 许可</a></p>
                """);
        int count = 0;
        for (var sample : json.readTree(Path.of("samples/round2/manifest.json").toFile())) {
            String name = sample.path("id").asText();
            Path reviewed = Path.of("reports/round2", name, "U2NET");
            byte[] cutout = Files.readAllBytes(reviewed.resolve("cutout.png"));
            var legacy = json.readTree(reviewed.resolve("response.json").toFile());
            String id = legacy.path("id").asText();
            // Import the two files this post-processing stage needs from an existing segmentation.
            Path saved = storage.resolve(id); Files.createDirectories(saved);
            Files.write(saved.resolve("cutout.png"), cutout);
            Files.copy(reviewed.resolve("response.json"), saved.resolve("result.json"));
            var request = HttpRequest.newBuilder(uri("/api/segmentations/" + id + "/sticker"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(201, response.statusCode(), new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
            var result = json.readTree(response.body());
            var download = client.send(HttpRequest.newBuilder(uri(result.path("imageUrl").asText())).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, download.statusCode());
            Path dir = root.resolve(name); Files.createDirectories(dir);
            Files.write(dir.resolve("sticker.png"), download.body());
            Files.write(dir.resolve("response.json"), response.body());
            var sticker = ImageIO.read(new ByteArrayInputStream(download.body()));
            assertEquals(1024, sticker.getWidth()); assertEquals(1024, sticker.getHeight());
            assertTrue(sticker.getColorModel().hasAlpha());
            assertEquals(0, sticker.getRGB(0, 0) >>> 24);
            var cached = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, cached.statusCode()); assertEquals(result, json.readTree(cached.body()));
            assertArrayEquals(cutout, Files.readAllBytes(saved.resolve("cutout.png")));
            BufferedImage comparison = new BufferedImage(1440, 420, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = comparison.createGraphics();
            try {
                g.setColor(Color.WHITE); g.fillRect(0, 0, 1440, 420);
                cell(g, ImageIO.read(new ByteArrayInputStream(cutout)), 0, new Color(240, 237, 230), name + " / 原抠图");
                cell(g, sticker, 1, new Color(240, 237, 230), "贴纸 / 浅色背景");
                cell(g, sticker, 2, new Color(38, 50, 62), "贴纸 / 深色背景");
            } finally { g.dispose(); }
            ImageIO.write(comparison, "png", dir.resolve("comparison.png").toFile());
            html.append("<section><h2>").append(sample.path("title").asText()).append("</h2><div class=grid>")
                    .append("<figure><img src=\"../round2/").append(name).append("/U2NET/cutout.png\"><figcaption>原抠图</figcaption></figure>")
                    .append("<figure><a href=\"").append(name).append("/sticker.png\"><img src=\"").append(name).append("/sticker.png\"></a><figcaption>贴纸 · 浅色背景 · 点击下载透明 PNG</figcaption></figure>")
                    .append("<figure><img class=dark src=\"").append(name).append("/sticker.png\"><figcaption>同一张贴纸 · 深色背景</figcaption></figure></div></section>");
            results.add(java.util.Map.of("sample", name, "result", result)); count++;
        }
        assertEquals(12, count);
        Files.writeString(root.resolve("index.html"), html.append("</html>").toString());
        json.writerWithDefaultPrettyPrinter().writeValue(root.resolve("results.json").toFile(), results);
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private void cell(Graphics2D g, BufferedImage image, int col, Color background, String label) {
        int x = col * 480;
        g.setColor(background); g.fillRect(x, 40, 470, 370);
        g.setColor(Color.DARK_GRAY); g.setFont(new Font("SansSerif", Font.PLAIN, 19)); g.drawString(label, x + 8, 28);
        double scale = Math.min(460.0 / image.getWidth(), 360.0 / image.getHeight());
        int w = (int) Math.round(image.getWidth() * scale), h = (int) Math.round(image.getHeight() * scale);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(image, x + (470 - w) / 2, 40 + (370 - h) / 2, w, h, null);
    }
}
