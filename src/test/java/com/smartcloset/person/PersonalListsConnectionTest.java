package com.smartcloset.person;

import com.smartcloset.security.ClosetAccountService;
import com.smartcloset.outfit.OutfitRepository;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** List pages must finish without borrowing a second connection inside a row mapper. */
@SpringBootTest(properties={"spring.config.import=optional:file:./target/no-local-secrets.yml", "closet.model-path=target/absent-list-model.onnx", "spring.datasource.hikari.maximum-pool-size=4", "spring.datasource.hikari.minimum-idle=4", "spring.datasource.hikari.connection-timeout=1000"})
class PersonalListsConnectionTest {
    @TempDir static Path storage;
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {r.add("closet.storage-dir",()->storage.toString());}
    @Autowired JdbcTemplate db;
    @Autowired ClosetAccountService accounts;
    @Autowired WearerProfiles profiles;
    @Autowired PersonalRenders renders;
    @Autowired PersonalRenderHandler handler;
    @Autowired com.smartcloset.processing.ProcessingExecutor executor;
    @Autowired PrivateMedia media;
    @Autowired OutfitRepository outfits;
    @Autowired ObjectMapper json;

    @Test void personResultsAndSavedOutfitsLoadWithOneConnection() throws Exception {
        executor.close(); // Keep the synthetic queued task available for the explicit scan below.
        String owner="list-"+UUID.randomUUID().toString().substring(0,8);
        accounts.create(owner,"test-list-password","USER");
        var profile=profiles.update(owner,new WearerProfiles.Profile(0,"FEMALE",null,null,null,Map.of(),Map.of(),null));
        String baseline=UUID.randomUUID().toString(), render=UUID.randomUUID().toString();long now=System.currentTimeMillis();
        db.update("INSERT INTO person_baseline(id,owner_username,status,profile_snapshot_json,revision,created_at_ms) VALUES(?,?,'CANDIDATE',?,0,?)",baseline,owner,json.writeValueAsString(WearerProfiles.snapshot(profile)),now);
        db.update("INSERT INTO image_render_job(id,owner_username,purpose,input_json,status,idempotency_key,created_at_ms,updated_at_ms) VALUES(?,?,'OOTD','{}','SUCCEEDED',?,?,?)",render,owner,render,now,now);
        UUID garment=UUID.randomUUID(),job=UUID.randomUUID(),asset=UUID.randomUUID(),outfit=UUID.randomUUID();
        db.update("INSERT INTO workflow_job(id,fingerprint,status,created_at_ms,updated_at_ms,request_json,requires_review,version,owner_username) VALUES(?,?,'SUCCEEDED',?,?,'{}',0,0,?)",job.toString(),job.toString(),now,now,owner);
        db.update("INSERT INTO image_asset(id,job_id,file_name,sha256,byte_size,width,height,kind,review_status,created_at_ms) VALUES(?,?,'input.png','fixture',1,64,96,'INPUT','APPROVED',?)",asset.toString(),job.toString(),now);
        db.update("INSERT INTO garment(id,source_asset_id,display_asset_id,source_origin,overrides_json,display_user_selected,revision,created_at_ms,updated_at_ms,owner_username,closet_status) VALUES(?,?,?,'USER_UPLOAD','{}',0,0,?,?,?,'ACTIVE')",garment.toString(),asset.toString(),asset.toString(),now,now,owner);
        db.update("UPDATE workflow_job SET garment_id=? WHERE id=?",garment.toString(),job.toString());
        var item=new OutfitRepository.Item(garment,asset,"TOP",0,0,100,150,0,0,"fixture",0,"/fixture",64,96,"INPUT","ACTIVE",false,false,0,0,1,1,UUID.randomUUID(),"GARMENT",null,0,false,false);
        db.update("INSERT INTO outfit(id,owner_username,name,scene_version,scene_json,revision,created_at_ms,updated_at_ms) VALUES(?,?,'fixture',2,?,0,?,?)",outfit.toString(),owner,json.writeValueAsString(new OutfitRepository.Scene(2,List.of(item))),now,now);
        var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(64,96,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);
        var photo=media.put(owner,"PHOTO",bytes.toByteArray());
        String queued=UUID.randomUUID().toString();
        var generation=new com.smartcloset.generation.GenerationService.Prepared("fixture","fixture-image","local fixture",Map.of(),com.smartcloset.generation.GenerationProperties.Protocol.OPENAI_IMAGES);
        var input=new PersonalRenders.Frozen("OOTD",generation,"fixture",1,List.of(),json.createObjectNode(),"fixture",json.createObjectNode());
        db.update("INSERT INTO image_render_job(id,owner_username,purpose,input_json,status,idempotency_key,created_at_ms,updated_at_ms) VALUES(?,?,'OOTD',?,'QUEUED',?,?,?)",queued,owner,json.writeValueAsString(input),queued,now,now);
        // Reserve the other connections to reproduce pool pressure from simultaneous pages.
        try (var reserved = db.getDataSource().getConnection(); var second = db.getDataSource().getConnection(); var third = db.getDataSource().getConnection()) {
        assertEquals(photo.id(),media.list(owner,"PHOTO").getFirst().id());
        assertEquals(baseline,renders.baselines(owner).getFirst().id());
        assertEquals(Set.of(render,queued),new HashSet<>(renders.jobs(owner,"OOTD").stream().map(PersonalRenders.Job::id).toList()));
        assertEquals(garment,outfits.list(owner).getFirst().scene().elements().getFirst().garmentId());
        assertEquals(queued,handler.candidates().getFirst().businessId());
        assertEquals("fixture",handler.candidates().getFirst().account());
        }
    }
}
