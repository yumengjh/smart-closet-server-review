package com.smartcloset.garment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.smartcloset.processing.ProcessingStore;
import com.smartcloset.recognition.Recognition;
import com.smartcloset.recognition.RecognitionService;
import com.smartcloset.recommend.RecommendationService;
import com.smartcloset.security.ClosetAccountService;
import com.smartcloset.workflow.WorkflowFiles;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
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

/** genderFit backfill (design 2026-10-21): one batched call fills only genderFit of old results, never a user's value. */
@SpringBootTest(properties = {"spring.config.import=optional:file:./target/no-local-secrets.yml", "closet.model-path=target/absent-style-model.onnx", "closet.embedding-model-path=target/absent-embedding.onnx"})
class GenderFitBackfillTest {
    @TempDir static Path storage;
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) { registry.add("closet.storage-dir", () -> storage.toString()); }
    @MockitoBean RecognitionService recognition;
    @Autowired JdbcTemplate db;
    @Autowired ClosetAccountService accounts;
    @Autowired GenderFitHandler handler;
    @Autowired RecommendationService recommendations;
    final JsonMapper json = JsonMapper.builder().build();
    String owner;
    static final String OLD = "{\"assessment\":\"READY\",\"attributes\":{\"category\":{\"values\":[\"衬衫\"],\"status\":\"OBSERVED\",\"evidence\":\"领口\"}},\"issues\":[]}";

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
        owner = "fit-" + UUID.randomUUID().toString().substring(0, 8);
        accounts.create(owner, "gender-fit-password", "USER");
        reset(recognition);
        db.update("DELETE FROM app_marker WHERE name='gender-fit-backfill-done'");
        when(recognition.providers()).thenReturn(List.of(new RecognitionService.ProviderInfo("test", "OPENAI_CHAT", "m", true, true)));
        when(recognition.account(any())).thenReturn("test");
        when(recognition.prepare(any())).thenReturn(new Recognition.Prepared("test", "m", "r1", "v", "v", "p", json.createObjectNode()));
    }

    @Test void oneBatchedCallFillsOnlyGenderFitAndKeepsUserValues() throws Exception {
        var womens = garment("女式衬衫", Map.of(), 1_000, OLD, "{}");
        var plain = garment("白T恤", Map.of(), 1_000, OLD, "{}");
        var userSet = garment("我改过的", Map.of("genderFit", List.of("中性")), 1_000, OLD, "{}");
        var fresh = garment("新识别", Map.of(), 1_000, OLD.replace("\"issues\"", "\"_x\":0,\"issues\"").replace("\"attributes\":{", "\"attributes\":{\"genderFit\":{\"values\":[\"男款\"],\"status\":\"INFERRED\",\"evidence\":\"直身\"},"), "{}");
        when(recognition.raw(any(), any(), anyString())).thenReturn(new RecognitionService.RawOutput(
            json.readTree("{\"items\":[{\"index\":1,\"genderFit\":\"女款\",\"evidence\":\"收腰\"},{\"index\":2,\"genderFit\":\"中性\",\"evidence\":\"基础款\"}]}"), null, "req"));
        long before = db.queryForObject("SELECT updated_at_ms FROM garment WHERE id=?", Long.class, womens);
        handler.rescanNow();
        var candidate = handler.candidates().stream().filter(c -> c.owner().equals(owner)).findFirst().orElseThrow();
        handler.execute(new ProcessingStore.Task("t", "k", owner, GenderFitHandler.KIND, candidate.businessId(), 0, "RECOGNITION", "test", 10, "RUNNING", 0, 0, null, null));
        verify(recognition, times(1)).raw(any(), any(), anyString());
        var w = json.readTree(db.queryForObject("SELECT automatic_json FROM garment WHERE id=?", String.class, womens)).path("attributes");
        assertEquals("女款", w.path("genderFit").path("values").get(0).asText());
        assertEquals("INFERRED", w.path("genderFit").path("status").asText());
        assertEquals("衬衫", w.path("category").path("values").get(0).asText(), "nothing else changes");
        assertEquals(before, db.queryForObject("SELECT updated_at_ms FROM garment WHERE id=?", Long.class, womens), "closet order stays");
        assertEquals("中性", json.readTree(db.queryForObject("SELECT automatic_json FROM garment WHERE id=?", String.class, plain)).path("attributes").path("genderFit").path("values").get(0).asText());
        assertFalse(db.queryForObject("SELECT automatic_json FROM garment WHERE id=?", String.class, userSet).contains("genderFit"), "a user's value is never overwritten");
        assertTrue(db.queryForObject("SELECT automatic_json FROM garment WHERE id=?", String.class, fresh).contains("男款"), "new recognitions are left alone");
        assertTrue(handler.pending(owner, 12).isEmpty());
        var inventory = recommendations.inventory(owner).stream().filter(i -> i.id().equals(womens)).findFirst().orElseThrow();
        assertEquals(List.of("女款"), inventory.fields().get("genderFit"));
        assertEquals("INFERRED", inventory.fieldStatus().get("genderFit"));
    }
}
