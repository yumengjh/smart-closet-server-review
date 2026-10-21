package com.smartcloset.preset;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcloset.ClosetProperties;
import com.smartcloset.garment.*;
import com.smartcloset.image.ProcessingException;
import com.smartcloset.recommend.*;
import com.smartcloset.security.*;
import com.smartcloset.semantic.GarmentEmbeddings;
import com.smartcloset.similar.*;
import com.smartcloset.storage.ImageVariants;
import com.smartcloset.workflow.*;
import com.smartcloset.workflow.persistence.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/** Real H2/Flyway, files and domain services; no application context, network, workers or installed models. */
public class PresetGarmentsTest {
    static { System.setProperty("java.awt.headless","true"); }
    @TempDir Path root;
    JdbcTemplate db;
    com.zaxxer.hikari.HikariDataSource data;
    GarmentRepository garments;
    WorkflowFiles files;
    WorkflowRepository workflows;
    ImageStyles imageStyles;
    PresetGarments presets;
    ClosetOwnership ownership;
    ClosetAccountService accounts;
    JsonMapper json=JsonMapper.builder().build();
    ClosetProperties properties;
    byte[] picture;

    @BeforeEach void setup() throws Exception {
        data=new com.zaxxer.hikari.HikariDataSource();
        data.setJdbcUrl("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1"); data.setUsername("sa"); data.setPassword(""); data.setMaximumPoolSize(4);
        db=new JdbcTemplate(data);
        var migration=Flyway.configure().dataSource(data).locations("classpath:db/migration/h2").load(); migration.migrate();
        var manager=new DataSourceTransactionManager(data);
        properties=new ClosetProperties(root,root.resolve("absent.onnx"),16000000,512,1);
        files=new WorkflowFiles(properties,json);
        garments=new GarmentRepository(db,json,manager,migration);
        // Only the immutable asset lookup is needed; use real SQL instead of a MyBatis application context.
        workflows=new WorkflowRepository(null,null,null,null,json,manager,migration,garments,db,null) {
            @Override public synchronized AssetRow asset(UUID id) {
                return db.queryForObject("SELECT * FROM image_asset WHERE id=?",(r,n) -> {
                    var a=new AssetRow(); a.id=r.getString("id"); a.jobId=r.getString("job_id"); a.fileName=r.getString("file_name");
                    a.sha256=r.getString("sha256"); a.byteSize=r.getLong("byte_size"); a.width=r.getInt("width"); a.height=r.getInt("height");
                    a.kind=r.getString("kind"); a.reviewStatus=r.getString("review_status"); a.createdAtMs=r.getLong("created_at_ms"); return a;
                },id.toString());
            }
        };
        imageStyles=new ImageStyles(db); presets=new PresetGarments(db,json,garments,workflows,files,imageStyles,manager);
        ownership=new ClosetOwnership(db);
        accounts=new ClosetAccountService(db,new BCryptPasswordEncoder(4),new ClosetSessionRevoker(db,null));
        for (String name:List.of("source","alice","bob","admin")) accounts.create(name,"RegisteredPassword123!",name.equals("admin")?"ADMIN":"USER",null,"ADMIN",null,false);
        var image=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);
        for (int y=8;y<56;y++) for (int x=10;x<54;x++) image.setRGB(x,y,0xff336699);
        var out=new ByteArrayOutputStream(); ImageIO.write(image,"png",out); picture=out.toByteArray();
        signIn("admin");
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); db.execute("SHUTDOWN"); data.close(); }
    void signIn(String name) { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name,"",List.of(new SimpleGrantedAuthority(name.equals("admin")?"ROLE_ADMIN":"ROLE_USER")))); }
    Map<String,List<String>> fields() {
        return new LinkedHashMap<>(Map.of("category",List.of("衬衫"),"primaryColor",List.of("蓝色"),"genderFit",List.of("中性"),"seasons",List.of("春","秋"),"styles",List.of("通勤")));
    }
    public UUID source(Map<String,List<String>> fields) throws Exception {
        UUID id=UUID.randomUUID(), job=UUID.randomUUID(); long now=System.currentTimeMillis();
        var a=files.write(job,"step-0.png","STICKER",picture);
        db.update("INSERT INTO workflow_job(id,fingerprint,status,created_at_ms,updated_at_ms,request_json,requires_review,version,owner_username) VALUES(?,?,'SUCCEEDED',?,?,'{}',0,0,'source')",job.toString(),a.sha256,now,now);
        asset(a);
        db.update("INSERT INTO garment(id,source_asset_id,display_asset_id,source_origin,overrides_json,display_user_selected,revision,created_at_ms,updated_at_ms,owner_username,closet_status,name,favorite) VALUES(?,?,?,'USER_UPLOAD',?,0,0,?,?,'source','ACTIVE','蓝衬衫',TRUE)",id.toString(),a.id,a.id,json.writeValueAsString(fields),now,now);
        db.update("UPDATE workflow_job SET garment_id=? WHERE id=?",id.toString(),job.toString());
        return id;
    }
    void asset(AssetRow a) {
        db.update("INSERT INTO image_asset(id,job_id,file_name,sha256,byte_size,width,height,kind,review_status,created_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?)",a.id,a.jobId,a.fileName,a.sha256,a.byteSize,a.width,a.height,a.kind,a.reviewStatus,a.createdAtMs);
    }
    PresetGarments.Preset draft(UUID source) throws Exception { signIn("admin"); return presets.importDrafts(new PresetGarments.AdminImport("source",List.of(source))).getFirst(); }
    PresetGarments.Preset listed(UUID source) throws Exception { var p=draft(source); return presets.edit(p.id(),new PresetGarments.Edit("LISTED","中性",List.of("知性"),List.of("春","秋"),2)); }
    UUID importAs(String user,UUID preset) { signIn(user); return presets.importIntoCloset(new PresetGarments.Import(List.of(preset))).garmentIds().getFirst(); }
    int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }

    @Test void snapshotFiltersPrivateFieldsAndNeverCopiesPrivateHistory() throws Exception {
        var f=fields(); for (String key:List.of("visibleText","size","careInstructions","brand","materialComposition")) f.put(key,List.of("private-value"));
        UUID source=source(f);
        var automatic=new com.smartcloset.recognition.Recognition.Result(com.smartcloset.recognition.Recognition.Assessment.READY,
                Map.of("primaryColor",new com.smartcloset.recognition.Recognition.Attribute(List.of("黑色"),com.smartcloset.recognition.Recognition.State.OBSERVED,"private-evidence"),
                       "patterns",new com.smartcloset.recognition.Recognition.Attribute(List.of("纯色"),com.smartcloset.recognition.Recognition.State.OBSERVED,"private-evidence")),List.of(),
                new com.smartcloset.recognition.Recognition.Target("private-target",false,null));
        db.update("UPDATE garment SET automatic_json=? WHERE id=?",json.writeValueAsString(automatic),source.toString());
        var p=listed(source);
        assertTrue(p.fields().keySet().containsAll(Set.of("category","primaryColor","genderFit","seasons","styles","patterns")));
        for (String key:List.of("visibleText","size","careInstructions","brand","materialComposition")) assertFalse(p.fields().containsKey(key));
        assertFalse(json.writeValueAsString(p).contains("private-"));
        assertEquals(List.of("蓝色"),p.fields().get("primaryColor")); assertEquals(List.of("纯色"),p.fields().get("patterns"));
        assertNotEquals(garments.get(source).displayAssetId(),p.displayAssetId());
        UUID imported=importAs("alice",p.id()); var view=garments.get(imported);
        p.fields().forEach((key,value)->assertEquals(value,view.effective().get(key))); assertNull(view.automatic()); assertEquals(p.fields(),view.overrides());
        assertFalse(view.favorite()); assertEquals("ACTIVE",view.status()); assertEquals("PRESET",view.sourceOrigin()); assertEquals("蓝衬衫",view.name());
        for (String table:List.of("wear_log","recommendation_feedback","outfit","person_baseline","image_render_job")) assertEquals(0,count(table));
        assertEquals(0,count("ai_daily_quota"),"copying data consumes no model quota");
        assertEquals(PresetOwner.USERNAME,db.queryForObject("SELECT j.owner_username FROM image_asset a JOIN workflow_job j ON j.id=a.job_id WHERE a.id=?",String.class,p.displayAssetId().toString()));
    }
    @Test void simultaneousImportsCreateOnlyOneCopy() throws Exception {
        var p=listed(source(fields()));
        try (var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start=new java.util.concurrent.CountDownLatch(1);
            var requests=new ArrayList<java.util.concurrent.Future<PresetGarments.Imported>>();
            for (int i=0;i<2;i++) requests.add(executor.submit(() -> {
                signIn("alice"); start.await();
                try { return presets.importIntoCloset(new PresetGarments.Import(List.of(p.id()))); }
                finally { SecurityContextHolder.clearContext(); }
            }));
            start.countDown();
            int created=0,skipped=0;
            for (var result:requests) { var imported=result.get(10,java.util.concurrent.TimeUnit.SECONDS); created+=imported.garmentIds().size(); skipped+=imported.skipped().size(); }
            assertEquals(1,created); assertEquals(1,skipped);
        }
    }
    @Test void imageBytesCopiedOnceByShaAndDraftReimportIsIdempotent() throws Exception {
        UUID first=source(fields()), second=source(fields()); var a=draft(first); var b=draft(second);
        assertEquals(a.displayAssetId(),b.displayAssetId()); assertEquals(1,count("preset_image"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM workflow_job WHERE owner_username=?",Integer.class,PresetOwner.USERNAME));
        assertArrayEquals(picture,files.readVerified(workflows.asset(a.displayAssetId())));
        var current=garments.get(first); garments.edit(first,new GarmentRepository.Edit(current.revision(),Map.of("primaryColor",List.of("红色")),null));
        var updated=draft(first); assertEquals(a.id(),updated.id()); assertEquals(List.of("红色"),updated.fields().get("primaryColor"));
        assertEquals(2,count("preset_garment"));
        var approved=presets.edit(a.id(),new PresetGarments.Edit("LISTED",null,null,null,null));
        current=garments.get(first); garments.edit(first,new GarmentRepository.Edit(current.revision(),Map.of("primaryColor",List.of("绿色")),null));
        assertEquals(approved,draft(first),"listed snapshots are frozen on reimport");
    }
    /** The account a preset was made from (the demo account) never gets a duplicate of its own garment. */
    @Test void theSourceAccountSeesItsOwnGarmentsAsOwnAndCannotDuplicateThem() throws Exception {
        UUID source=source(fields()); var p=listed(source);
        signIn("source");
        var mine=presets.list(null,null,null).getFirst();
        assertTrue(mine.ownSource()); assertTrue(mine.alreadyImported());
        var result=presets.importIntoCloset(new PresetGarments.Import(List.of(p.id())));
        assertTrue(result.garmentIds().isEmpty()); assertEquals(List.of(p.id()),result.skipped());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM garment WHERE owner_username='source'",Integer.class));
        signIn("alice"); var theirs=presets.list(null,null,null).getFirst();
        assertFalse(theirs.ownSource()); assertFalse(theirs.alreadyImported());
        garments.trash(source,garments.get(source).revision());
        signIn("source"); assertFalse(presets.list(null,null,null).getFirst().ownSource(), "once the original is in the trash the preset can be added back");
    }
    @Test void copiesAreIndependentAndAlreadyImportedIsSkippedIncludingTrash() throws Exception {
        var p=listed(source(fields())); UUID alice=importAs("alice",p.id()),bob=importAs("bob",p.id());
        assertNotEquals(alice,bob); assertEquals(garments.get(alice).displayAssetId(),garments.get(bob).displayAssetId());
        var a=garments.get(alice); garments.edit(alice,new GarmentRepository.Edit(a.revision(),Map.of("primaryColor",List.of("红色")),null));
        assertEquals(List.of("蓝色"),garments.get(bob).effective().get("primaryColor"));
        signIn("admin"); assertEquals(p.fields(),presets.listAdmin("LISTED").getFirst().fields());
        signIn("alice"); var again=presets.importIntoCloset(new PresetGarments.Import(List.of(p.id(),p.id())));
        assertTrue(again.garmentIds().isEmpty()); assertEquals(List.of(p.id()),again.skipped());
        assertTrue(presets.list(null,null,null).getFirst().alreadyImported());
        var trashed=garments.trash(alice,garments.get(alice).revision());
        assertEquals(1,presets.importIntoCloset(new PresetGarments.Import(List.of(p.id()))).skipped().size());
        assertEquals("ACTIVE",garments.restore(alice,trashed.revision()).status());
    }
    @Test void sharedImageCanBeReadButNeverWrittenReviewedOrDeletedByAnotherUser() throws Exception {
        var p=draft(source(fields())); var a=workflows.asset(p.displayAssetId()); UUID job=UUID.fromString(a.jobId);
        for (String user:List.of("alice","bob","admin")) {
            signIn(user); ownership.workflowRead(job); ownership.assetRead(p.displayAssetId());
            assertThrows(ProcessingException.class,()->ownership.workflow(job));
            assertThrows(ProcessingException.class,()->ownership.asset(p.displayAssetId()));
            assertThrows(ProcessingException.class,()->ownership.workflowIfPresent(job));
        }
        SecurityContextHolder.clearContext();
        assertThrows(ProcessingException.class,()->ownership.workflowRead(job));
        assertThrows(ProcessingException.class,()->ownership.assetRead(p.displayAssetId()));
        signIn("bob"); UUID privateId=source(fields()); var privateAsset=garments.get(privateId).displayAssetId();
        assertThrows(ProcessingException.class,()->ownership.assetRead(privateAsset));
    }
    @Test void trashAndPhysicalRowDeletionLeaveSharedFileAndOtherCopiesIntact() throws Exception {
        var p=listed(source(fields())); UUID alice=importAs("alice",p.id()),bob=importAs("bob",p.id());
        signIn("alice"); var trashed=garments.trash(alice,garments.get(alice).revision()); assertEquals("TRASHED",trashed.status());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM workflow_job WHERE garment_id=?",Integer.class,alice.toString()));
        // No garment purge API exists. Exercise physical row deletion directly, including ON DELETE relations.
        db.update("DELETE FROM garment WHERE id=?",alice.toString());
        assertArrayEquals(picture,files.readVerified(workflows.asset(p.displayAssetId())));
        assertEquals(p.displayAssetId(),garments.get(bob).displayAssetId()); assertEquals(1,count("preset_image"));
    }
    @Test void userListFiltersAndUnlistingNeverChangesExistingCopies() throws Exception {
        var p=listed(source(fields())); UUID copy=importAs("alice",p.id());
        assertEquals(1,presets.list("中性",List.of("知性"),"春").size());
        assertTrue(presets.list("女款",null,null).isEmpty()); assertTrue(presets.list(null,List.of("甜"),null).isEmpty()); assertTrue(presets.list(null,null,"冬").isEmpty());
        String publicJson=json.writeValueAsString(presets.list(null,null,null));
        assertFalse(publicJson.contains("sourceOwner")); assertFalse(publicJson.contains("sourceGarmentId"));
        signIn("admin"); presets.edit(p.id(),new PresetGarments.Edit("UNLISTED",null,null,null,null));
        signIn("alice"); assertTrue(presets.list(null,null,null).isEmpty()); assertEquals("ACTIVE",garments.get(copy).status());
        signIn("bob"); assertThrows(ProcessingException.class,()->presets.importIntoCloset(new PresetGarments.Import(List.of(p.id()))));
    }
    @Test void coverageHasExplicitZeroCellsAndUsesOutfitRoleOrder() throws Exception {
        var f=fields(); f.put("category",List.of("连裤袜")); var p=listed(source(f));
        var cells=presets.coverage();
        assertTrue(cells.stream().anyMatch(c->c.gender().equals("中性") && c.role().equals("accessory") && c.style().equals("知性") && c.count()==1 && !c.missing()));
        assertTrue(cells.stream().anyMatch(c->c.gender().equals("男款") && c.role().equals("bottom") && c.style().equals("知性") && c.count()==0 && c.missing()));
        assertEquals(4*6*(StyleLooks.defaults().looks.size()+3),cells.size());
        assertEquals(2,p.sort());
        for (String direction:List.of("都市通勤","高智极简","美式休闲","户外机能","老钱风","潮流运动","工装风","日系清新","巴恩风")) {
            presets.edit(p.id(),new PresetGarments.Edit(null,null,List.of(direction),null,null));
            assertEquals(1,presets.list(null,List.of(direction),null).size());
        }
    }
    @Test void adminOperationsRejectNonAdminAndValidateMetadata() throws Exception {
        var p=draft(source(fields())); signIn("alice");
        assertThrows(ProcessingException.class,()->presets.listAdmin(null)); assertThrows(ProcessingException.class,presets::coverage);
        assertThrows(ProcessingException.class,()->presets.edit(p.id(),new PresetGarments.Edit("LISTED",null,null,null,null)));
        assertThrows(ProcessingException.class,()->presets.importDrafts(new PresetGarments.AdminImport("source",null)));
        signIn("admin");
        assertThrows(ProcessingException.class,()->presets.edit(p.id(),new PresetGarments.Edit("INVALID",null,null,null,null)));
        assertThrows(ProcessingException.class,()->presets.edit(p.id(),new PresetGarments.Edit(null,"invalid",null,null,null)));
        assertThrows(ProcessingException.class,()->presets.edit(p.id(),new PresetGarments.Edit(null,null,List.of("not-a-look"),null,null)));
        assertThrows(ProcessingException.class,()->presets.edit(p.id(),new PresetGarments.Edit(null,null,null,List.of("unknown"),null)));
    }
    @Test void batchImportIsBoundedAtomicAndOnlyActiveSourceGarmentsAreAccepted() throws Exception {
        var source=source(fields()); var p=listed(source); signIn("bob");
        assertThrows(ProcessingException.class,()->presets.importIntoCloset(new PresetGarments.Import(Collections.nCopies(101,p.id()))));
        assertThrows(ProcessingException.class,()->presets.importIntoCloset(new PresetGarments.Import(List.of(p.id(),UUID.randomUUID()))));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM garment WHERE owner_username='bob'",Integer.class));
        signIn("admin"); garments.trash(source,garments.get(source).revision());
        assertThrows(ProcessingException.class,()->draft(source));
    }
    @Test void reservedOwnerCannotBeRegisteredCreatedOrStoredAsAnAccount() {
        var registration=new RegistrationService(db,accounts,new AccountProperties(AccountProperties.Registration.OPEN,null,null,null));
        for (String name:List.of("__preset__","__PRESET__")) {
            assertThrows(IllegalArgumentException.class,()->accounts.create(name,"RegisteredPassword123!","USER"));
            assertThrows(ProcessingException.class,()->registration.register(name,"RegisteredPassword123!",null,null));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update("INSERT INTO closet_user(username,password_hash,role,must_change_password,enabled,created_at_ms) VALUES(?,'x','USER',0,1,0)",name));
        }
    }
    @Test void importedGarmentsEnterColourSemanticAndVisualQueuesAndReuseThumbnailCache() throws Exception {
        var source=source(fields()); imageStyles.setByUser("source",source.toString(),"ILLUSTRATION");
        var p=listed(source); UUID alice=importAs("alice",p.id()),bob=importAs("bob",p.id());
        assertEquals(ImageStyles.Style.ILLUSTRATION,imageStyles.current("alice").get(alice.toString()).style());
        var colors=new GarmentColors(db,json,files,workflows);
        assertEquals(1,colors.pendingOwners().get("alice")); assertEquals(1,colors.index("alice",100)); assertFalse(colors.palettes("alice").isEmpty());
        var semantic=new GarmentEmbeddings(db); assertEquals(1,semantic.pendingOwners("test-model").get("alice"));
        assertEquals(alice.toString(),semantic.stale("alice","test-model",100).getFirst().garmentId());
        var visuals=new GarmentVisuals(db,json,files,workflows); assertEquals(1,visuals.pendingOwners().get("alice")); assertEquals(1,visuals.index("alice",100)); assertFalse(visuals.all("alice").isEmpty());
        // Exercise the real pending query/dispatcher with a model-availability stand-in; no ONNX file is loaded.
        try (var models=new ClipModels(root.resolve("absent-image.onnx"),root.resolve("absent-text.onnx"),root.resolve("absent-vocab.txt"),Duration.ZERO,Duration.ZERO,0,1) {
            @Override public String imageVersion() { return "fixture-model"; }
            @Override public State imageState() { return State.READY; }
        }) {
            var vectors=new GarmentImageVectors(db,files,workflows,models);
            assertEquals(1,vectors.pendingOwners().get("alice")); assertEquals(1,vectors.pending("alice"));
            assertTrue(new ImageIndexHandler(vectors,db).candidates().stream().anyMatch(c->"alice".equals(c.owner())));
        }
        var variants=new ImageVariants(files.store(),properties); var a=workflows.asset(p.displayAssetId()); String key=files.key(UUID.fromString(a.jobId),a.fileName);
        var first=variants.variant(key,32,false); var second=variants.variant(key,32,false);
        assertEquals(first.file(),second.file()); assertEquals(first.etag(),second.etag());
        assertEquals(garments.get(alice).displayUrl(),garments.get(bob).displayUrl());
    }
    @Test void replacingWithAnOwnedImageClearsPresetTagAndKeepsPresetIdAndOtherCopies() throws Exception {
        var p=listed(source(fields())); UUID alice=importAs("alice",p.id()),bob=importAs("bob",p.id()); signIn("alice");
        UUID job=UUID.randomUUID(); var a=files.write(job,"input.png","INPUT",picture); long now=System.currentTimeMillis();
        db.update("INSERT INTO workflow_job(id,fingerprint,status,created_at_ms,updated_at_ms,request_json,requires_review,version,owner_username,garment_id) VALUES(?,?,'SUCCEEDED',?,?,'{}',0,0,'alice',?)",job.toString(),a.sha256,now,now,alice.toString()); asset(a);
        var request=new Workflow.Request(0d,null,null,List.of(Workflow.Step.recognize(null)),alice,Workflow.Origin.USER_UPLOAD);
        var run=new Workflow.Job(job,a.sha256,Workflow.Status.SUCCEEDED,Instant.ofEpochMilli(now),Instant.ofEpochMilli(now),request,files.url(job,a.fileName),null,false,List.of(),null);
        garments.submitted(run,List.of(a)); assertEquals(UUID.fromString(a.id),garments.get(alice).sourceAssetId());
        ownership.asset(UUID.fromString(a.id)); var before=garments.get(alice);
        var selected=garments.select(alice,new GarmentRepository.SelectImage(before.revision(),UUID.fromString(a.id)));
        assertEquals("USER_UPLOAD",selected.sourceOrigin()); assertEquals(p.fields(),selected.overrides());
        assertEquals(p.id().toString(),db.queryForObject("SELECT preset_id FROM garment WHERE id=?",String.class,alice.toString()));
        assertEquals("PRESET",garments.get(bob).sourceOrigin()); assertArrayEquals(picture,files.readVerified(workflows.asset(p.displayAssetId())));
    }
    @Test void automaticImageProgressAndConfirmationAlsoClearPresetOrigin() throws Exception {
        var p=listed(source(fields())); UUID copy=importAs("alice",p.id());
        UUID job=UUID.randomUUID(); var a=files.write(job,"step-0.png","STICKER",picture); long now=System.currentTimeMillis();
        db.update("INSERT INTO workflow_job(id,fingerprint,status,created_at_ms,updated_at_ms,request_json,requires_review,version,owner_username,garment_id) VALUES(?,?,'SUCCEEDED',?,?,'{}',0,0,'alice',?)",job.toString(),a.sha256,now,now,copy.toString()); asset(a);
        db.update("UPDATE garment SET latest_image_job_id=? WHERE id=?",job.toString(),copy.toString());
        var run=new Workflow.Job(job,a.sha256,Workflow.Status.SUCCEEDED,Instant.ofEpochMilli(now),Instant.ofEpochMilli(now),new Workflow.Request(0d,null,null,List.of(),copy,Workflow.Origin.PRESET),null,null,false,List.of(),null);
        garments.progress(run,a); assertEquals("USER_UPLOAD",garments.get(copy).sourceOrigin());
        db.update("UPDATE garment SET source_origin='PRESET',latest_image_job_id=NULL WHERE id=?",copy.toString());
        assertEquals("USER_UPLOAD",garments.confirm(copy,new GarmentRepository.Confirm(garments.get(copy).revision(),UUID.fromString(a.id),null,null)).sourceOrigin());
    }
    @Test void recommendationPresentationAndWearLogsTreatPresetsAsNormalGarments() throws Exception {
        var one=fields(); one.put("category",List.of("连衣裙")); var p=listed(source(one)); UUID copy=importAs("alice",p.id());
        var service=new RecommendationService(garments,new StyleProfileService(db,json),db,json,null);
        var projection=garments.sourcedProjection("alice").getFirst(); var g=projection.projection();
        var item=new GarmentFeatures.Item(g.id().toString(),g.revision(),g.name(),g.favorite(),g.fields(),projection.fieldStatus(),g.wearingConfirmed());
        var engine=RecommendationEngine.defaults();
        var intent=new Intent(Intent.Occasion.DAILY,18.0,"春",null,List.of(),List.of(),List.of(),"",List.of(),List.of(),3,Map.of());
        var result=engine.recommend(List.of(item),intent,StyleProfile.empty(),RecommendationEngine.Settings.defaults());
        assertFalse(result.pool().isEmpty()); var c=result.pool().getFirst();
        RecommendationService.OutfitView view=ReflectionTestUtils.invokeMethod(service,"outfit",c,null,1,intent);
        assertTrue(view.garments().getFirst().preset()); assertEquals(c.score().total(),view.score());
        assertEquals(List.of("含 1 件预设衣物（示意图）"),RecommendationService.presetNotes(List.of(view,view),List.of()));
        assertTrue(result.limits().stream().noneMatch(n->n.contains("预设")));
        service.wear("alice",null,List.of(copy.toString()),LocalDate.now().minusDays(40)); assertEquals(1,count("wear_log"));
        assertEquals(1,service.wearSummary("alice",copy.toString()).get("count"));
        db.update("UPDATE garment SET created_at_ms=? WHERE id=?",System.currentTimeMillis()-50L*86400000,copy.toString());
        var profile=new StyleProfileService(db,json).profile("alice",Map.of(copy.toString(),engine.features(List.of(item)).getFirst()));
        assertTrue(profile.idleDays().get(copy.toString())>=39,"preset wear logs participate in normal idle activation");
        assertEquals("PRESET",garments.listCloset("alice","ACTIVE","","","","",1,24).items().getFirst().sourceOrigin());
    }
}
