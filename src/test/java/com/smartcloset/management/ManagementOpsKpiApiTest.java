package com.smartcloset.management;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcloset.security.ClosetAccountService;
import com.smartcloset.security.QuotaPolicy;
import java.nio.file.Path;
import java.time.LocalDate;
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

/** Management API 1.1 (design 管理接口二期): operations, KPIs, big-screen snapshot, exports and template review. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=optional:file:./target/no-local-secrets.yml", "closet.model-path=target/absent.onnx",
        "closet.embedding-model-path=target/absent-embedding.onnx", "closet.agent.runtime-url=http://127.0.0.1:9",
        "closet.management.enabled=true", "closet.management.auth.keys[0].id=primary",
        "closet.management.auth.keys[0].secret=" + ManagementOpsKpiApiTest.SECRET,
        "closet.management.insights-cache-ttl=0s", "closet.management.dashboard-cache-ttl=0s",
        "closet.management.export-salt=test-export-salt"})
class ManagementOpsKpiApiTest {
    static final String SECRET = "management-test-secret-0123456789abcdef";
    @TempDir static Path storage;
    @DynamicPropertySource static void config(DynamicPropertyRegistry p) { p.add("closet.storage-dir", () -> storage.toString()); }
    @LocalServerPort int port;
    @Autowired ClosetAccountService accounts;
    @Autowired JdbcTemplate db;
    final JsonMapper json = JsonMapper.builder().build();

    ManagementSigner signer() { return new ManagementSigner(port, "primary", SECRET); }
    JsonNode get(String path) throws Exception {
        var r = signer().send("GET", path, null); assertEquals(200, r.statusCode(), path + " → " + r.body()); return json.readTree(r.body());
    }
    int status(String method, String path, String body) throws Exception { return signer().send(method, path, body).statusCode(); }

    String user() { String name = "ok-" + UUID.randomUUID().toString().substring(0, 8); accounts.create(name, "OpsKpiTestPassword123!", "USER"); return name; }
    long at(LocalDate day, int hour) { return day.atTime(hour, 0).atZone(QuotaPolicy.ZONE).toInstant().toEpochMilli(); }

    String garment(String owner, long createdAt) {
        String id = UUID.randomUUID().toString(), job = UUID.randomUUID().toString(), asset = UUID.randomUUID().toString();
        db.update("INSERT INTO workflow_job(id,fingerprint,status,created_at_ms,updated_at_ms,request_json,requires_review,version,owner_username) VALUES(?,?,'SUCCEEDED',?,?,'{}',0,0,?)", job, UUID.randomUUID().toString(), createdAt, createdAt, owner);
        db.update("INSERT INTO image_asset(id,job_id,file_name,sha256,byte_size,width,height,kind,review_status,created_at_ms) VALUES(?,?,'input.png','fixture',1,120,160,'INPUT','APPROVED',?)", asset, job, createdAt);
        db.update("INSERT INTO garment(id,source_asset_id,display_asset_id,source_origin,overrides_json,display_user_selected,revision,created_at_ms,updated_at_ms,owner_username,closet_status) VALUES(?,?,?,'USER_UPLOAD',?,0,0,?,?,?,'ACTIVE')",
            id, asset, asset, "{\"category\":[\"衬衫\"],\"primaryColor\":[\"白色\"]}", createdAt, createdAt, owner);
        return id;
    }
    String log(String owner, String source, int rank, List<String> garments, long createdAt, String template) {
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO recommendation_log(id,run_id,owner_username,source,intent_json,outfit_key,garment_ids_json,rank_no,total_score,score_vector_json,tags_json,rule_version,created_at_ms,template_id) VALUES(?,?,?,?,'{}',?,?,?,80,'{}','[]','test',?,?)",
            id, UUID.randomUUID().toString(), owner, source, UUID.randomUUID().toString(), json.writeValueAsString(garments), rank, createdAt, template);
        return id;
    }
    void feedback(String log, String owner, String verdict, long createdAt) {
        db.update("INSERT INTO recommendation_feedback(id,log_id,owner_username,verdict,reasons_json,created_at_ms) VALUES(?,?,?,?,'[]',?)", UUID.randomUUID().toString(), log, owner, verdict, createdAt);
    }
    void signal(String owner, String kind, String log, long createdAt) {
        db.update("INSERT INTO style_signal(id,owner_username,kind,log_id,created_at_ms) VALUES(?,?,?,?,?)", UUID.randomUUID().toString(), owner, kind, log, createdAt);
    }
    String wear(String owner, List<String> garments, long createdAt) {
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO wear_log(id,owner_username,garment_ids_json,worn_on,created_at_ms) VALUES(?,?,?,?,?)", id, owner, json.writeValueAsString(garments),
            java.sql.Date.valueOf(java.time.Instant.ofEpochMilli(createdAt).atZone(QuotaPolicy.ZONE).toLocalDate()), createdAt);
        return id;
    }
    String usage(String owner, String feature, Long in, Long out, long createdAt) {
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO model_usage(id,owner_username,feature,model,input_tokens,output_tokens,created_at_ms) VALUES(?,?,?,'m',?,?,?)", id, owner, feature, in, out, createdAt);
        return id;
    }
    void task(String kind, String status, long createdAt, long updatedAt, String error) {
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO execution_task(id,business_key,owner_username,kind,business_id,stage,pool,priority,status,next_run_at_ms,created_at_ms,updated_at_ms,error_message) VALUES(?,?,NULL,?,?,0,'CPU',1,?,?,?,?,?)",
            id, "k-" + id, kind, UUID.randomUUID().toString(), status, createdAt, createdAt, updatedAt, error);
    }

    @Test void opsStatusExplainsWhatNeedsAttentionWithoutSecrets() throws Exception {
        long now = System.currentTimeMillis();
        task("OPS_TEST", "QUEUED", now - 3_600_000, now - 3_600_000, null);
        for (int i = 0; i < 10; i++) task("OPS_TEST", "FAILED", now - 60_000, now - 60_000, "上游超时 " + "x".repeat(400));
        var r = signer().send("GET", "/api/manage/v1/ops/status", null);
        assertEquals(200, r.statusCode(), r.body());
        var s = json.readTree(r.body());
        assertEquals("DEGRADED", s.path("status").asText());
        var codes = new HashSet<String>(); s.path("issues").forEach(i -> codes.add(i.path("code").asText()));
        assertTrue(codes.containsAll(Set.of("EMBEDDING_MODEL_MISSING", "SEGMENTATION_MODEL_MISSING", "AGENT_RUNTIME_UNREACHABLE", "QUEUE_BACKLOG", "TASK_FAILURES")), codes.toString());
        assertTrue(s.path("queue").path("failed24h").asInt() >= 10);
        assertTrue(s.path("queue").path("oldestQueuedAgeMs").asLong() >= 3_000_000);
        assertTrue(s.path("jvm").path("heapUsedBytes").asLong() > 0);
        assertTrue(s.path("storage").path("totalBytes").asLong() > 0);
        assertFalse(r.body().contains(SECRET));
        assertFalse(r.body().contains("http://"), "no provider or runtime addresses");

        // Task list: failed by default, error text cut, filter by kind, paging, bad status rejected.
        var tasks = get("/api/manage/v1/ops/tasks?kind=OPS_TEST&size=4");
        assertEquals(10, tasks.path("total").asInt());
        assertEquals(4, tasks.path("items").size());
        assertTrue(tasks.path("items").get(0).path("errorMessage").asText().length() <= 300);
        assertEquals(1, get("/api/manage/v1/ops/tasks?kind=OPS_TEST&status=QUEUED&status=RUNNING").path("total").asInt());
        assertEquals(400, status("GET", "/api/manage/v1/ops/tasks?status=EXPLODED", null));
    }

    @Test void recommendationKpiMatchesWearsPerUser() throws Exception {
        String a = user(), b = user();
        LocalDate day = LocalDate.of(2025, 3, 10);
        String g1 = garment(a, at(day, 8)), g2 = garment(a, at(day, 8)), g3 = garment(a, at(day, 8)), g4 = garment(a, at(day, 8)), g5 = garment(a, at(day, 8));
        String liked = log(a, "PAGE", 1, List.of(g1), at(day, 9), "white-tee-denim-sneaker");
        String opened = log(a, "AGENT", 2, List.of(g2), at(day, 9), null);
        log(a, "INSPIRATION", 1, List.of(g3, g4), at(day, 10), "white-tee-denim-sneaker");   // worn two days later
        log(a, "PAGE", 3, List.of(g5), at(day, 11), null);                                   // shown, never used
        String other = log(b, "PAGE", 1, List.of(g3, g4), at(day, 10), null);                // b never wore it; a's wear must not count for b
        feedback(liked, a, "UP", at(day, 9)); feedback(other, b, "DOWN", at(day, 12));
        signal(a, "OPEN_WORKSPACE", opened, at(day, 9)); signal(a, "SWAP_OUT", null, at(day, 9));
        wear(a, List.of(g3, g4), at(day.plusDays(2), 8));
        wear(a, List.of(g5), at(day.plusDays(5), 8));                                         // too late to count

        var k = get("/api/manage/v1/insights/recommendations?from=2025-03-10&to=2025-03-10");
        assertEquals(5, k.path("shown").asInt());
        assertEquals(3, k.path("adopted").asInt());
        assertEquals(0.6, k.path("adoptionRate").asDouble(), 1e-9);
        assertEquals(1, k.path("liked").asInt()); assertEquals(1, k.path("disliked").asInt()); assertEquals(0.5, k.path("likeRate").asDouble(), 1e-9);
        assertEquals(1, k.path("openedInWorkspace").asInt()); assertEquals(1, k.path("wornWithin3Days").asInt()); assertEquals(1, k.path("swaps").asInt());
        assertEquals(2, k.path("users").asInt());
        var sources = new HashMap<String, JsonNode>(); k.path("bySource").forEach(x -> sources.put(x.path("source").asText(), x));
        assertEquals(3, sources.get("PAGE").path("shown").asInt()); assertEquals(1, sources.get("PAGE").path("adopted").asInt());
        assertEquals(1, sources.get("INSPIRATION").path("adopted").asInt());
        assertEquals("white-tee-denim-sneaker", k.path("templates").get(0).path("template").asText());
        assertEquals(2, k.path("templates").get(0).path("adopted").asInt());
        assertTrue(k.path("definition").asText().contains("3 天"));
        var onlyB = get("/api/manage/v1/insights/recommendations?username=" + b + "&from=2025-03-10&to=2025-03-10");
        assertEquals(1, onlyB.path("shown").asInt()); assertEquals(0, onlyB.path("adopted").asInt());
        assertEquals(404, status("GET", "/api/manage/v1/insights/recommendations?username=nobody-here", null));
    }

    @Test void aiUsageIsSplitByFeatureInTokensOnly() throws Exception {
        String a = user(), b = user();
        LocalDate day = LocalDate.of(2025, 4, 2);
        usage(a, "AGENT", 1000L, 200L, at(day, 9)); usage(a, "AGENT", null, null, at(day, 10)); usage(b, "OOTD_EXTRACT", 1800L, 900L, at(day, 11));
        db.update("INSERT INTO ai_daily_quota(owner_username,quota_date,used) VALUES(?,?,3)", a, java.sql.Date.valueOf(day));
        db.update("INSERT INTO agent_daily_quota(owner_username,quota_date,used) VALUES(?,?,2)", a, java.sql.Date.valueOf(day));
        log(a, "PAGE", 1, List.of(), at(day, 9), null); log(a, "AGENT", 1, List.of(), at(day, 9), null);
        var ai = get("/api/manage/v1/insights/ai?from=2025-04-02&to=2025-04-02");
        var byFeature = new HashMap<String, JsonNode>(); ai.path("byFeature").forEach(f -> byFeature.put(f.path("feature").asText(), f));
        assertEquals(2, byFeature.get("AGENT").path("calls").asInt()); assertEquals(1, byFeature.get("AGENT").path("unknownUsage").asInt());
        assertEquals(1000, byFeature.get("AGENT").path("inputTokens").asInt());
        assertEquals(2700, byFeature.get("OOTD_EXTRACT").path("inputTokens").asInt() + byFeature.get("OOTD_EXTRACT").path("outputTokens").asInt());
        assertEquals(3900, ai.path("totals").path("inputTokens").asInt() + ai.path("totals").path("outputTokens").asInt());
        assertEquals(3, ai.path("imageUnits").asInt()); assertEquals(2, ai.path("agentMessages").asInt());
        assertEquals(0.5, ai.path("recommendations").path("zeroModelRatio").asDouble(), 1e-9);
        assertEquals(b, ai.path("topUsers").get(0).path("username").asText());
        assertTrue(get("/api/manage/v1/insights/ai?username=" + a + "&from=2025-04-02&to=2025-04-02").path("topUsers").isMissingNode());
    }

    @Test void newTrendMetricsCountDistinctUsersPerBucket() throws Exception {
        String a = user(), b = user();
        LocalDate monday = LocalDate.of(2025, 5, 5);
        garment(a, at(monday, 9)); log(a, "PAGE", 1, List.of(), at(monday.plusDays(1), 9), null);   // a active Mon and Tue
        usage(b, "AGENT", 10L, 5L, at(monday.plusDays(2), 9));                                       // b active Wed
        db.update("INSERT INTO agent_daily_quota(owner_username,quota_date,used) VALUES(?,?,1)", a, java.sql.Date.valueOf(monday.plusDays(8)));
        var daily = get("/api/manage/v1/insights/trends?from=2025-05-05&to=2025-05-13&metric=active_users&metric=recommendations_shown&metric=model_tokens");
        var series = new HashMap<String, JsonNode>(); daily.path("series").forEach(x -> series.put(x.path("metric").asText(), x.path("values")));
        assertEquals("[1,1,1,0,0,0,0,0,1]", series.get("active_users").toString());
        assertEquals(1, series.get("recommendations_shown").get(1).asInt());
        assertEquals(15, series.get("model_tokens").get(2).asInt());
        var weekly = get("/api/manage/v1/insights/trends?from=2025-05-05&to=2025-05-13&metric=active_users&granularity=week");
        assertEquals("[2,1]", weekly.path("series").get(0).path("values").toString(), "a counted once in the first week, not twice");
        var monthly = get("/api/manage/v1/insights/trends?from=2025-05-05&to=2025-06-02&metric=active_users&granularity=month");
        assertEquals("[\"2025-05-01\",\"2025-06-01\"]", monthly.path("buckets").toString());
        assertEquals(400, status("GET", "/api/manage/v1/insights/trends?metric=revenue", null));
    }

    @Test void dashboardSnapshotIsAggregateOnly() throws Exception {
        String a = user();
        long now = System.currentTimeMillis();
        String g = garment(a, now - 60_000);
        log(a, "PAGE", 1, List.of(g), now - 30_000, null);
        usage(a, "AGENT", 100L, 50L, now - 20_000);
        var r = signer().send("GET", "/api/manage/v1/dashboard/snapshot", null);
        assertEquals(200, r.statusCode(), r.body());
        var s = json.readTree(r.body());
        assertFalse(r.body().contains(a), "no user names on a wall display");
        assertTrue(s.path("tiles").path("activeToday").asInt() >= 1);
        assertTrue(s.path("tiles").path("garmentsToday").asInt() >= 1);
        assertTrue(s.path("tiles").path("modelTokensToday").asInt() >= 150);
        assertEquals(24, s.path("hourly").path("hours").size());
        var hourly = new HashMap<String, JsonNode>(); s.path("hourly").path("series").forEach(x -> hourly.put(x.path("metric").asText(), x.path("values")));
        assertEquals(24, hourly.get("active_users").size());
        assertTrue(hourly.get("recommendations_shown").get(23).asInt() >= 1, "the current hour is the last bucket");
        assertEquals(30, s.path("daily").path("days").size());
        assertTrue(s.path("refreshAfterMs").asLong() >= 30_000);
        assertTrue(s.path("ops").has("status"));
        // 1.2 big-screen sections: growth, weekday × hour heat, de-identified feed and extra distributions.
        assertEquals(30, s.path("growth").path("newUsers").size());
        assertTrue(s.path("growth").path("cumulative").get(29).asInt() >= 1);
        assertEquals(7 * 24, s.path("heatmap").path("cells").size());
        long heat = 0; for (var cell : s.path("heatmap").path("cells")) heat += cell.get(2).asLong();
        assertTrue(heat >= 1);
        var kinds = new java.util.HashSet<String>(); s.path("feed").forEach(x -> kinds.add(x.path("kind").asText()));
        assertTrue(kinds.containsAll(List.of("GARMENT_ADDED", "USER_REGISTERED")), kinds.toString());
        assertTrue(s.path("feed").size() <= 20);
        var d = s.path("distributions");
        assertEquals(5, d.path("closetSizes").size());
        assertTrue(d.has("styles") && d.has("genderFits") && d.has("registeredVia"));
        assertEquals("AGENT", d.path("aiFeatures30d").get(0).path("key").asText());
    }

    @Test void dailyExportJsonAndCsvAgree() throws Exception {
        String a = user();
        LocalDate day = LocalDate.of(2025, 6, 1);
        garment(a, at(day, 9)); wear(a, List.of(), at(day.plusDays(1), 9)); usage(a, "AGENT", 7L, 3L, at(day.plusDays(1), 10));
        var j = get("/api/manage/v1/exports/daily?from=2025-06-01&to=2025-06-03");
        assertEquals(3, j.path("rows").size());
        var second = j.path("rows").get(1);
        assertEquals("2025-06-02", second.path("date").asText());
        assertEquals(1, second.path("wear_logs").asInt()); assertEquals(1, second.path("model_calls").asInt()); assertEquals(7, second.path("input_tokens").asInt());
        assertEquals(1, j.path("rows").get(0).path("garments_created").asInt());
        assertEquals(1, j.path("rows").get(0).path("active_users").asInt());
        var csv = signer().send("GET", "/api/manage/v1/exports/daily?from=2025-06-01&to=2025-06-03&format=csv", null);
        assertEquals(200, csv.statusCode());
        assertTrue(csv.headers().firstValue("Content-Type").orElse("").startsWith("text/csv"));
        var lines = csv.body().strip().split("\n");
        assertEquals(4, lines.length);
        assertEquals("date," + String.join(",", json.convertValue(j.path("columns"), String[].class)), lines[0]);
        var cells = lines[2].split(",");
        assertEquals("2025-06-02", cells[0]);
        assertEquals(second.path("wear_logs").asText(), cells[List.of(lines[0].split(",")).indexOf("wear_logs")]);
        assertEquals(400, status("GET", "/api/manage/v1/exports/daily?from=2024-01-01&to=2025-06-01", null));
    }

    @Test void eventStreamPagesWithoutGapsOrRepeatsAndHidesNames() throws Exception {
        String a = user(), b = user();
        long base = at(LocalDate.of(2025, 7, 1), 9);
        var expected = new HashSet<String>();
        // Same millisecond across tables and rows: the (atMs, type, id) order must still page cleanly.
        for (int i = 0; i < 4; i++) expected.add(garment(i % 2 == 0 ? a : b, base));
        for (int i = 0; i < 3; i++) expected.add(log(a, "PAGE", i + 1, List.of(), base, null));
        expected.add(wear(b, List.of("x"), base)); expected.add(usage(a, "AGENT", 5L, 6L, base + 1));
        var seen = new ArrayList<String>(); var keys = new HashMap<String, String>();
        String cursor = null; JsonNode page; String firstCursor = null; JsonNode firstPage = null;
        do {
            String types = "&types=garment_created&types=recommendation_shown&types=wear_logged&types=model_usage";
            page = get("/api/manage/v1/exports/events?limit=3" + types + (cursor == null ? "&since=" + base : "&cursor=" + cursor));
            if (firstCursor == null && cursor != null) { firstCursor = cursor; firstPage = page; }
            for (var e : page.path("items")) {
                seen.add(e.path("id").asText());
                assertEquals(16, e.path("userKey").asText().length());
                if (e.path("type").asText().equals("garment_created")) assertFalse(e.path("attrs").path("family").asText().isBlank());
            }
            cursor = page.path("nextCursor").asText();
        } while (page.path("hasMore").asBoolean());
        assertEquals(new HashSet<>(seen).size(), seen.size(), "no event twice");
        assertTrue(seen.containsAll(expected), "no event missing");
        String body = signer().send("GET", "/api/manage/v1/exports/events?since=" + base + "&limit=1000", null).body();
        assertFalse(body.contains(a) || body.contains(b), "user names never leave the server");
        // Re-reading a cursor gives the same page.
        assertEquals(firstPage.path("items").toString(), get("/api/manage/v1/exports/events?limit=3&types=garment_created&types=recommendation_shown&types=wear_logged&types=model_usage&cursor=" + firstCursor).path("items").toString());
        // userKey is stable for one user and differs between users.
        var all = json.readTree(body).path("items");
        for (var e : all) if (expected.contains(e.path("id").asText())) keys.merge(e.path("id").asText(), e.path("userKey").asText(), (x, y) -> x);
        assertEquals(2, new HashSet<>(keys.values()).size());
        assertEquals(400, status("GET", "/api/manage/v1/exports/events?cursor=not-a-cursor", null));
        assertEquals(400, status("GET", "/api/manage/v1/exports/events?types=passwords", null));
    }

    @Test void templateReviewIsLoggedAndValidated() throws Exception {
        var list = get("/api/manage/v1/templates");
        assertTrue(list.path("items").size() >= 70);
        assertEquals("FILE", list.path("items").get(0).path("source").asText());
        var r = signer().send("PATCH", "/api/manage/v1/templates/white-tee-denim-sneaker", "{\"reviewStatus\":\"APPROVED\",\"bonus\":4.5}");
        assertEquals(200, r.statusCode(), r.body());
        var t = json.readTree(r.body());
        assertEquals("APPROVED", t.path("reviewStatus").asText()); assertTrue(t.path("active").asBoolean()); assertEquals(4.5, t.path("bonus").asDouble(), 1e-9);
        assertTrue(t.path("reviewedBy").asText().startsWith("manage:primary"));
        assertEquals(404, status("PATCH", "/api/manage/v1/templates/no-such-template", "{\"enabled\":false}"));
        assertEquals(400, status("PATCH", "/api/manage/v1/templates/white-tee-denim-sneaker", "{\"reviewStatus\":\"APPROVED\",\"extra\":1}"));
        assertEquals(400, status("PATCH", "/api/manage/v1/templates/white-tee-denim-sneaker", "{}"));
        assertEquals(400, status("PATCH", "/api/manage/v1/templates/white-tee-denim-sneaker", "{\"bonus\":9}"));
        var logs = get("/api/manage/v1/ops/operation-log?action=TEMPLATE_REVIEW");
        assertTrue(logs.path("total").asInt() >= 2, "success and the not-found attempt are both recorded");
        assertTrue(logs.path("items").toString().contains("template=white-tee-denim-sneaker"));
    }

    @Test void everyNewRouteNeedsASignature() throws Exception {
        var client = java.net.http.HttpClient.newHttpClient();
        for (String path : List.of("/ops/status", "/ops/tasks", "/ops/operation-log", "/insights/recommendations", "/insights/ai", "/dashboard/snapshot", "/exports/daily", "/exports/events", "/templates")) {
            var r = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/api/manage/v1" + path)).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(401, r.statusCode(), path);
        }
    }
}
