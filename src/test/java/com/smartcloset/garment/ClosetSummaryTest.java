package com.smartcloset.garment;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcloset.security.ClosetAccountService;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** Issue #8: exact counts and the matching garments themselves, including by picture style (卡通插画 vs a cartoon print). */
@SpringBootTest(properties = {"spring.config.import=optional:file:./target/no-local-secrets.yml", "closet.model-path=target/absent.onnx", "closet.embedding-model-path=target/absent-embedding.onnx"})
class ClosetSummaryTest {
    @TempDir static Path storage;
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) { registry.add("closet.storage-dir", () -> storage.toString()); }
    @Autowired JdbcTemplate db;
    @Autowired ClosetAccountService accounts;
    @Autowired ClosetSummaryService summary;
    @Autowired ImageStyles imageStyles;
    final JsonMapper json = JsonMapper.builder().build();

    private String garment(String owner, String name, Map<String, List<String>> fields) {
        String id = UUID.randomUUID().toString(), job = UUID.randomUUID().toString(), asset = UUID.randomUUID().toString(); long now = System.currentTimeMillis();
        db.update("INSERT INTO workflow_job(id,fingerprint,status,created_at_ms,updated_at_ms,request_json,requires_review,version,owner_username) VALUES(?,?,'SUCCEEDED',?,?,'{}',0,0,?)", job, job, now, now, owner);
        db.update("INSERT INTO image_asset(id,job_id,file_name,sha256,byte_size,width,height,kind,review_status,created_at_ms) VALUES(?,?,'input.png','fixture',1,64,96,'INPUT','APPROVED',?)", asset, job, now);
        db.update("INSERT INTO garment(id,source_asset_id,display_asset_id,source_origin,overrides_json,display_user_selected,revision,created_at_ms,updated_at_ms,owner_username,closet_status,name) VALUES(?,?,?,'USER_UPLOAD',?,0,0,?,?,?,'ACTIVE',?)",
            id, asset, asset, json.writeValueAsString(fields), now, now, owner, name);
        db.update("UPDATE workflow_job SET garment_id=? WHERE id=?", id, job);
        return id;
    }

    @Test void countsAndListsByPictureStyle() {
        String owner = "sum-" + UUID.randomUUID().toString().substring(0, 8);
        accounts.create(owner, "summary-test-password", "USER");
        var drawn = new ArrayList<String>();
        for (int i = 0; i < 3; i++) drawn.add(garment(owner, "插画卫衣" + i, Map.of("category", List.of("卫衣"), "primaryColor", List.of("粉色"))));
        String print = garment(owner, "卡通印花T恤", Map.of("category", List.of("T恤"), "primaryColor", List.of("白色"), "patterns", List.of("图形印花")));
        String photo = garment(owner, "白衬衫", Map.of("category", List.of("衬衫"), "primaryColor", List.of("白色")));
        for (String id : drawn) imageStyles.setByUser(owner, id, "ILLUSTRATION");
        imageStyles.setByUser(owner, print, "PHOTO"); imageStyles.setByUser(owner, photo, "PHOTO");

        var cartoon = summary.summary(owner, "", "", "", "", null, "ILLUSTRATION");
        assertEquals(3, cartoon.get("count"));
        var items = (List<?>) cartoon.get("items");
        assertEquals(Set.copyOf(drawn), items.stream().map(m -> ((Map<?, ?>) m).get("id")).collect(java.util.stream.Collectors.toSet()));
        assertEquals(false, cartoon.get("itemsTruncated"));
        assertEquals(Map.of("ILLUSTRATION", 3L, "PHOTO", 2L), cartoon.get("imageStyleCounts"));
        // A cartoon print on a photographed garment is a different question: the text query, not the picture style.
        var printed = summary.summary(owner, "卡通", "", "", "", null, "");
        assertEquals(1, printed.get("count"));
        assertEquals(print, ((Map<?, ?>) ((List<?>) printed.get("items")).getFirst()).get("id"));
        assertThrows(com.smartcloset.image.ProcessingException.class, () -> summary.summary(owner, "", "", "", "", null, "CARTOON"));
    }
}
