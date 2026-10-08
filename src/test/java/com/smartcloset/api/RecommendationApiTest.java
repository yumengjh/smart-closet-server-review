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
        properties = {"spring.config.import=optional:file:./target/no-local-secrets.yml", "closet.model-path=target/absent-recommend-model.onnx", "closet.embedding-model-path=target/absent-embedding.onnx"})
class RecommendationApiTest {
    @TempDir static Path storage;
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) { registry.add("closet.storage-dir", () -> storage.toString()); }

    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired ClosetAccountService accounts;
    @Autowired com.smartcloset.recommend.TemplateCatalog templates;
    @Autowired com.smartcloset.recommend.GarmentColors colors;
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

    private Session signIn(String username) throws Exception { return signIn(username, "USER"); }
    private Session signIn(String username, String role) throws Exception {
        accounts.create(username, "recommend-test-password", role);
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

    @Test void explainSaysWhyAGarmentWasNotRecommendedOrWhereItRanks() throws Exception {
        String owner = "rec-explain-" + UUID.randomUUID().toString().substring(0, 8);
        var me = signIn(owner); var ids = seed(owner);
        var view = me.ok("POST", "/api/recommendations", Map.of("occasion", "COMMUTE", "temperatureC", 18, "avoidColors", List.of("红色")));
        String runId = view.path("runId").asText();
        var excluded = me.ok("GET", "/api/recommendations/" + runId + "/explain?garmentId=" + ids.get("red"), null);
        assertEquals("EXCLUDED", excluded.path("status").asText());
        assertTrue(excluded.path("summary").get(0).asText().contains("避开红色"), excluded.toString());
        var top = view.path("outfits").get(0);
        var outfit = me.ok("GET", "/api/recommendations/" + runId + "/explain?outfitKey=" + top.path("key").asText(), null);
        assertEquals("SHOWN", outfit.path("status").asText());
        assertTrue(outfit.path("summary").get(0).asText().contains("第 1 套"), outfit.toString());
        assertTrue(outfit.path("outfit").path("rules").size() >= 15, "every outfit rule is listed with hit or pass");
        var shownGarment = me.ok("GET", "/api/recommendations/" + runId + "/explain?garmentId=" + top.path("garments").get(0).path("id").asText(), null);
        assertEquals("SHOWN", shownGarment.path("status").asText());
        // A garment that made it into the pool but not on screen gets its best outfit, the gap and the biggest deductions.
        var notShown = ids.values().stream().filter(id -> !id.equals(ids.get("red")) && !contains(view.path("outfits"), id)).findFirst();
        if (notShown.isPresent()) {
            var why = me.ok("GET", "/api/recommendations/" + runId + "/explain?garmentId=" + notShown.get(), null);
            assertTrue(Set.of("RANKED", "CUT", "NO_VALID_OUTFIT").contains(why.path("status").asText()), why.toString());
            assertFalse(why.path("summary").isEmpty());
        }
        var other = signIn("rec-explain-other-" + UUID.randomUUID().toString().substring(0, 8));
        assertEquals(410, other.send("GET", "/api/recommendations/" + runId + "/explain?garmentId=" + ids.get("red"), null, true).statusCode());
    }

    @Test void adminTemplateReviewTakesEffectWithoutTouchingTheFile() throws Exception {
        String owner = "rec-tpl-" + UUID.randomUUID().toString().substring(0, 8);
        var me = signIn(owner); seed(owner);
        assertEquals(403, me.send("GET", "/api/admin/outfit-templates", null, true).statusCode());
        var admin = signIn("rec-admin-" + UUID.randomUUID().toString().substring(0, 8), "ADMIN");
        try {
            var list = admin.ok("GET", "/api/admin/outfit-templates", null);
            assertTrue(list.path("items").size() >= 50);
            int active = list.path("active").asInt();
            admin.ok("POST", "/api/admin/outfit-templates/review", Map.of("ids", List.of("white-shirt-black-trousers", "commute-shirt-loafer"), "reviewStatus", "APPROVED"));
            var edited = admin.ok("PATCH", "/api/admin/outfit-templates/commute-shirt-loafer", Map.of("bonus", 5));
            assertEquals(5, edited.path("bonus").asDouble());
            assertEquals(400, admin.send("PATCH", "/api/admin/outfit-templates/commute-shirt-loafer", Map.of("bonus", 9), true).statusCode());
            assertEquals(active + 2, admin.ok("GET", "/api/admin/outfit-templates", null).path("active").asInt());
            var view = me.ok("POST", "/api/recommendations", Map.of("occasion", "COMMUTE", "temperatureC", 18));
            boolean classic = false;
            for (var o : view.path("outfits")) for (var h : o.path("highlights")) classic |= h.asText().startsWith("经典搭法：");
            assertTrue(classic, view.path("outfits").toString());
        } finally {
            db.update("DELETE FROM outfit_template_review");
            templates.reload();
        }
    }

    @Test void semanticStrictnessIsPerAccountAndBounded() throws Exception {
        var me = signIn("rec-strict-" + UUID.randomUUID().toString().substring(0, 8));
        var profile = me.ok("GET", "/api/style-profile", null);
        assertEquals(50, profile.path("strictness").path("level").asInt());
        assertEquals(0.35, profile.path("strictness").path("threshold").asDouble(), 1e-9);
        assertEquals(5, profile.path("strictness").path("levels").size());
        me.ok("PATCH", "/api/style-profile", Map.of("semanticStrictness", 80));
        var strict = me.ok("GET", "/api/style-profile", null).path("strictness");
        assertEquals(80, strict.path("level").asInt());
        assertTrue(strict.path("threshold").asDouble() > 0.35);
        var reset = new HashMap<String, Object>(); reset.put("semanticStrictness", null);
        me.ok("PATCH", "/api/style-profile", reset);
        assertEquals(50, me.ok("GET", "/api/style-profile", null).path("strictness").path("level").asInt());
        assertEquals(400, me.send("PATCH", "/api/style-profile", Map.of("semanticStrictness", 150), true).statusCode());
        assertEquals(400, me.send("PATCH", "/api/style-profile", Map.of("styleMixing", "SOMETIMES"), true).statusCode());
        assertEquals("AUTO", me.ok("PATCH", "/api/style-profile", Map.of("styleMixing", "PENALTY")).path("styleMixing").asText(), "the pre-P3 name maps to AUTO");
    }

    @Test void wearSummaryCountsAndDatesPerGarment() throws Exception {
        String owner = "rec-wear-" + UUID.randomUUID().toString().substring(0, 8);
        var me = signIn(owner); var ids = seed(owner);
        assertEquals(0, me.ok("GET", "/api/wear-logs/garments/" + ids.get("shirt"), null).path("count").asInt());
        me.ok("POST", "/api/wear-logs", Map.of("garmentIds", List.of(ids.get("shirt"), ids.get("trousers"))));
        var summary = me.ok("GET", "/api/wear-logs/garments/" + ids.get("shirt"), null);
        assertEquals(1, summary.path("count").asInt());
        assertEquals(0, summary.path("daysSince").asInt());
        var other = signIn("rec-wear-other-" + UUID.randomUUID().toString().substring(0, 8));
        assertEquals(404, other.send("GET", "/api/wear-logs/garments/" + ids.get("shirt"), null, true).statusCode());
    }

    @Test void invalidIntentIsRejected() throws Exception {
        var me = signIn("rec-bad-" + UUID.randomUUID().toString().substring(0, 8));
        assertEquals(400, me.send("POST", "/api/recommendations", Map.of("temperatureC", 99), true).statusCode());
        assertEquals(400, me.send("POST", "/api/recommendations", Map.of("count", 9), true).statusCode());
    }

    private static boolean contains(JsonNode outfits, String garmentId) {
        for (var o : outfits) for (var g : o.path("garments")) if (g.path("id").asText().equals(garmentId)) return true;
        return false;
    }

    @Test void twoExplicitDislikesPauseTheGarmentUntilRestored() throws Exception {
        String owner = "rec-pause-" + UUID.randomUUID().toString().substring(0, 8);
        var me = signIn(owner); var ids = seed(owner);
        String sneakers = ids.get("sneakers");
        var forced = me.ok("POST", "/api/recommendations", Map.of("occasion", "DAILY", "requiredGarmentIds", List.of(sneakers)));
        var logs = new ArrayList<String>(); for (var o : forced.path("outfits")) logs.add(o.path("logId").asText());
        assertTrue(logs.size() >= 2, forced.toString());
        // A run fetched before the feedback: its "换一批" must honour the feedback given afterwards.
        var earlier = me.ok("POST", "/api/recommendations", Map.of("occasion", "DAILY", "count", 1));

        me.ok("POST", "/api/recommendations/logs/" + logs.get(0) + "/feedback", Map.of("verdict", "DOWN", "reasons", List.of("DISLIKE_ITEM"), "garmentId", sneakers));
        var once = me.ok("GET", "/api/style-profile", null);
        assertTrue(once.path("statements").toString().contains("你表示过不喜欢"), "the first explicit dislike shows up at once: " + once);
        me.ok("POST", "/api/recommendations/logs/" + logs.get(1) + "/feedback", Map.of("verdict", "DOWN", "reasons", List.of("DISLIKE_ITEM"), "garmentId", sneakers));

        var profile = me.ok("GET", "/api/style-profile", null);
        assertTrue(profile.path("statements").toString().contains("pause:" + sneakers), profile.toString());
        for (int i = 0; i < 3; i++) {
            var view = me.ok("POST", "/api/recommendations", Map.of("occasion", "DAILY"));
            assertFalse(contains(view.path("outfits"), sneakers), view.toString());
            assertTrue(view.path("limits").toString().contains("已暂停推荐"), view.toString());
            assertFalse(contains(me.ok("GET", "/api/recommendations/" + view.path("runId").asText() + "/more?count=5", null).path("outfits"), sneakers));
        }
        assertFalse(contains(me.ok("GET", "/api/recommendations/" + earlier.path("runId").asText() + "/more?count=5", null).path("outfits"), sneakers));
        var stillAllowed = me.ok("POST", "/api/recommendations", Map.of("occasion", "DAILY", "requiredGarmentIds", List.of(sneakers)));
        assertFalse(stillAllowed.path("outfits").isEmpty());
        for (var o : stillAllowed.path("outfits")) assertTrue(contains(json.createArrayNode().add(o), sneakers));

        me.ok("PATCH", "/api/style-profile", Map.of("unblock", "pause:" + sneakers));
        assertFalse(me.ok("GET", "/api/style-profile", null).path("statements").toString().contains("pause:" + sneakers));
        assertFalse(me.ok("POST", "/api/recommendations", Map.of("occasion", "DAILY")).path("limits").toString().contains("已暂停推荐"));
    }

    @Test void sexIsReadOnlyForExclusionAndNeverEchoedAsLimit() throws Exception {
        String owner = "rec-sex-" + UUID.randomUUID().toString().substring(0, 8);
        var me = signIn(owner); var ids = seed(owner);
        String heels = garment(owner, Map.of("category", List.of("高跟鞋"), "primaryColor", List.of("红色"), "styles", List.of("正式")));
        db.update("MERGE INTO wearer_profile(owner_username,revision,sex,measurements_json,preferences_json,updated_at_ms) KEY(owner_username) VALUES(?,1,'MALE','{}','{}',?)", owner, System.currentTimeMillis());
        var view = me.ok("POST", "/api/recommendations", Map.of("occasion", "DATE"));
        assertFalse(contains(view.path("outfits"), heels));
        assertFalse(contains(view.path("outfits"), ids.get("red")));
        assertTrue(view.path("notes").toString().contains("女款"), view.toString());
        assertFalse(view.path("limits").toString().contains("女款"));
    }

    @Test void wardrobeSearchIsAccountScopedAndDegradesWithoutTheModel() throws Exception {
        String owner = "rec-search-" + UUID.randomUUID().toString().substring(0, 8);
        var me = signIn(owner); var ids = seed(owner);
        var white = me.ok("GET", "/api/garments/search?q=" + URLEncoder.encode("白色上衣", "UTF-8"), null);
        assertEquals(1, white.path("total").asInt(), white.toString());
        assertEquals(ids.get("shirt"), white.path("items").get(0).path("garment").path("id").asText());
        assertEquals("白色", white.path("parsed").path("filters").path("primaryColor").get(0).asText());
        var red = me.ok("GET", "/api/garments/search?q=" + URLEncoder.encode("红色上衣", "UTF-8"), null);
        assertEquals(0, red.path("total").asInt());
        assertFalse(red.path("emptyHint").asText().isBlank());
        var described = me.ok("GET", "/api/garments/search?q=" + URLEncoder.encode("适合海边的", "UTF-8"), null);
        assertFalse(described.path("semantic").path("available").asBoolean());
        assertTrue(described.path("notice").asText().contains("关键词"));
        var other = signIn("rec-search-other-" + UUID.randomUUID().toString().substring(0, 8));
        assertEquals(0, other.ok("GET", "/api/garments/search?q=" + URLEncoder.encode("白色上衣", "UTF-8"), null).path("total").asInt());
        assertTrue(Set.of(401, 403).contains(new Session().send("GET", "/api/garments/search?q=x", null, false).statusCode()));
    }

    @Test void dailyInspirationFillsApprovedTemplatesAndIsLoggedWithTheTemplate() throws Exception {
        String owner = "rec-insp-" + UUID.randomUUID().toString().substring(0, 8);
        var me = signIn(owner); var ids = seed(owner);
        db.update("DELETE FROM outfit_template_review"); templates.reload();
        var empty = me.ok("GET", "/api/recommendations/inspiration", null);
        assertEquals(0, empty.path("approvedTemplates").asInt());
        assertTrue(empty.path("view").path("emptyReason").asText().contains("还没有审核通过"), empty.toString());
        try {
            db.update("INSERT INTO outfit_template_review(template_id,review_status,enabled,bonus,updated_by,updated_at_ms) VALUES('white-shirt-black-trousers','APPROVED',TRUE,NULL,'test',0)");
            templates.reload();
            var view = me.ok("GET", "/api/recommendations/inspiration?temperatureC=18", null);
            var outfits = view.path("view").path("outfits");
            assertEquals(1, outfits.size(), view.toString());
            var garments = new ArrayList<String>(); for (var g : outfits.get(0).path("garments")) garments.add(g.path("id").asText());
            assertTrue(garments.containsAll(List.of(ids.get("shirt"), ids.get("trousers"))));
            assertTrue(outfits.get(0).path("highlights").get(0).asText().startsWith("经典搭法："));
            assertEquals("white-shirt-black-trousers", view.path("ideas").get(0).path("templateId").asText());
            assertEquals("white-shirt-black-trousers", db.queryForObject("SELECT template_id FROM recommendation_log WHERE owner_username=? AND source='INSPIRATION'", String.class, owner));
            me.ok("POST", "/api/recommendations/logs/" + outfits.get(0).path("logId").asText() + "/feedback", Map.of("verdict", "UP"));
        } finally { db.update("DELETE FROM outfit_template_review"); templates.reload(); }
    }

    @Test void displayImagesAreMeasuredLocallyAndRemeasuredWhenTheImageChanges() throws Exception {
        String owner = "rec-color-" + UUID.randomUUID().toString().substring(0, 8);
        signIn(owner);
        String id = UUID.randomUUID().toString(), job = UUID.randomUUID().toString(), asset = UUID.randomUUID().toString(); long now = System.currentTimeMillis();
        var image = new java.awt.image.BufferedImage(40, 40, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 5; y < 35; y++) for (int x = 5; x < 35; x++) image.setRGB(x, y, new java.awt.Color(36, 52, 94).getRGB());
        var out = new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(image, "png", out); byte[] png = out.toByteArray();
        var dir = storage.resolve("workflows").resolve(job); java.nio.file.Files.createDirectories(dir); java.nio.file.Files.write(dir.resolve("input.png"), png);
        String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(png));
        db.update("INSERT INTO workflow_job(id,fingerprint,status,created_at_ms,updated_at_ms,request_json,requires_review,version,owner_username) VALUES(?,?,'SUCCEEDED',?,?,'{}',0,0,?)", job, job, now, now, owner);
        db.update("INSERT INTO image_asset(id,job_id,file_name,sha256,byte_size,width,height,kind,review_status,created_at_ms) VALUES(?,?,'input.png',?,?,40,40,'INPUT','APPROVED',?)", asset, job, sha, png.length, now);
        db.update("INSERT INTO garment(id,source_asset_id,display_asset_id,source_origin,overrides_json,display_user_selected,revision,created_at_ms,updated_at_ms,owner_username,closet_status) VALUES(?,?,?,'USER_UPLOAD',?,0,0,?,?,?,'ACTIVE')",
            id, asset, asset, json.writeValueAsString(Map.of("category", List.of("毛衣"), "primaryColor", List.of("藏蓝色"))), now, now, owner);
        assertTrue(colors.pendingOwners().containsKey(owner));
        assertEquals(1, colors.index(owner, 10));
        var palette = colors.palettes(owner).get(id);
        assertNotNull(palette); assertTrue(palette.getFirst().l() < 30, palette.toString());
        assertEquals(1.0, palette.getFirst().ratio(), 0.01, "the transparent margin is ignored");
        assertFalse(colors.pendingOwners().containsKey(owner));
        String other = UUID.randomUUID().toString();
        db.update("INSERT INTO image_asset(id,job_id,file_name,sha256,byte_size,width,height,kind,review_status,created_at_ms) VALUES(?,?,'step-1.png','x',1,40,40,'STICKER','APPROVED',?)", other, job, now);
        db.update("UPDATE garment SET display_asset_id=? WHERE id=?", other, id);
        assertTrue(colors.pendingOwners().containsKey(owner), "a new display image is measured again");
        assertTrue(colors.palettes(owner).isEmpty(), "a palette measured on an old display image is not used");
    }

    @Test void statsCountAdoptionLikesSwapsAndCostWithoutModelCalls() throws Exception {
        String owner = "rec-stats-" + UUID.randomUUID().toString().substring(0, 8);
        var me = signIn(owner); seed(owner);
        var view = me.ok("POST", "/api/recommendations", Map.of("occasion", "COMMUTE", "temperatureC", 18));
        var outfits = view.path("outfits");
        assertEquals(3, outfits.size());
        me.ok("POST", "/api/recommendations/logs/" + outfits.get(0).path("logId").asText() + "/feedback", Map.of("verdict", "UP"));
        me.ok("POST", "/api/recommendations/logs/" + outfits.get(1).path("logId").asText() + "/feedback", Map.of("verdict", "DOWN", "reasons", List.of("COLOR_CLASH")));
        me.ok("POST", "/api/recommendations/events", Map.of("events", List.of(Map.of("kind", "OPEN_WORKSPACE", "logId", outfits.get(1).path("logId").asText()))));
        var worn = new ArrayList<String>(); for (var g : outfits.get(2).path("garments")) worn.add(g.path("id").asText());
        me.ok("POST", "/api/wear-logs", Map.of("garmentIds", worn));
        db.update("INSERT INTO model_usage(id,owner_username,feature,model,input_tokens,output_tokens,created_at_ms) VALUES(?,?,'AGENT','m',1200,80,?)", UUID.randomUUID().toString(), owner, System.currentTimeMillis());
        var stats = me.ok("GET", "/api/recommendations/stats?days=7", null);
        var effect = stats.path("effect");
        assertEquals(3, effect.path("shown").asInt());
        assertEquals(3, effect.path("adopted").asInt(), stats.toString());
        assertEquals(1.0, effect.path("adoptionRate").asDouble(), 0.001);
        assertEquals(0.5, effect.path("likeRate").asDouble(), 0.001);
        assertEquals(1, effect.path("wornWithin3Days").asInt());
        assertEquals(3, effect.path("bySource").path("PAGE").path("shown").asInt());
        assertEquals(1200, stats.path("cost").path("inputTokens").asLong());
        assertEquals(3, stats.path("cost").path("zeroModelRecommendations").asLong());
        assertEquals(400, me.send("GET", "/api/recommendations/stats?days=0", null, true).statusCode());
        var other = signIn("rec-stats-o-" + UUID.randomUUID().toString().substring(0, 8));
        assertEquals(0, other.ok("GET", "/api/recommendations/stats", null).path("effect").path("shown").asInt());
    }
}
