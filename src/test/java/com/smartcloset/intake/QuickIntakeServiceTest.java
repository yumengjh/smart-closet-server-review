package com.smartcloset.intake;
import java.nio.file.*;
import java.util.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import com.smartcloset.security.ClosetAccountService;
import com.smartcloset.processing.*;
import com.smartcloset.image.*;
import com.smartcloset.garment.GarmentRepository;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
@SpringBootTest(properties={"spring.config.import=optional:file:./target/no-secrets.yml","closet.model-path=target/no-model.onnx"}) @DirtiesContext
class QuickIntakeServiceTest {
 static Path root;static{try{root=Files.createTempDirectory("quick-intake-test-");}catch(Exception e){throw new RuntimeException(e);}}
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("closet.storage-dir",()->root.toString());}
 @Autowired QuickIntakeService quick;@Autowired ClosetAccountService accounts;@Autowired GarmentRepository garments;@Autowired ProcessingStore store;@Autowired com.smartcloset.workflow.WorkflowFiles files;@Autowired com.smartcloset.workflow.persistence.WorkflowRepository jobs;@Autowired tools.jackson.databind.ObjectMapper json;
 @MockitoBean SegmentationService segmentation;
 @Autowired ProcessingExecutor executor;
 String owner;
 @BeforeEach void prepare()throws Exception{owner="quick-"+UUID.randomUUID();accounts.create(owner,"test-password","USER");login(owner);var rev=((Number)store.configView().get("revision")).longValue();store.setConfig(new ProcessingStore.Config(1,1,1,2,1,null,Set.of("RECOGNITION","GENERATION")),rev,owner);when(segmentation.uploadBytes(any(),any(),any(),anyDouble())).thenThrow(ProcessingException.invalid("Injected local failure"));}
 @AfterEach void clear(){SecurityContextHolder.clearContext();}
 void login(String name){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name,null,java.util.List.of(new SimpleGrantedAuthority("ROLE_USER"))));}
 byte[] photo()throws Exception{var image=new BufferedImage(80,100,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();g.setColor(Color.BLUE);g.fillRect(0,0,80,100);g.dispose();var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();}
 @Test void stableUploadIdentityBothAdmissionModesAndLocalFallback()throws Exception{
  var key=UUID.randomUUID();var option=new QuickIntakeService.Options("BASE_READY",UUID.randomUUID(),null,false);byte[] source=photo();var first=quick.create(key,option,source);assertTrue(first.sourceSaved());assertEquals("DRAFT",first.garment().status());var again=quick.create(key,option,source);assertEquals(first.garmentId(),again.garmentId());
  for(int i=0;i<100&&quick.get(key).garment().status().equals("DRAFT");i++)Thread.sleep(25);var settled=quick.get(key);assertEquals("ACTIVE",settled.garment().status());assertEquals(first.garment().sourceAssetId(),settled.garment().displayAssetId());assertEquals("SKIPPED",settled.task().steps().getFirst().status().name());for(int i=0;i<40&&store.db().queryForObject("SELECT COUNT(*) FROM execution_task WHERE kind='QUICK' AND business_id=?",Integer.class,key.toString())<2;i++)Thread.sleep(25);assertEquals(2,store.db().queryForObject("SELECT COUNT(*) FROM execution_task WHERE kind='QUICK' AND business_id=?",Integer.class,key.toString()));
  var immediate=quick.create(UUID.randomUUID(),new QuickIntakeService.Options("UPLOAD_READY",null,null,false),source);assertEquals("ACTIVE",immediate.garment().status());assertTrue(immediate.sourceSaved());verify(segmentation,atLeast(2)).uploadBytes(any(),any(),any(),anyDouble());
 }
 @Test void markerFreezesPerPhotoAndOtherPhotosContinue()throws Exception{
  var marked=quick.create(UUID.randomUUID(),new QuickIntakeService.Options("BASE_READY",null,null,true),photo());var other=quick.create(UUID.randomUUID(),null,photo());
  for(int i=0;i<120&&(quick.get(other.intakeId()).garment().status().equals("DRAFT")||quick.get(marked.intakeId()).garment().status().equals("DRAFT"));i++)Thread.sleep(25);
  assertEquals("ACTIVE",quick.get(other.intakeId()).garment().status());assertTrue(quick.get(marked.intakeId()).awaitTargetHint());assertEquals(0,store.db().queryForObject("SELECT COUNT(*) FROM execution_task WHERE business_id=? AND stage=1",Integer.class,marked.intakeId().toString()));
  quick.hint(marked.intakeId(),new QuickIntakeService.Hint("BOX",.1,.1,.5,.5),marked.revision());quick.start(marked.intakeId());assertThrows(ProcessingException.class,()->quick.hint(marked.intakeId(),null,marked.revision()));
 }
 @Test void foreignIdentityAndChangedPayloadCannotReplayUpload()throws Exception{
  var key=UUID.randomUUID();var view=quick.create(key,null,photo());assertThrows(ProcessingException.class,()->quick.create(key,new QuickIntakeService.Options("UPLOAD_READY",null,null,false),photo()));login("another-user");assertThrows(ProcessingException.class,()->quick.get(key));assertThrows(ProcessingException.class,()->quick.create(key,null,photo()));assertTrue(quick.provenance(view.garmentId()).isEmpty());
 }
 @Test void deletedGarmentCannotBeRevivedByBaseCompletion()throws Exception{
  var rev=((Number)store.configView().get("revision")).longValue();store.setConfig(new ProcessingStore.Config(1,1,1,2,1,null,Set.of("CPU","RECOGNITION","GENERATION")),rev,owner);
  var view=quick.create(UUID.randomUUID(),new QuickIntakeService.Options("UPLOAD_READY",null,null,false),photo());store.db().update("UPDATE garment SET closet_status='TRASHED' WHERE id=?",view.garmentId().toString());
  var row=new ProcessingStore.Task("fixture","fixture",owner,"QUICK",view.intakeId().toString(),0,"CPU",null,100,"RUNNING",0,0,null,null);quick.execute(row);assertEquals("TRASHED",garments.get(view.garmentId()).status());
 }
 @Test void returnedGenerationJournalRecoversBeforeImageCommitWithoutNewPaidAttempt()throws Exception{
  store.setConfig(new ProcessingStore.Config(1,1,1,2,1,null,Set.of("CPU","RECOGNITION","GENERATION")),((Number)store.configView().get("revision")).longValue(),owner);
  var view=quick.create(UUID.randomUUID(),new QuickIntakeService.Options("UPLOAD_READY",null,null,false),photo());var job=jobs.get(view.intakeId());var steps=new ArrayList<>(job.steps());
  for(int i=0;i<2;i++)steps.set(i,new com.smartcloset.workflow.Workflow.StepResult(i,steps.get(i).type(),com.smartcloset.workflow.Workflow.Status.SKIPPED,null,null,null,null,null,null,null));
  steps.set(2,new com.smartcloset.workflow.Workflow.StepResult(2,steps.get(2).type(),com.smartcloset.workflow.Workflow.Status.RUNNING,null,null,null,null,null,null,null));
  jobs.save(new com.smartcloset.workflow.Workflow.Job(job.id(),job.fingerprint(),com.smartcloset.workflow.Workflow.Status.RUNNING,job.createdAt(),job.updatedAt(),job.request(),job.inputUrl(),job.outputUrl(),false,steps,null),null);
  store.enqueue("QUICK",job.id().toString(),2,owner,"GENERATION",null,20);var task=store.db().queryForObject("SELECT id FROM execution_task WHERE business_id=? AND stage=2",(r,n)->store.get(r.getString(1)),job.id().toString());store.claim(task);String attempt=store.attempt(task,1);store.dispatched(attempt);
  var output=new com.smartcloset.generation.GenerationService.Output(photo(),"unconfigured-fixture","fixture-model",com.smartcloset.generation.GenerationProperties.Protocol.OPENAI_IMAGES,null,"known-result");files.data(job.id(),"quick-generation.json",json.writeValueAsBytes(output));store.recover();assertEquals("UNKNOWN",store.get(task.id()).status());
  quick.retry(store.get(task.id()));assertEquals("SUCCEEDED",store.get(task.id()).status());var asset=jobs.assetByFile(job.id(),"step-2.png");assertArrayEquals(photo(),files.readVerified(asset));assertEquals("AI_ENHANCED",asset.kind);assertEquals(1,store.db().queryForObject("SELECT COUNT(*) FROM execution_attempt WHERE task_id=?",Integer.class,task.id()));assertEquals(1,store.db().queryForObject("SELECT used FROM ai_daily_quota WHERE owner_username=?",Integer.class,owner));
 }
 @Test void selectingTheCurrentImageStillRevokesEarlierAutomaticDisplayAuthorization()throws Exception{
  store.setConfig(new ProcessingStore.Config(1,1,1,2,1,null,Set.of("CPU","RECOGNITION","GENERATION")),((Number)store.configView().get("revision")).longValue(),owner);
  var v=quick.create(UUID.randomUUID(),new QuickIntakeService.Options("UPLOAD_READY",null,null,false),photo());var first=garments.select(v.garmentId(),new GarmentRepository.SelectImage(v.garment().revision(),v.garment().displayAssetId()));assertEquals(v.revision()+1,quick.get(v.intakeId()).revision());
  garments.select(v.garmentId(),new GarmentRepository.SelectImage(first.revision(),first.displayAssetId()));assertEquals(v.revision()+2,quick.get(v.intakeId()).revision());assertEquals(v.garment().displayAssetId(),garments.get(v.garmentId()).displayAssetId());
 }
 @Test @DirtiesContext(methodMode=DirtiesContext.MethodMode.AFTER_METHOD)
 void localStickerIsPublishedBeforeRecognitionAndCleanReferenceSurvivesRecovery()throws Exception{
  // This test drives crash/replay manually. Stop the background scanner so it
  // cannot replay the same receipt between our snapshot and display selection.
  executor.close();
  store.setConfig(new ProcessingStore.Config(1,1,1,2,1,null,Set.of("CPU","RECOGNITION","GENERATION")),((Number)store.configView().get("revision")).longValue(),owner);
  var cutout=new BufferedImage(80,100,BufferedImage.TYPE_INT_ARGB);var g=cutout.createGraphics();g.setColor(Color.BLUE);g.fillRect(20,15,40,70);g.dispose();var bytes=new ByteArrayOutputStream();ImageIO.write(cutout,"png",bytes);
  var path=Files.createTempFile(root,"cutout-",".png");Files.write(path,bytes.toByteArray());var segmentationId=UUID.randomUUID();
  doReturn(new SegmentationResult(segmentationId.toString(),java.time.Instant.now(),SegmentationEngine.Method.U2NET,"fixture",80,100,1,null,null,1,.5,java.util.List.of(),java.util.List.of(),null,null,null,null,null,0d)).when(segmentation).uploadBytes(any(),any(),any(),anyDouble());
  when(segmentation.file(segmentationId,"cutout.png")).thenReturn(path);
  var view=quick.create(UUID.randomUUID(),null,photo());var task=new ProcessingStore.Task("fixture","fixture",owner,"QUICK",view.intakeId().toString(),0,"CPU",null,100,"RUNNING",0,0,null,null);quick.execute(task);
  var ready=quick.get(view.intakeId());assertEquals("ACTIVE",ready.garment().status());assertEquals("PENDING",ready.task().steps().get(1).status().name());assertTrue(ready.garment().imageProcessing().stickerReady());
  var base=jobs.assetByFile(view.intakeId(),"base-cutout.png");var sticker=jobs.assetByFile(view.intakeId(),"step-0.png");assertEquals("BASE",base.kind);assertEquals("BASE_STICKER",sticker.kind);assertNotEquals(base.id,sticker.id);assertArrayEquals(bytes.toByteArray(),files.readVerified(base));assertEquals(UUID.fromString(sticker.id),ready.garment().displayAssetId());
  var rendered=ImageIO.read(new ByteArrayInputStream(files.readVerified(sticker)));assertEquals(1024,rendered.getWidth());assertEquals(0,rendered.getRGB(0,0)>>>24);boolean outline=false;for(int y=0;y<rendered.getHeight()&&!outline;y++)for(int x=0;x<rendered.getWidth();x++)if((rendered.getRGB(x,y)>>>24)>250 && (rendered.getRGB(x,y)&255)>240 && ((rendered.getRGB(x,y)>>>8)&255)>240 && ((rendered.getRGB(x,y)>>>16)&255)>240){outline=true;break;}assertTrue(outline,"Display sticker must have a visible white outline");
  assertEquals(base.id,store.db().queryForObject("SELECT base_asset_id FROM quick_intake WHERE id=?",String.class,view.intakeId().toString()));
  // Interrupt after the durable receipt; replay must keep both the clean reference and a later manual display selection.
  garments.select(view.garmentId(),new GarmentRepository.SelectImage(ready.garment().revision(),ready.garment().sourceAssetId()));
  var job=jobs.get(view.intakeId());var steps=new ArrayList<>(job.steps());var step=steps.getFirst();steps.set(0,new com.smartcloset.workflow.Workflow.StepResult(0,step.type(),com.smartcloset.workflow.Workflow.Status.RUNNING,null,null,null,null,null,null,null));
  jobs.save(new com.smartcloset.workflow.Workflow.Job(job.id(),job.fingerprint(),com.smartcloset.workflow.Workflow.Status.RUNNING,job.createdAt(),job.updatedAt(),job.request(),job.inputUrl(),job.outputUrl(),false,steps,null),null);
  store.db().update("UPDATE quick_intake SET base_asset_id=NULL WHERE id=?",job.id().toString());org.springframework.test.util.ReflectionTestUtils.invokeMethod(quick,"recover",new com.smartcloset.workflow.Workflow.Job(job.id(),job.fingerprint(),com.smartcloset.workflow.Workflow.Status.RUNNING,job.createdAt(),job.updatedAt(),job.request(),job.inputUrl(),job.outputUrl(),false,steps,null));
  assertEquals(base.id,store.db().queryForObject("SELECT base_asset_id FROM quick_intake WHERE id=?",String.class,job.id().toString()));assertEquals(ready.garment().sourceAssetId(),quick.get(job.id()).garment().displayAssetId());verify(segmentation,times(1)).uploadBytes(any(),any(),any(),anyDouble());
 }
 @Test void recoveredBlockedRecognitionStillSkipsEnhancementWithoutDispatch()throws Exception{
  store.setConfig(new ProcessingStore.Config(1,1,1,2,1,null,Set.of("CPU","RECOGNITION","GENERATION")),((Number)store.configView().get("revision")).longValue(),owner);
  var view=quick.create(UUID.randomUUID(),new QuickIntakeService.Options("UPLOAD_READY",null,null,true),photo());
  var job=jobs.get(view.intakeId());var steps=new ArrayList<>(job.steps());
  steps.set(0,new com.smartcloset.workflow.Workflow.StepResult(0,steps.get(0).type(),com.smartcloset.workflow.Workflow.Status.SKIPPED,null,null,null,null,null,null,null));
  steps.set(1,new com.smartcloset.workflow.Workflow.StepResult(1,steps.get(1).type(),com.smartcloset.workflow.Workflow.Status.RUNNING,null,null,null,null,null,null,null));
  // Keep the dispatcher out of this interrupted snapshot while exercising receipt recovery.
  var interrupted=new com.smartcloset.workflow.Workflow.Job(job.id(),job.fingerprint(),com.smartcloset.workflow.Workflow.Status.NEEDS_REVIEW,job.createdAt(),job.updatedAt(),job.request(),job.inputUrl(),job.outputUrl(),false,steps,null);
  jobs.save(interrupted,null);
  var metadata=new com.smartcloset.recognition.Recognition.Result(com.smartcloset.recognition.Recognition.Assessment.MULTIPLE_GARMENTS,Map.of(),java.util.List.of(),null);
  var returned=new com.smartcloset.workflow.Workflow.StepResult(1,steps.get(1).type(),com.smartcloset.workflow.Workflow.Status.SUCCEEDED,null,null,job.inputUrl(),null,null,null,null,null,metadata,null);
  files.receipt(job.id(),new com.smartcloset.workflow.WorkflowFiles.Receipt(returned,null,"returned-response"));
  org.springframework.test.util.ReflectionTestUtils.invokeMethod(quick,"recover",interrupted);
  var restored=quick.get(job.id());assertEquals("NEEDS_REVIEW",restored.task().status().name());assertEquals("SUCCEEDED",restored.task().steps().get(1).status().name());
  assertTrue(restored.task().steps().subList(2,4).stream().allMatch(step->step.status()==com.smartcloset.workflow.Workflow.Status.SKIPPED));
  assertEquals(0,store.db().queryForObject("SELECT COUNT(*) FROM execution_attempt a JOIN execution_task t ON a.task_id=t.id WHERE t.business_id=? AND a.dispatched=1",Integer.class,job.id().toString()));
 }
 @Test void malformedLocationNeverCreatesWorkflow()throws Exception{int before=store.db().queryForObject("SELECT COUNT(*) FROM workflow_job",Integer.class);assertThrows(ProcessingException.class,()->quick.create(UUID.randomUUID(),new QuickIntakeService.Options("BASE_READY",null,new QuickIntakeService.Hint("BOX",.8,.2,.4,.4),false),photo()));assertEquals(before,store.db().queryForObject("SELECT COUNT(*) FROM workflow_job",Integer.class));}
}
