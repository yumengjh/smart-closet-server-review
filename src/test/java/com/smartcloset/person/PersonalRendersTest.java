package com.smartcloset.person;

import com.smartcloset.security.ClosetAccountService;
import com.smartcloset.agent.CompositionReviews;
import com.smartcloset.outfit.OutfitRepository;
import com.smartcloset.workflow.WorkflowFiles;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.config.import=optional:file:./target/no-local-secrets.yml","closet.model-path=target/absent-person-model.onnx","generation.providers.fixture.protocol=OPENAI_IMAGES","generation.providers.fixture.api-key=fake-local-test","generation.providers.fixture.default-model=fixture-image"})
class PersonalRendersTest {
 @TempDir static Path storage;static final HttpServer upstream;static final AtomicInteger calls=new AtomicInteger();static final AtomicReference<CountDownLatch> blocker=new AtomicReference<>();static final AtomicReference<CountDownLatch> entered=new AtomicReference<>();static final byte[] PNG;
 static {try{var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(64,96,BufferedImage.TYPE_INT_RGB),"png",bytes);PNG=bytes.toByteArray();upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);upstream.createContext("/v1/images",exchange->{exchange.getRequestBody().readAllBytes();calls.incrementAndGet();var start=entered.getAndSet(null);if(start!=null)start.countDown();var wait=blocker.getAndSet(null);if(wait!=null)try{wait.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}byte[] response=("{\"data\":[{\"b64_json\":\""+Base64.getEncoder().encodeToString(PNG)+"\"}]}").getBytes();exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();});upstream.start();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
 @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("closet.storage-dir",()->storage.toString());r.add("generation.providers.fixture.base-url",()->"http://127.0.0.1:"+upstream.getAddress().getPort()+"/v1");}
 @AfterAll static void shutdown(){upstream.stop(0);}
 @Autowired org.springframework.transaction.PlatformTransactionManager transactions; @Autowired com.smartcloset.processing.ProcessingStore processing; @Autowired com.smartcloset.agent.AgentRenderCommands renderCommands; @Autowired PersonalRenders renders;@Autowired WearerProfiles profiles;@Autowired RenderCapabilities capabilities;@Autowired PrivateMedia media;@Autowired ClosetAccountService accounts;@Autowired JdbcTemplate db;@Autowired WorkflowFiles files;@Autowired OutfitRepository outfits;@Autowired ObjectMapper json;
 private String owner(){String owner="person-"+UUID.randomUUID().toString().substring(0,8);accounts.create(owner,"test-person-password","USER");return owner;}
 private void verified(String purpose){var current=capabilities.list().stream().filter(c->c.provider().equals("fixture")&&c.purpose().equals(purpose)).findFirst();capabilities.update(new RenderCapabilities.Capability("fixture","fixture-image",purpose,10,true,current.map(RenderCapabilities.Capability::version).orElse(0L),null,null,"本地模拟协议测试；不代表真实模型视觉质量"));}
 private PersonalRenders.Job completed(String owner,String id)throws Exception{long end=System.currentTimeMillis()+10000;PersonalRenders.Job j;do{renders.sync();j=renders.job(owner,id);if(!Set.of("QUEUED","RUNNING","CANCEL_REQUESTED","WAITING_RESOURCE").contains(j.status()))return j;Thread.sleep(25);}while(System.currentTimeMillis()<end);fail("Task did not settle: "+j);return j;}
 private UUID garment(String owner,String category)throws Exception {
  UUID id=UUID.randomUUID(),job=UUID.randomUUID();long now=System.currentTimeMillis();var asset=files.write(job,"input.png","INPUT",PNG);
  db.update("INSERT INTO workflow_job(id,fingerprint,status,created_at_ms,updated_at_ms,request_json,requires_review,version,owner_username) VALUES(?,?,'SUCCEEDED',?,?,'{}',0,0,?)",job.toString(),job.toString(),now,now,owner);
  db.update("INSERT INTO image_asset(id,job_id,file_name,sha256,byte_size,width,height,kind,review_status,created_at_ms) VALUES(?,?,?,?,?,?,?,'INPUT','APPROVED',?)",asset.id,job.toString(),asset.fileName,asset.sha256,asset.byteSize,asset.width,asset.height,now);
  db.update("INSERT INTO garment(id,source_asset_id,display_asset_id,source_origin,overrides_json,display_user_selected,revision,created_at_ms,updated_at_ms,owner_username,closet_status) VALUES(?,?,?,'USER_UPLOAD',?,0,0,?,?,?,'ACTIVE')",id.toString(),asset.id,asset.id,json.writeValueAsString(Map.of("category",List.of(category))),now,now,owner);db.update("UPDATE workflow_job SET garment_id=? WHERE id=?",id.toString(),job.toString());return id;
 }
 private OutfitRepository.View outfit(String owner,UUID... garments){var elements=new ArrayList<Map<String,Object>>();int index=0;for(UUID id:garments){String asset=db.queryForObject("SELECT source_asset_id FROM garment WHERE id=?",String.class,id.toString());var e=new LinkedHashMap<String,Object>();e.put("garmentId",id);e.put("assetId",asset);e.put("role",index++==0?"TOP":"BOTTOM");e.put("x",100);e.put("y",200);e.put("width",150);e.put("height",220);e.put("rotation",0);e.put("zIndex",index-1);e.put("garmentLabel","测试衣物");e.put("cropX",0);e.put("cropY",0);e.put("cropWidth",1);e.put("cropHeight",1);e.put("elementId",UUID.randomUUID());e.put("kind","GARMENT");elements.add(e);}var scene=json.convertValue(Map.of("version",2,"elements",elements),OutfitRepository.SceneWrite.class);return outfits.create(new OutfitRepository.SaveRequest(null,"模拟搭配",scene),owner);}
 @Test void privateRenderChainIsSingleDispatchUsesSavedInputsAndSeparatesDeletion()throws Exception {
  String owner=owner();int before=calls.get();assertThrows(com.smartcloset.image.ProcessingException.class,()->renders.generateBaseline(owner,"missing-sex",new PersonalRenders.BaselineRequest(0,"fixture","fixture-image",null)));assertEquals(before,calls.get());
  var p=profiles.update(owner,new WearerProfiles.Profile(0,"FEMALE",null,null,null,Map.of(),Map.of(),null));assertNull(p.heightCm());assertNull(p.weightKg());verified("BASELINE");verified("OOTD");
  var photo=media.put(owner,"PHOTO",PNG);var request=new PersonalRenders.BaselineRequest(p.revision(),"fixture","fixture-image",photo.id());var j=renders.generateBaseline(owner,"baseline-once",request);assertEquals(j.id(),renders.generateBaseline(owner,"baseline-once",request).id());var done=completed(owner,j.id());assertEquals("SUCCEEDED",done.status(),done.error());assertEquals(before+1,calls.get());
  var candidate=renders.baseline(owner,j.baselineId());assertEquals("CANDIDATE",candidate.status());assertNull(profiles.get(owner).defaultBaselineId());assertEquals(160,candidate.profile().heightCm());assertEquals(56.3,candidate.profile().weightKg());var baseline=renders.activate(owner,candidate.id(),candidate.revision());
  UUID top=garment(owner,"衬衫"),bottom=garment(owner,"长裤");var saved=outfit(owner,top,bottom);var preparation=renders.prepare(owner,new PersonalRenders.PreparationRequest(saved.id(),saved.revision(),UUID.fromString(baseline.id()),baseline.revision(),List.of(top,bottom),null,false,"fixture","fixture-image",false));assertTrue(preparation.review().passed());assertEquals("NO_SHOES",preparation.review().warnings().getFirst().code());
  media.delete(owner,photo.id());assertThrows(com.smartcloset.image.ProcessingException.class,()->media.read(owner,photo.id()));
  var ootd=renders.submitOotd(owner,"ootd-once",preparation.id());assertEquals(ootd.id(),renders.submitOotd(owner,"ootd-once",preparation.id()).id());assertEquals(ootd.id(),renders.submitOotd(owner,"different-client-key",preparation.id()).id());var frozen=renders.input(ootd.id());assertEquals(3,frozen.references().size());assertTrue(frozen.references().stream().noneMatch(ref->photo.id().toString().equals(ref.mediaId())));assertTrue(frozen.generation().prompt().contains("赤脚"));var output=completed(owner,ootd.id());assertEquals("SUCCEEDED",output.status(),output.error());assertEquals(before+2,calls.get());
  String stranger=owner();UUID result=UUID.fromString(output.mediaUrl().substring(output.mediaUrl().lastIndexOf('/')+1));assertThrows(com.smartcloset.image.ProcessingException.class,()->media.read(stranger,result));assertThrows(com.smartcloset.image.ProcessingException.class,()->renders.job(stranger,ootd.id()));
  var changed=renders.prepare(owner,new PersonalRenders.PreparationRequest(saved.id(),saved.revision(),UUID.fromString(baseline.id()),baseline.revision(),List.of(top,bottom),null,false,"fixture","fixture-image",false));db.update("UPDATE garment SET revision=revision+1 WHERE id=?",top.toString());assertThrows(com.smartcloset.image.ProcessingException.class,()->renders.submitOotd(owner,"stale-version",changed.id()));assertEquals(before+2,calls.get());
  var fresh=renders.prepare(owner,new PersonalRenders.PreparationRequest(saved.id(),saved.revision(),UUID.fromString(baseline.id()),baseline.revision(),List.of(top,bottom),null,false,"fixture","fixture-image",false));var waiting=new CountDownLatch(1);var start=new CountDownLatch(1);entered.set(start);blocker.set(waiting);var late=renders.submitOotd(owner,"late-output",fresh.id());assertTrue(start.await(5,TimeUnit.SECONDS));renders.deleteBaseline(owner,baseline.id(),baseline.revision());waiting.countDown();var stopped=completed(owner,late.id());assertNotEquals("SUCCEEDED",stopped.status());assertNull(stopped.mediaUrl());assertTrue(media.read(owner,result).length>0,"Old OOTD survives source baseline deletion");
  renders.delete(owner,ootd.id());assertThrows(com.smartcloset.image.ProcessingException.class,()->media.read(owner,result));assertTrue(renders.jobs(owner,"OOTD").stream().noneMatch(job->job.id().equals(ootd.id())));
 }
 @Test void commandPreparationIsFreeAndRequiresSpecificConfirmation()throws Exception {
  String owner=owner();var p=profiles.update(owner,new WearerProfiles.Profile(0,"MALE",185d,null,null,Map.of(),Map.of(),null));verified("BASELINE");int before=calls.get();
  var input=json.valueToTree(Map.of("purpose","BASELINE","provider","fixture","model","fixture-image"));var prepared=renderCommands.prepare(owner,"baseline-command",input);assertEquals("PREPARED",prepared.status());assertEquals(75.3,prepared.parameters().path("profile").path("weightKg").asDouble());assertEquals(before,calls.get());
  var confirmed=renderCommands.execute(owner,prepared.id());String jobId=confirmed.result().path("id").asText();assertEquals(jobId,renderCommands.execute(owner,prepared.id()).result().path("id").asText());assertEquals("SUCCEEDED",completed(owner,jobId).status());assertEquals(before+1,calls.get());
  var stale=renderCommands.prepare(owner,"stale-command",input);profiles.update(owner,new WearerProfiles.Profile(p.revision(),"MALE",190d,null,null,Map.of(),Map.of(),null));assertThrows(com.smartcloset.image.ProcessingException.class,()->renderCommands.execute(owner,stale.id()));assertEquals(before+1,calls.get());
 }
 @Test void tombstonesPreventRestoringDeletedFilesAndUndispatchedRecoveryResumesLocally()throws Exception {
  String owner=owner();var orphan=storage.resolve("private-person").resolve(UUID.randomUUID()+".png");java.nio.file.Files.write(orphan,PNG);java.nio.file.Files.setLastModifiedTime(orphan,java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()-7200000));media.purgeOrphans(System.currentTimeMillis());assertFalse(java.nio.file.Files.exists(orphan));
  var portrait=media.put(owner,"PHOTO",PNG);media.delete(owner,portrait.id());db.update("UPDATE private_media SET state='ACTIVE' WHERE id=?",portrait.id().toString());media.purgePending();assertFalse(media.available(owner,portrait.id(),0));
  var p=profiles.update(owner,new WearerProfiles.Profile(0,"MALE",null,null,null,Map.of(),Map.of(),null));verified("BASELINE");String id=UUID.randomUUID().toString();long now=System.currentTimeMillis();
  new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(t->{
  db.update("INSERT INTO image_render_job(id,owner_username,purpose,input_json,status,idempotency_key,created_at_ms,updated_at_ms) VALUES(?,?,'BASELINE','{}','RUNNING',?,?,?)",id,owner,id,now,now);
  processing.enqueue("RENDER",id,0,owner,"GENERATION","fixture",50,"{}");
  renders.sync();assertEquals("QUEUED",renders.job(owner,id).status());renders.cancel(owner,id);
  });
 }
}
