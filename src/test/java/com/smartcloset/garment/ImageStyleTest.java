package com.smartcloset.garment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.smartcloset.processing.ProcessingStore;
import com.smartcloset.recognition.Recognition;
import com.smartcloset.recognition.RecognitionService;
import com.smartcloset.recommend.Intent;
import com.smartcloset.recommend.RecommendationService;
import com.smartcloset.security.ClosetAccountService;
import com.smartcloset.semantic.GarmentSearchService;
import com.smartcloset.workflow.WorkflowFiles;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Display-image style (D29): resolved for free from recognition, generation parameters or the source; the batched
 * grid only backfills old garments once; default search scope; learned mixing in recommendations.
 */
@SpringBootTest(properties = {"spring.config.import=optional:file:./target/no-local-secrets.yml", "closet.model-path=target/absent-style-model.onnx", "closet.embedding-model-path=target/absent-embedding.onnx"})
class ImageStyleTest {
    @TempDir static Path storage;
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) { registry.add("closet.storage-dir", () -> storage.toString()); }
    @MockitoBean RecognitionService recognition;
    @Autowired JdbcTemplate db;
    @Autowired ClosetAccountService accounts;
    @Autowired ImageStyles styles;
    @Autowired ImageStyleHandler handler;
    @Autowired GarmentSearchService search;
    @Autowired RecommendationService recommendations;
    final JsonMapper json = JsonMapper.builder().build();
    String owner;

    private String garment(String name, Map<String, List<String>> fields) throws Exception { return garment(name, fields, System.currentTimeMillis(), null, "{}"); }
    /** An "old" garment: created before the backfill cutoff and recognised before recognition reported image styles. */
    private String old(String name, Map<String, List<String>> fields) throws Exception {
        return garment(name, fields, 1_000, "{\"assessment\":\"READY\",\"attributes\":{},\"issues\":[]}", "{}");
    }
    private String garment(String name, Map<String, List<String>> fields, long now, String automatic, String request) throws Exception {
        String id = UUID.randomUUID().toString(), job = UUID.randomUUID().toString(), asset = UUID.randomUUID().toString();
        var image = new BufferedImage(40, 60, BufferedImage.TYPE_INT_ARGB); var out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out); byte[] png = out.toByteArray();
        Files.createDirectories(storage.resolve("workflows").resolve(job)); Files.write(storage.resolve("workflows").resolve(job).resolve("step-0.png"), png);
        db.update("INSERT INTO workflow_job(id,fingerprint,status,created_at_ms,updated_at_ms,request_json,requires_review,version,owner_username) VALUES(?,?,'SUCCEEDED',?,?,?,0,0,?)", job, job, now, now, request, owner);
        db.update("INSERT INTO image_asset(id,job_id,file_name,sha256,byte_size,width,height,kind,review_status,created_at_ms) VALUES(?,?,'step-0.png',?,?,40,60,'BASE_STICKER','APPROVED',?)", asset, job, WorkflowFiles.hash(png), png.length, now);
        db.update("INSERT INTO garment(id,source_asset_id,display_asset_id,source_origin,overrides_json,automatic_json,display_user_selected,revision,created_at_ms,updated_at_ms,owner_username,closet_status,name) VALUES(?,?,?,'USER_UPLOAD',?,?,0,0,?,?,?,'ACTIVE',?)",
            id, asset, asset, json.writeValueAsString(fields), automatic, now, now, owner, name);
        db.update("UPDATE workflow_job SET garment_id=? WHERE id=?", id, job);
        return id;
    }

    @BeforeEach void account() {
        owner = "style-" + UUID.randomUUID().toString().substring(0, 8);
        accounts.create(owner, "image-style-password", "USER");
        reset(recognition);
        when(recognition.providers()).thenReturn(List.of(new RecognitionService.ProviderInfo("test", "OPENAI_CHAT", "m", true, true)));
        when(recognition.account(any())).thenReturn("test");
        when(recognition.prepare(any())).thenReturn(new Recognition.Prepared("test", "m", "r1", "v", "v", "p", json.createObjectNode()));
    }

    @Test void newGarmentsGetTheirStyleWithoutAnyCall() throws Exception {
        var recognised = garment("卡通T恤", Map.of("category", List.of("T恤")), System.currentTimeMillis(),
            "{\"assessment\":\"READY\",\"attributes\":{},\"issues\":[],\"imageStyle\":\"ILLUSTRATION\"}", "{}");
        var generated = garment("线稿裙", Map.of("category", List.of("连衣裙")), System.currentTimeMillis(), null, "{\"steps\":[{\"parameters\":{\"imageStyle\":\"SKETCH\"}}]}");
        var plain = garment("白衬衫", Map.of("category", List.of("衬衫")));
        var current = styles.current(owner);
        assertEquals(new ImageStyles.Entry(recognised, ImageStyles.Style.ILLUSTRATION, "RECOGNITION", "识图时一并判断（展示图由这张原图抠图或整理而来）"), current.get(recognised));
        assertEquals("GENERATION", current.get(generated).source()); assertEquals(ImageStyles.Style.SKETCH, current.get(generated).style());
        assertEquals("SOURCE", current.get(plain).source()); assertEquals(ImageStyles.Style.PHOTO, current.get(plain).style());
        assertTrue(styles.pending(owner, 12).isEmpty(), "new garments never enter the backfill");
        db.update("DELETE FROM app_marker WHERE name='image-style-backfill-done'");
        handler.rescanNow();
        assertTrue(handler.candidates().stream().noneMatch(c -> c.owner().equals(owner)));
        verify(recognition, never()).raw(any(), any(), anyString());
    }

    @Test void oneBatchedCallBackfillsOldGarmentsOnceAndKeepsUserChoices() throws Exception {
        db.update("DELETE FROM app_marker WHERE name='image-style-backfill-done'");
        var ids = new ArrayList<String>();
        for (int i = 0; i < 3; i++) ids.add(old("衣物" + i, Map.of("category", List.of("T恤"), "primaryColor", List.of("白色"))));
        styles.setByUser(owner, ids.get(2), "SKETCH");
        var grid = new AtomicReference<byte[]>();
        when(recognition.raw(any(), any(), anyString())).thenAnswer(inv -> {
            grid.set(inv.getArgument(1));
            return new RecognitionService.RawOutput(json.readTree("{\"items\":[{\"index\":1,\"style\":\"PHOTO\",\"evidence\":\"真实布料纹理\"},{\"index\":2,\"style\":\"ILLUSTRATION\",\"evidence\":\"平涂描边\"}]}"), null, "req");
        });
        handler.rescanNow();
        var candidate = handler.candidates().stream().filter(c -> c.owner().equals(owner)).findFirst().orElseThrow();
        assertEquals("RECOGNITION", candidate.pool());
        assertEquals(1, handler.quotaUnits(null));
        handler.execute(new ProcessingStore.Task("t", "k", owner, ImageStyleHandler.KIND, candidate.businessId(), 0, "RECOGNITION", "test", 10, "RUNNING", 0, 0, null, null));
        verify(recognition, times(1)).raw(any(), any(), anyString());
        var image = ImageIO.read(new java.io.ByteArrayInputStream(grid.get()));
        assertEquals(ImageStyleHandler.COLUMNS * ImageStyleHandler.TILE, image.getWidth(), "two pending garments share one numbered grid");
        var current = styles.current(owner);
        assertEquals(Set.of("PHOTO", "ILLUSTRATION"), Set.of(current.get(ids.get(0)).style().name(), current.get(ids.get(1)).style().name()));
        assertEquals("SKETCH", current.get(ids.get(2)).style().name(), "a user's correction is never overwritten");
        assertTrue(styles.pending(owner, 12).isEmpty());
        // A new display image makes the automatic answer stale again.
        db.update("UPDATE garment SET display_asset_id=source_asset_id WHERE id=?", ids.get(0));
        db.update("UPDATE garment_image_style SET display_asset_id='other' WHERE garment_id=?", ids.get(0));
        assertEquals(List.of(ids.get(0)), styles.pending(owner, 12).stream().map(ImageStyles.Pending::garmentId).toList());
        // Once no old garment is left anywhere, the backfill marks itself done and never scans again.
        db.update("UPDATE garment SET closet_status='TRASHED' WHERE created_at_ms<?", 2_000);
        handler.rescanNow();
        assertTrue(handler.candidates().isEmpty());
        assertTrue(styles.backfillDone());
        db.update("UPDATE garment SET closet_status='ACTIVE' WHERE id=?", ids.get(0));
        assertTrue(handler.candidates().isEmpty(), "done is final");
    }

    @Test void searchDefaultsToTheDominantStyleAndCanSwitch() throws Exception {
        var photo1 = garment("白T", Map.of("category", List.of("T恤"), "primaryColor", List.of("白色")));
        var photo2 = garment("白衬衫", Map.of("category", List.of("衬衫"), "primaryColor", List.of("白色")));
        var cartoon = garment("卡通白T", Map.of("category", List.of("T恤"), "primaryColor", List.of("白色")));
        var unknown = old("未判定白T", Map.of("category", List.of("T恤"), "primaryColor", List.of("白色")));
        styles.setByUser(owner, photo1, "PHOTO"); styles.setByUser(owner, photo2, "PHOTO"); styles.setByUser(owner, cartoon, "ILLUSTRATION");
        assertEquals(ImageStyles.Style.PHOTO, styles.dominant(owner));
        var normal = search.search(owner, "白色上衣", null);
        assertEquals(Set.of(photo1, photo2, unknown), ids(normal));
        assertTrue(normal.notice().contains("另有 1 件其他风格"), normal.notice());
        var cartoons = search.search(owner, "卡通的白色上衣", null);
        assertEquals(Set.of(cartoon), ids(cartoons), "an explicit style never includes unclassified garments");
        assertTrue(cartoons.hits().getFirst().reasons().contains("图片风格=卡通插画 ✓"));
        assertEquals(4, search.search(owner, "白色上衣", "ALL").total());
        assertEquals(4, search.search(owner, "全部风格的白色上衣", null).total());
    }

    @Test void mixingStylesInOneOutfitFollowsTheSetting() throws Exception {
        var top = garment("卡通白T", Map.of("category", List.of("T恤"), "primaryColor", List.of("白色"), "styles", List.of("休闲")));
        var bottom = garment("牛仔裤", Map.of("category", List.of("牛仔裤"), "primaryColor", List.of("蓝色"), "styles", List.of("休闲")));
        styles.setByUser(owner, top, "ILLUSTRATION"); styles.setByUser(owner, bottom, "PHOTO");
        var intent = new Intent(Intent.Occasion.DAILY, null, null, null, List.of(), List.of(), List.of(), "", List.of(), List.of(), 1, Map.of());
        var penalised = recommendations.recommend(owner, intent, "TEST");
        assertEquals(1, penalised.outfits().size());
        assertTrue(penalised.outfits().getFirst().warnings().contains("单品图片风格不一致（写实实物与卡通插画）"), penalised.outfits().getFirst().warnings().toString());
        assertEquals("ILLUSTRATION", penalised.outfits().getFirst().garments().stream().filter(g -> g.id().equals(top)).findFirst().orElseThrow().imageStyle());
        // Learned mixing (AUTO): "画风不统一" feedback makes the same mix cost more next time.
        double before = penalised.outfits().getFirst().score();
        recommendations.feedback(owner, penalised.outfits().getFirst().logId(), "DOWN", List.of("STYLE_MISMATCH"), null);
        var learned = recommendations.recommend(owner, intent, "TEST").outfits().getFirst();
        assertTrue(learned.warnings().stream().anyMatch(w -> w.contains("扣分加大")), learned.warnings().toString());
        assertTrue(learned.score() < before - 8, before + " → " + learned.score());
        recommendations.updateSettings(owner, Map.of("styleMixing", "FORBID"));
        var forbidden = recommendations.recommend(owner, intent, "TEST");
        assertTrue(forbidden.outfits().isEmpty());
        assertTrue(forbidden.emptyReason().contains("不放在同一套"), forbidden.emptyReason());
        recommendations.updateSettings(owner, Map.of("styleMixing", "ALLOW"));
        assertTrue(recommendations.recommend(owner, intent, "TEST").outfits().getFirst().warnings().stream().noneMatch(w -> w.contains("图片风格")));
        assertThrows(com.smartcloset.image.ProcessingException.class, () -> recommendations.updateSettings(owner, Map.of("styleMixing", "SOMETIMES")));
    }

    private static Set<String> ids(GarmentSearchService.Result r) { var out = new HashSet<String>(); r.hits().forEach(h -> out.add(h.id())); return out; }
}
