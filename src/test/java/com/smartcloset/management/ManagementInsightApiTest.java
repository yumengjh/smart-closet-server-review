package com.smartcloset.management;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcloset.security.ClosetAccountService;
import com.smartcloset.security.QuotaPolicy;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=optional:file:./target/no-local-secrets.yml", "closet.model-path=target/absent.onnx",
        "closet.management.enabled=true", "closet.management.auth.keys[0].id=primary",
        "closet.management.auth.keys[0].secret=management-test-secret-0123456789abcdef",
        "closet.management.insights-cache-ttl=0s"})
class ManagementInsightApiTest {
    @TempDir static Path storage;
    @DynamicPropertySource static void config(DynamicPropertyRegistry p) { p.add("closet.storage-dir", () -> storage.toString()); }
    @LocalServerPort int port;
    @Autowired ClosetAccountService accounts;
    @Autowired JdbcTemplate db;
    final JsonMapper json = JsonMapper.builder().build();

    JsonNode get(String path) throws Exception {
        var response = new ManagementSigner(port, "primary", "management-test-secret-0123456789abcdef").send("GET", path, null);
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }
    int status(String path) throws Exception {
        return new ManagementSigner(port, "primary", "management-test-secret-0123456789abcdef").send("GET", path, null).statusCode();
    }

    String user() {
        String name = "in-" + UUID.randomUUID().toString().substring(0, 8);
        accounts.create(name, "InsightTestPassword123!", "USER");
        return name;
    }

    String garment(String owner, String status, long createdAt, Map<String, List<String>> fields) {
        String id = UUID.randomUUID().toString(), job = UUID.randomUUID().toString(), asset = UUID.randomUUID().toString();
        db.update("INSERT INTO workflow_job(id,fingerprint,status,created_at_ms,updated_at_ms,request_json,requires_review,version,owner_username) VALUES(?,?,'SUCCEEDED',?,?,'{}',0,0,?)",
                job, UUID.randomUUID().toString(), createdAt, createdAt, owner);
        db.update("INSERT INTO image_asset(id,job_id,file_name,sha256,byte_size,width,height,kind,review_status,created_at_ms) VALUES(?,?,'input.png','fixture',1,120,160,'INPUT','APPROVED',?)",
                asset, job, createdAt);
        db.update("INSERT INTO garment(id,source_asset_id,display_asset_id,source_origin,overrides_json,display_user_selected,revision,created_at_ms,updated_at_ms,owner_username,closet_status) VALUES(?,?,?,'USER_UPLOAD',?,0,0,?,?,?,?)",
                id, asset, asset, json.writeValueAsString(fields), createdAt, createdAt, owner, status);
        return id;
    }

    void outfit(String owner, long createdAt, String... garmentIds) {
        var elements = java.util.Arrays.stream(garmentIds).map(id -> Map.of("garmentId", id, "role", "TOP")).toList();
        db.update("INSERT INTO outfit(id,owner_username,name,scene_version,scene_json,revision,created_at_ms,updated_at_ms) VALUES(?,?,'fixture',2,?,0,?,?)",
                UUID.randomUUID().toString(), owner, json.writeValueAsString(Map.of("version", 2, "elements", elements)), createdAt, createdAt);
    }

    long at(LocalDate day) { return day.atTime(12, 0).atZone(QuotaPolicy.ZONE).toInstant().toEpochMilli(); }

    @Test void singleUserReportCountsWardrobeUsageOverviewAndTrends() throws Exception {
        String owner = user();
        LocalDate today = QuotaPolicy.today();
        String shirt = garment(owner, "ACTIVE", at(today.minusDays(2)), Map.of("category", List.of("衬衫"), "primaryColor", List.of("白色"), "seasons", List.of("春", "夏")));
        String pants = garment(owner, "ACTIVE", at(today.minusDays(1)), Map.of("category", List.of("牛仔裤"), "primaryColor", List.of("蓝色"), "seasons", List.of("秋")));
        garment(owner, "ACTIVE", at(today.minusDays(40)), Map.of("category", List.of("外套"), "primaryColor", List.of("白色")));
        garment(owner, "DRAFT", at(today.minusDays(1)), Map.of("category", List.of("帽子")));
        outfit(owner, at(today.minusDays(1)), shirt, pants);
        outfit(owner, at(today), shirt, shirt);
        db.update("INSERT INTO ai_daily_quota(owner_username,quota_date,used) VALUES(?,?,?)", owner, java.sql.Date.valueOf(today), 4);
        db.update("INSERT INTO agent_daily_quota(owner_username,quota_date,used) VALUES(?,?,?)", owner, java.sql.Date.valueOf(today.minusDays(1)), 3);

        var overview = get("/api/manage/v1/insights/overview?username=" + owner);
        assertFalse(overview.has("users"));
        assertEquals(owner, overview.path("scope").path("username").asText());
        assertEquals(today.minusDays(29).toString(), overview.path("scope").path("from").asText());
        assertEquals(3, overview.path("garments").path("active").asInt());
        assertEquals(1, overview.path("garments").path("draft").asInt());
        assertEquals(3, overview.path("garments").path("createdInRange").asInt());
        assertEquals(2, overview.path("garments").path("adoptedInRange").asInt());
        assertEquals(0.667, overview.path("garments").path("adoptionRate").asDouble(), 0.0001);
        assertEquals(2, overview.path("outfits").path("total").asInt());
        assertEquals(4, overview.path("ai").path("imageUnitsInRange").asInt());
        assertEquals(3, overview.path("ai").path("agentMessagesInRange").asInt());

        var wardrobe = get("/api/manage/v1/insights/wardrobe?username=" + owner);
        assertEquals(3, wardrobe.path("totalActive").asInt());
        assertEquals("白色", wardrobe.path("colors").get(0).path("key").asText());
        assertEquals(2, wardrobe.path("colors").get(0).path("count").asInt());
        assertEquals(4, wardrobe.path("seasons").size());
        assertEquals(1, wardrobe.path("seasons").get(0).path("count").asInt());
        assertEquals(1, wardrobe.path("unknownSeasonCount").asInt());
        assertEquals("closet-families-v1", wardrobe.path("mappingVersion").asText());

        var usage = get("/api/manage/v1/insights/usage?username=" + owner);
        assertEquals(2, usage.path("outfitCount").asInt());
        assertEquals(shirt, usage.path("topUsed").get(0).path("garmentId").asText());
        assertEquals(2, usage.path("topUsed").get(0).path("outfitCount").asInt(), "an outfit counts a garment once");
        assertEquals(1, usage.path("idle").path("count").asInt());
        assertEquals(0.333, usage.path("idle").path("ratio").asDouble(), 0.0001);
        assertEquals("OUTER", usage.path("idle").path("sample").get(0).path("family").asText());

        var trends = get("/api/manage/v1/insights/trends?username=" + owner + "&metric=garments_created&metric=image_units&metric=agent_messages&from="
                + today.minusDays(2) + "&to=" + today);
        assertEquals(List.of(today.minusDays(2).toString(), today.minusDays(1).toString(), today.toString()),
                json.convertValue(trends.path("buckets"), List.class));
        assertEquals(List.of(1, 2, 0), json.convertValue(trends.path("series").get(0).path("values"), List.class));
        assertEquals(List.of(0, 0, 4), json.convertValue(trends.path("series").get(1).path("values"), List.class));
        var weekly = get("/api/manage/v1/insights/trends?username=" + owner + "&metric=outfits_created&granularity=week&from=" + today.minusDays(1) + "&to=" + today);
        assertEquals(today.minusDays(1).with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).toString(),
                weekly.path("buckets").get(0).asText());
        assertEquals(2, sum(weekly.path("series").get(0).path("values")));
    }

    static int sum(JsonNode values) { int total = 0; for (var v : values) total += v.asInt(); return total; }

    @Test void siteWideViewIncludesUsersAndAllOwners() throws Exception {
        String a = user(), b = user();
        long now = System.currentTimeMillis();
        garment(a, "ACTIVE", now, Map.of("category", List.of("T恤")));
        garment(b, "ACTIVE", now, Map.of("category", List.of("T恤")));
        var overview = get("/api/manage/v1/insights/overview");
        assertTrue(overview.path("users").path("total").asInt() >= 2);
        assertTrue(overview.path("users").path("createdInRange").asInt() >= 2);
        assertTrue(overview.path("scope").path("username").isNull());
        var usage = get("/api/manage/v1/insights/usage");
        var owners = new java.util.HashSet<String>();
        usage.path("idle").path("sample").forEach(g -> owners.add(g.path("owner").asText()));
        assertTrue(owners.contains(a) && owners.contains(b), owners.toString());
        var wardrobe = get("/api/manage/v1/insights/wardrobe");
        assertTrue(wardrobe.path("totalActive").asInt() >= 2);
    }

    @Test void validatesScopeRangeAndMetrics() throws Exception {
        assertEquals(404, status("/api/manage/v1/insights/wardrobe?username=nobody-here"));
        assertEquals(400, status("/api/manage/v1/insights/overview?from=2026-01-10&to=2026-01-01"));
        assertEquals(400, status("/api/manage/v1/insights/overview?from=2024-01-01&to=2026-01-01"));
        assertEquals(400, status("/api/manage/v1/insights/trends?metric=revenue"));
        assertEquals(400, status("/api/manage/v1/insights/trends"));
        assertEquals(400, status("/api/manage/v1/insights/trends?metric=garments_created&granularity=month"));
        assertEquals(400, status("/api/manage/v1/insights/overview?from=yesterday"));
    }
}
