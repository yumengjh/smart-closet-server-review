package com.smartcloset.person;

import com.smartcloset.image.ProcessingException;
import com.smartcloset.outfit.OutfitRepository;
import com.smartcloset.security.ClosetAccountService;
import com.smartcloset.workflow.WorkflowFiles;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Issue #42: several named models, OOTD poses (auto / preset / extracted card) and body adjustments of a finished OOTD. */
@SpringBootTest(properties = {"spring.config.import=optional:file:./target/no-local-secrets.yml", "closet.model-path=target/absent-person-model.onnx", "closet.ootd.pose-minor-check-enabled=true",
    "generation.providers.fixture.protocol=OPENAI_IMAGES", "generation.providers.fixture.api-key=fake-local-test", "generation.providers.fixture.default-model=fixture-image"})
class ModelsPosesAdjustmentsTest {
    @TempDir static Path storage;
    static final HttpServer upstream;
    static final AtomicInteger calls = new AtomicInteger();
    static final AtomicReference<String> lastBody = new AtomicReference<>("");
    static final byte[] PNG;
    static {
        try {
            var bytes = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(64, 96, BufferedImage.TYPE_INT_RGB), "png", bytes); PNG = bytes.toByteArray();
            upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            upstream.createContext("/v1/images", exchange -> {
                lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); calls.incrementAndGet();
                byte[] response = ("{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(PNG) + "\"}]}").getBytes();
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
            });
            upstream.start();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        r.add("closet.storage-dir", storage::toString);
        r.add("generation.providers.fixture.base-url", () -> "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1");
    }
    @AfterAll static void shutdown() { upstream.stop(0); }

    @MockitoBean PoseExtractor extractor;
    @Autowired PersonalRenders renders; @Autowired PoseCards poses; @Autowired WearerProfiles profiles; @Autowired RenderCapabilities capabilities;
    @Autowired PrivateMedia media; @Autowired ClosetAccountService accounts; @Autowired JdbcTemplate db; @Autowired WorkflowFiles files;
    @Autowired OutfitRepository outfits; @Autowired ObjectMapper json;

    private String owner() { String owner = "pose-" + UUID.randomUUID().toString().substring(0, 8); accounts.create(owner, "test-person-password", "USER"); return owner; }
    private void verified(String purpose) {
        var current = capabilities.list().stream().filter(c -> c.provider().equals("fixture") && c.purpose().equals(purpose)).findFirst();
        capabilities.update(new RenderCapabilities.Capability("fixture", "fixture-image", purpose, 10, true, current.map(RenderCapabilities.Capability::version).orElse(0L), null, null, "本地模拟协议测试"));
    }
    private PersonalRenders.Job completed(String owner, String id) throws Exception {
        long end = System.currentTimeMillis() + 10000; PersonalRenders.Job j;
        do { renders.sync(); j = renders.job(owner, id); if (!Set.of("QUEUED", "RUNNING", "CANCEL_REQUESTED", "WAITING_RESOURCE").contains(j.status())) return j; Thread.sleep(25); }
        while (System.currentTimeMillis() < end);
        fail("Task did not settle: " + j); return j;
    }
    private UUID garment(String owner, String category) throws Exception {
        UUID id = UUID.randomUUID(), job = UUID.randomUUID(); long now = System.currentTimeMillis(); var asset = files.write(job, "input.png", "INPUT", PNG);
        db.update("INSERT INTO workflow_job(id,fingerprint,status,created_at_ms,updated_at_ms,request_json,requires_review,version,owner_username) VALUES(?,?,'SUCCEEDED',?,?,'{}',0,0,?)", job.toString(), job.toString(), now, now, owner);
        db.update("INSERT INTO image_asset(id,job_id,file_name,sha256,byte_size,width,height,kind,review_status,created_at_ms) VALUES(?,?,?,?,?,?,?,'INPUT','APPROVED',?)", asset.id, job.toString(), asset.fileName, asset.sha256, asset.byteSize, asset.width, asset.height, now);
        db.update("INSERT INTO garment(id,source_asset_id,display_asset_id,source_origin,overrides_json,display_user_selected,revision,created_at_ms,updated_at_ms,owner_username,closet_status) VALUES(?,?,?,'USER_UPLOAD',?,0,0,?,?,?,'ACTIVE')",
            id.toString(), asset.id, asset.id, json.writeValueAsString(Map.of("category", List.of(category))), now, now, owner);
        db.update("UPDATE workflow_job SET garment_id=? WHERE id=?", id.toString(), job.toString());
        return id;
    }
    private OutfitRepository.View outfit(String owner, UUID... garments) {
        var elements = new ArrayList<Map<String, Object>>(); int index = 0;
        for (UUID id : garments) {
            String asset = db.queryForObject("SELECT source_asset_id FROM garment WHERE id=?", String.class, id.toString());
            var e = new LinkedHashMap<String, Object>();
            e.put("garmentId", id); e.put("assetId", asset); e.put("role", index++ == 0 ? "TOP" : "BOTTOM"); e.put("x", 100); e.put("y", 200); e.put("width", 150); e.put("height", 220);
            e.put("rotation", 0); e.put("zIndex", index - 1); e.put("garmentLabel", "测试衣物"); e.put("cropX", 0); e.put("cropY", 0); e.put("cropWidth", 1); e.put("cropHeight", 1);
            e.put("elementId", UUID.randomUUID()); e.put("kind", "GARMENT"); elements.add(e);
        }
        return outfits.create(new OutfitRepository.SaveRequest(null, "模拟搭配", json.convertValue(Map.of("version", 2, "elements", elements), OutfitRepository.SceneWrite.class)), owner);
    }
    private PersonalRenders.Baseline model(String owner, String name, boolean makeDefault) throws Exception {
        var p = profiles.get(owner);
        var job = renders.generateBaseline(owner, "baseline-" + UUID.randomUUID(), new PersonalRenders.BaselineRequest(p.revision(), "fixture", "fixture-image", null, name));
        assertEquals("SUCCEEDED", completed(owner, job.id()).status());
        var candidate = renders.baseline(owner, job.baselineId());
        return renders.activate(owner, candidate.id(), candidate.revision(), makeDefault);
    }

    @Test void severalNamedModelsCanBeConfirmedWithoutChangingTheDefault() throws Exception {
        String owner = owner(); profiles.update(owner, new WearerProfiles.Profile(0, "FEMALE", 165d, null, null, Map.of(), Map.of(), null)); verified("BASELINE");
        var first = model(owner, "日常的我", true);
        var second = model(owner, null, false);
        assertEquals("日常的我", first.name());
        assertEquals("模特 2", second.name(), "Unnamed models get a numbered default name");
        assertEquals("CONFIRMED", second.status());
        assertEquals(first.id(), profiles.get(owner).defaultBaselineId(), "Confirming without makeDefault keeps the default");
        var renamed = renders.rename(owner, second.id(), "  长发造型 ");
        assertEquals("长发造型", renamed.name());
        assertEquals(second.revision(), renamed.revision(), "Renaming is a label change and keeps prepared OOTD valid");
        assertThrows(ProcessingException.class, () -> renders.rename(owner, second.id(), " "));
        assertThrows(ProcessingException.class, () -> renders.rename(owner(), second.id(), "别人的"));
        assertEquals(2, renders.baselines(owner).stream().filter(b -> b.status().equals("CONFIRMED")).count());
    }

    @Test void poseChoiceIsFrozenIntoThePromptAndAdjustmentMakesANewImage() throws Exception {
        String owner = owner(); profiles.update(owner, new WearerProfiles.Profile(0, "MALE", null, null, null, Map.of(), Map.of(), null)); verified("BASELINE"); verified("OOTD");
        model(owner, "默认", true);
        var other = model(owner, "第二位", false);
        UUID top = garment(owner, "衬衫"), bottom = garment(owner, "长裤"); var saved = outfit(owner, top, bottom);

        var auto = renders.prepare(owner, new PersonalRenders.PreparationRequest(saved.id(), saved.revision(), UUID.fromString(other.id()), other.revision(), List.of(top, bottom), null, false, "fixture", "fixture-image", false));
        assertEquals("AUTO", auto.pose().path("mode").asText(), "No pose means the image model chooses");
        var autoJob = renders.submitOotd(owner, "auto-ootd", auto.id());
        assertTrue(renders.input(autoJob.id()).generation().prompt().contains("自行选择"));

        var preset = renders.prepare(owner, new PersonalRenders.PreparationRequest(saved.id(), saved.revision(), UUID.fromString(other.id()), other.revision(), List.of(top, bottom), null, false, "fixture", "fixture-image", false, new PoseCards.Choice("PRESET", "hand-on-hip")));
        assertEquals("单手叉腰", preset.pose().path("name").asText());
        var job = renders.submitOotd(owner, "preset-ootd", preset.id());
        assertTrue(renders.input(job.id()).generation().prompt().contains("左手叉在腰侧"));
        assertEquals("第二位", job.summary().path("model").asText());
        assertEquals("单手叉腰", job.summary().path("pose").asText());
        var done = completed(owner, job.id());
        assertEquals("SUCCEEDED", done.status(), done.error());

        var card = poses.createFromText(owner, new PoseCards.TextRequest("比心", "正面站立，双手在胸前比心，目视镜头", List.of("可爱")));
        var custom = renders.prepare(owner, new PersonalRenders.PreparationRequest(saved.id(), saved.revision(), UUID.fromString(other.id()), other.revision(), List.of(top, bottom), null, false, "fixture", "fixture-image", false, new PoseCards.Choice("CUSTOM", card.id())));
        poses.delete(owner, card.id());
        var frozen = renders.submitOotd(owner, "custom-ootd", custom.id());
        assertTrue(renders.input(frozen.id()).generation().prompt().contains("双手在胸前比心"), "A prepared pose is frozen; deleting the card later does not change it");
        assertThrows(ProcessingException.class, () -> renders.prepare(owner, new PersonalRenders.PreparationRequest(saved.id(), saved.revision(), UUID.fromString(other.id()), other.revision(), List.of(top, bottom), null, false, "fixture", "fixture-image", false, new PoseCards.Choice("CUSTOM", card.id()))));

        completed(owner, autoJob.id()); completed(owner, frozen.id());
        int before = calls.get();
        assertThrows(ProcessingException.class, () -> renders.adjust(owner, "nothing", done.id(), new PersonalRenders.Adjustment(null, null, 0, 0, 0, 0, 0)));
        assertThrows(ProcessingException.class, () -> renders.adjust(owner, "too-much", done.id(), new PersonalRenders.Adjustment(null, null, 3, 0, 0, 0, 0)));
        assertThrows(ProcessingException.class, () -> renders.adjust(owner(), "stranger", done.id(), new PersonalRenders.Adjustment(null, null, 1, 0, 0, 0, 0)));
        var adjusted = renders.adjust(owner, "slimmer", done.id(), new PersonalRenders.Adjustment(null, null, -1, 0, 1, 0, 2));
        assertEquals(adjusted.id(), renders.adjust(owner, "slimmer", done.id(), new PersonalRenders.Adjustment(null, null, -1, 0, 1, 0, 2)).id(), "Same key, same job");
        assertEquals(done.id(), adjusted.parentJobId());
        assertEquals("ADJUST", adjusted.summary().path("kind").asText());
        assertEquals(-1, adjusted.summary().path("adjustments").path("build").asInt());
        var input = renders.input(adjusted.id());
        assertEquals(1, input.references().size());
        assertEquals("SOURCE_OOTD", input.references().getFirst().purpose());
        assertTrue(input.generation().prompt().contains("稍瘦") && input.generation().prompt().contains("明显更长"));
        var result = completed(owner, adjusted.id());
        assertEquals("SUCCEEDED", result.status(), result.error());
        assertEquals(before + 1, calls.get());
        assertNotEquals(done.mediaUrl(), result.mediaUrl(), "The source image is kept; the adjustment is a new image");

        renders.delete(owner, done.id());
        assertEquals("SUCCEEDED", renders.job(owner, adjusted.id()).status(), "Deleting the source keeps the adjusted result");
        assertThrows(ProcessingException.class, () -> renders.adjust(owner, "after-delete", done.id(), new PersonalRenders.Adjustment(null, null, 1, 0, 0, 0, 0)));
    }

    @Test void interruptedOotdCanBeResubmittedManuallyOnce() throws Exception {
        String owner = owner(); profiles.update(owner, new WearerProfiles.Profile(0, "MALE", null, null, null, Map.of(), Map.of(), null)); verified("BASELINE"); verified("OOTD");
        var person = model(owner, "默认", true);
        UUID top = garment(owner, "衬衫"), bottom = garment(owner, "长裤"); var saved = outfit(owner, top, bottom);
        var prepared = renders.prepare(owner, new PersonalRenders.PreparationRequest(saved.id(), saved.revision(), UUID.fromString(person.id()), person.revision(), List.of(top, bottom), null, false, "fixture", "fixture-image", false, new PoseCards.Choice("PRESET", "hand-on-hip")));
        var first = renders.submitOotd(owner, "interrupted-ootd", prepared.id());
        assertEquals("SUCCEEDED", completed(owner, first.id()).status());
        assertThrows(ProcessingException.class, () -> renders.retry(owner, "retry-finished", first.id()), "A finished picture is never resubmitted");

        // The process was interrupted after dispatch: the job has no picture and its result is unknown.
        db.update("UPDATE image_render_job SET status='UNKNOWN',media_id=NULL WHERE id=?", first.id());
        var again = renders.retry(owner, "retry-1", first.id());
        assertNotEquals(first.id(), again.id());
        assertEquals(first.id(), again.summary().path("retryOf").asText());
        assertEquals("单手叉腰", again.summary().path("pose").asText(), "The retry keeps model and pose");
        assertEquals(renders.input(first.id()).generation().prompt(), renders.input(again.id()).generation().prompt());
        assertEquals(again.id(), renders.retry(owner, "retry-1", first.id()).id(), "The same key returns the same job");
        assertEquals("SUCCEEDED", completed(owner, again.id()).status());
        assertThrows(ProcessingException.class, () -> renders.retry(owner(), "retry-other", first.id()), "Another account cannot resubmit it");
    }

    @Test void extractedPoseKeepsOnlyDescriptionAndJoints() {
        String owner = owner();
        when(extractor.extract(any())).thenReturn(new PoseExtractor.Extraction(json.readTree("""
            {"accepted":true,"rejectReason":"NONE","name":"侧身回眸啦啦啦啦啦啦啦啦啦啦","description":"  身体向右侧转，头部回看镜头，左手扶腰 ","tags":["优雅","  ","优雅","街拍拍拍拍拍拍拍"],
             "viewAngle":"THREE_QUARTER","joints":[{"name":"HEAD","x":0.4,"y":0.2,"visible":true},{"name":"NECK","x":0.4,"y":0.3,"visible":true},
             {"name":"L_ANKLE","x":0.45,"y":0.9,"visible":true},{"name":"R_ANKLE","x":0.35,"y":0.88,"visible":true},{"name":"L_WRIST","x":1.7,"y":0.5,"visible":false},{"name":"TAIL","x":0.5,"y":0.5,"visible":true}]}
            """), "fake-vision"));
        var card = poses.extract(owner, PNG);
        assertEquals("PHOTO", card.source());
        assertEquals("侧身回眸啦啦啦啦啦啦啦啦", card.name(), "Names are cut to 12 characters");
        assertEquals("身体向右侧转，头部回看镜头，左手扶腰", card.description());
        assertEquals(List.of("优雅", "街拍拍拍拍拍"), card.tags());
        assertEquals(Set.of("HEAD", "NECK", "L_ANKLE", "R_ANKLE"), card.keypoints().keySet(), "Invisible and unknown joints are dropped");
        card.keypoints().values().forEach(p -> assertTrue(p.get(0) >= 0 && p.get(0) <= 1 && p.get(1) >= 0 && p.get(1) <= 1));
        assertEquals(0.11, card.keypoints().get("HEAD").get(1), 1e-6, "The figure is fitted to the card frame");
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM pose_extraction_attempt WHERE owner_username=?", Integer.class, owner));
        assertEquals(1, poses.catalog(owner).extractionsToday());

        when(extractor.extract(any())).thenReturn(new PoseExtractor.Extraction(json.readTree("""
            {"accepted":false,"rejectReason":"MINOR_LIKELY","name":"","description":"","tags":[],"viewAngle":"FRONT","joints":[]}"""), "fake-vision"));
        var rejected = assertThrows(ProcessingException.class, () -> poses.extract(owner, PNG));
        assertEquals("POSE_REJECTED", rejected.code());
        assertEquals(1, poses.mine(owner).size(), "A rejected picture makes no card");
        assertThrows(ProcessingException.class, () -> poses.extract(owner, "not an image".getBytes()));

        assertEquals("回眸", poses.rename(owner, card.id(), "回眸").name());
        assertThrows(ProcessingException.class, () -> poses.rename(owner(), card.id(), "偷改"));
        assertThrows(ProcessingException.class, () -> poses.createFromText(owner, new PoseCards.TextRequest(null, "短", null)));
        poses.delete(owner, card.id());
        assertTrue(poses.mine(owner).isEmpty());
    }

    @Test void dailyExtractionLimitCountsAttempts() {
        String owner = owner(); long now = System.currentTimeMillis();
        for (int i = 0; i < PoseCards.DAILY_EXTRACTIONS; i++) db.update("INSERT INTO pose_extraction_attempt(owner_username,created_at_ms) VALUES(?,?)", owner, now);
        var limited = assertThrows(ProcessingException.class, () -> poses.extract(owner, PNG));
        assertEquals("POSE_DAILY_LIMIT", limited.code());
    }

    @Test void presetsAreCompleteAndSchemaIsStrict() {
        assertTrue(poses.presets().size() >= 8);
        for (var p : poses.presets()) {
            assertEquals(PoseCards.JOINTS.size(), p.keypoints().size(), p.id());
            assertTrue(p.description().length() >= 30, p.id());
            assertFalse(p.tags().isEmpty(), p.id());
        }
        assertEquals(poses.presets().size(), poses.presets().stream().map(PoseCards.Pose::id).distinct().count());
        var schema = PoseCards.schema();
        assertFalse(schema.path("additionalProperties").asBoolean(true));
        assertEquals(schema.path("properties").size(), schema.path("required").size());
        assertTrue(PoseCards.prompt().contains("禁止描述"));
        assertFalse(PoseCards.prompt(false).contains("MINOR_LIKELY"), "Minor check off: the prompt no longer offers that refusal");
        assertTrue(PoseCards.prompt(true).contains("MINOR_LIKELY"));
    }
}
