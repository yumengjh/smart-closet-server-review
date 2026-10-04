package com.smartcloset.batch;
import com.smartcloset.generation.*;
import com.smartcloset.recognition.*;
import com.smartcloset.security.*;
import com.smartcloset.workflow.WorkflowService;
import com.smartcloset.image.ProcessingException;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.smartcloset.batch.Batch.*;
@SpringBootTest(properties={"spring.config.import=optional:file:./target/no-secrets.yml","closet.model-path=target/no-model.onnx"})
@DirtiesContext
class BatchServiceTest {
    static Path storage;
    static {try{storage=Files.createTempDirectory("closet-batch-test-");}catch(Exception e){throw new RuntimeException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("closet.storage-dir",()->storage.toString());
        p.add("generation.providers.test.protocol",()->"OPENAI_IMAGES");p.add("generation.providers.test.base-url",()->"http://127.0.0.1:1/v1");p.add("generation.providers.test.api-key",()->"test-secret");
    }
    @Autowired BatchService batches;@Autowired ClosetAccountService accounts;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired WorkflowService workflows;
    @MockitoBean RecognitionService recognition;@MockitoBean GenerationService generation;
    String username;AtomicInteger recognized;
    @BeforeEach void setup() throws Exception {
        username="batch-"+UUID.randomUUID();accounts.create(username,"test-only-password", "ADMIN");login(username);recognized=new AtomicInteger();
        when(recognition.prepareBatch(any(),any(),anyString())).thenAnswer(i->new Recognition.Prepared("test","gpt-6-luna","revision","v1","batch-v1","prompt",i.getArgument(1)));
        when(recognition.raw(any(),any(),anyString())).thenAnswer(i->{recognized.incrementAndGet();return new RecognitionService.RawOutput(json.valueToTree(Map.of("items",List.of(BatchRulesTest.item(.9,true,-1)))),json.valueToTree(Map.of("prompt_tokens",1000,"completion_tokens",100,"prompt_tokens_details",Map.of("cached_tokens",0))),"recognize-id");});
        when(generation.prepare(any())).thenAnswer(i->{GenerationService.Request r=i.getArgument(0);return new GenerationService.Prepared("test","gpt-image-2.5-flare",r.prompt(),r.parameters(),GenerationProperties.Protocol.OPENAI_IMAGES);});
        when(generation.revision(any())).thenReturn("frozen");when(generation.frozen(any(),any())).thenAnswer(i->{GenerationService.Request r=i.getArgument(0);return new GenerationService.Prepared(r.provider(),r.model(),r.prompt(),r.parameters(),GenerationProperties.Protocol.OPENAI_IMAGES);});
        when(generation.generate(any(),any())).thenAnswer(i->{GenerationService.Prepared p=i.getArgument(0);String[] size=p.parameters().get("size").toString().split("x");return new GenerationService.Output(BatchImages.png(new BufferedImage(Integer.parseInt(size[0]),Integer.parseInt(size[1]),BufferedImage.TYPE_INT_RGB)),p.provider(),p.model(),p.protocol(),json.valueToTree(Map.of("input_tokens_details",Map.of("image_tokens",1000,"text_tokens",100),"output_tokens",1000)),"image-id");});
    }
    void login(String user){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,"",List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    MockMultipartFile photo() throws Exception {return new MockMultipartFile("file","photo.png","image/png",BatchImages.png(new BufferedImage(100,120,BufferedImage.TYPE_INT_RGB)));}
    View two(Strategy strategy)throws Exception {var v=batches.upload(UUID.randomUUID(),new Upload(Channel.SINGLE,strategy),photo());return batches.addSource(v.id,UUID.randomUUID(),photo());}
    View settled(UUID id)throws Exception {for(int i=0;i<300;i++){var v=batches.get(id);if(v.calls.stream().noneMatch(c->Set.of("QUEUED","RUNNING").contains(c.status())))return v;Thread.sleep(20);}throw new AssertionError("timeout");}
    int quota(){return db.queryForObject("SELECT COALESCE(SUM(used),0) FROM ai_daily_quota WHERE owner_username=?",Integer.class,username);}
    @Test void oneSubmissionContinuesWithoutBrowserAndWithoutKnownCosts()throws Exception {
        when(recognition.raw(any(),any(),anyString())).thenAnswer(i->{recognized.incrementAndGet();return new RecognitionService.RawOutput(json.valueToTree(Map.of("items",List.of(BatchRulesTest.item(.9,true,-1)))),null,"id");});
        recognized.set(0);var v=two(Strategy.SMART);var request=new Start(new Detect(List.of(),"test","gpt-6-luna"),"test","gpt-image-2.5-flare");UUID key=UUID.randomUUID();
        batches.start(v.id,key,request);batches.start(v.id,key,request);v=settled(v.id);
        assertEquals(2,recognized.get());assertEquals(4,quota(),v.error+" / "+v.status);assertEquals(2,v.targets.stream().filter(t->t.jobId!=null).count());
        assertEquals(2,v.calls.stream().filter(c->c.type().equals("GENERATE")).count());assertNull(v.automaticGeneration);
        assertTrue(v.calls.stream().filter(c->c.type().equals("RECOGNIZE")).allMatch(c->c.costUsd()==null));
    }
    @Test void spriteReviewPausesAfterRecognitionThenSplitsEightOrderedIndependentJobs()throws Exception {
        doAnswer(i->{recognized.incrementAndGet();return new RecognitionService.RawOutput(json.valueToTree(Map.of("items",Collections.nCopies(8,BatchRulesTest.item(.9,true,-1)))),null,"id");}).when(recognition).raw(any(),any(),anyString());
        var v=batches.upload(UUID.randomUUID(),new Upload(Channel.MULTIPLE,Strategy.SMART),photo());
        batches.start(v.id,UUID.randomUUID(),new Start(new Detect(List.of(),"test","gpt-6-luna"),"test","gpt-image-2.5-flare",true));v=settled(v.id);
        assertEquals(1,recognized.get());assertEquals(1,quota());assertEquals(8,v.targets.size());
        assertTrue(Files.exists(storage.resolve("batches").resolve(v.id.toString()).resolve("recognition-"+v.calls.getFirst().id()+".json")));verify(generation,never()).generate(any(),any());
        doAnswer(i->{var p=(GenerationService.Prepared)i.getArgument(0);assertTrue(p.prompt().contains("3行、3列"));var image=new BufferedImage(1254,1254,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
            for(int slot=0;slot<8;slot++){g.setColor(new java.awt.Color(20+slot*20,40,80));g.fillRect(slot%3*418,slot/3*418,418,418);}g.dispose();
            return new GenerationService.Output(BatchImages.png(image),p.provider(),p.model(),p.protocol(),null,"generated");}).when(generation).generate(any(),any());
        batches.generate(v.id,UUID.randomUUID(),new Generate(v.targets.stream().map(t->t.id).toList(),8,1024,"FIXED","test","gpt-image-2.5-flare","low"));v=settled(v.id);
        assertEquals(2,quota());assertEquals(1,recognized.get());verify(generation,times(1)).generate(any(),any());
        assertEquals(8,v.targets.stream().map(t->t.jobId).distinct().count());
        for(int slot=0;slot<8;slot++){var target=v.targets.get(slot);assertNotNull(target.jobId);assertEquals(slot,target.slot);
            assertEquals(List.of("RECOGNIZE","GENERATE","ENSURE_TRANSPARENCY","STICKER"),workflows.get(target.jobId).request().steps().stream().map(step->step.type().name()).toList());
            var image=BatchImages.read(Files.readAllBytes(storage.resolve("workflows").resolve(target.jobId.toString()).resolve("step-1.png")));
            assertEquals(418,image.getWidth());assertEquals(new java.awt.Color(20+slot*20,40,80).getRGB(),image.getRGB(200,200));
        }
    }
    @Test void insufficientGenerationQuotaKeepsRecognizedTargetsForExplicitContinuation()throws Exception {
        var v=two(Strategy.SMART);db.update("INSERT INTO ai_daily_quota(owner_username,quota_date,used) VALUES(?,CURRENT_DATE,17)",username);
        batches.start(v.id,UUID.randomUUID(),new Start(new Detect(List.of(),"test","gpt-6-luna"),"test","gpt-image-2.5-flare"));v=settled(v.id);
        assertEquals(19,quota());assertEquals(2,v.targets.size());assertTrue(v.targets.stream().allMatch(t->t.status.equals("DETECTED")));assertNotNull(v.error);assertNull(v.automaticGeneration);
        verify(generation,never()).generate(any(),any());
        batches.generate(v.id,UUID.randomUUID(),new Generate(List.of(v.targets.getFirst().id),1,1024,"FIXED","test","gpt-image-2.5-flare","low"));v=settled(v.id);
        assertEquals(20,quota());assertEquals(1,v.targets.stream().filter(t->t.jobId!=null).count());assertEquals(2,recognized.get());
    }
    @Test void repeatedSubmissionAndSingleRedoReuseRecognitionAndPreserveOriginals()throws Exception {
        var v=two(Strategy.SMART);UUID key=UUID.randomUUID();var request=new Detect(List.of(),"test","gpt-6-luna");batches.detect(v.id,key,request);batches.detect(v.id,key,request);v=settled(v.id);
        assertEquals(2,recognized.get());assertEquals(2,quota());assertEquals(2,v.targets.size());assertArrayEquals(photo().getBytes(),Files.readAllBytes(batches.file(v.id,v.sources.getFirst().originalFile())));
        var ids=v.targets.stream().map(t->t.id).toList();var generate=new Generate(ids,2,1024,"FIXED","test","gpt-image-2.5-flare","low");UUID genkey=UUID.randomUUID();batches.generate(v.id,genkey,generate);batches.generate(v.id,genkey,generate);v=settled(v.id);
        assertEquals(3,quota());UUID atlasCall=v.calls.stream().filter(c->c.type().equals("GENERATE")).findFirst().orElseThrow().id();batches.resumeLocal(v.id,atlasCall);batches.resumeLocal(v.id,atlasCall);assertEquals(3,quota());verify(generation,times(1)).generate(any(),any());assertTrue(v.targets.stream().allMatch(t->t.jobId!=null));assertEquals(1,v.calls.stream().filter(c->c.type().equals("GENERATE")).count());
        batches.generate(v.id,UUID.randomUUID(),new Generate(List.of(ids.getFirst()),1,1024,"FIXED","test","gpt-image-2.5-flare","low"));settled(v.id);assertEquals(4,quota());assertEquals(2,recognized.get());
        assertEquals(v.id,batches.provenance(v.targets.getFirst().garmentId).get("batchId"));
        login("another-user");UUID batchId=v.id;assertThrows(ProcessingException.class,()->batches.get(batchId));assertTrue(batches.provenance(v.targets.getFirst().garmentId).isEmpty());
    }
    @Test void confirmedTargetsCannotBeGeneratedOrOverwrittenByOldAtlas()throws Exception {
        var v=two(Strategy.SMART);batches.detect(v.id,UUID.randomUUID(),new Detect(List.of(),"test","gpt-6-luna"));v=settled(v.id);
        var ids=v.targets.stream().map(t->t.id).toList();batches.generate(v.id,UUID.randomUUID(),new Generate(ids,2,1024,"FIXED","test","gpt-image-2.5-flare","low"));v=settled(v.id);
        var target=v.targets.getFirst();var originalJob=target.jobId;
        db.update("UPDATE garment SET closet_status='ACTIVE' WHERE id=?",target.garmentId.toString());
        var refreshed=batches.get(v.id);assertTrue(refreshed.targets.getFirst().confirmed);assertFalse(refreshed.targets.getLast().confirmed);assertEquals(1,refreshed.metrics.confirmed());
        UUID batchId=v.id;assertThrows(ProcessingException.class,()->batches.generate(batchId,UUID.randomUUID(),new Generate(ids,2,1024,"FIXED","test","gpt-image-2.5-flare","low")));assertEquals(3,quota());
        batches.resumeLocal(v.id,v.calls.getLast().id());assertEquals(originalJob,batches.get(v.id).targets.getFirst().jobId);assertEquals(3,quota());
    }
    @Test void requestedTargetOrderDefinesSlotsIndependentlyOfCandidateOrder()throws Exception {
        var v=two(Strategy.SMART);batches.detect(v.id,UUID.randomUUID(),new Detect(List.of(),"test","gpt-6-luna"));v=settled(v.id);
        var reversed=List.of(v.targets.getLast().id,v.targets.getFirst().id);
        batches.generate(v.id,UUID.randomUUID(),new Generate(reversed,2,1024,"FIXED","test","gpt-image-2.5-flare","low"));v=settled(v.id);
        assertEquals(reversed,v.calls.getLast().targetIds());assertEquals(1,v.targets.getFirst().slot);assertEquals(0,v.targets.getLast().slot);
    }
    @Test void manualUnmarkedPhotosDoNotConsumeCloudQuota()throws Exception {
        var v=two(Strategy.MANUAL);var src=v.sources.getLast();
        when(recognition.raw(any(),any(),anyString())).thenAnswer(i->{recognized.incrementAndGet();return new RecognitionService.RawOutput(json.valueToTree(Map.of("items",List.of(BatchRulesTest.item(.4,false,0)))),null,"id");});
        recognized.set(0);batches.detect(v.id,UUID.randomUUID(),new Detect(List.of(new Marker(src.id(),.5,.5)),"test","gpt-6-luna"));v=settled(v.id);assertEquals(1,quota());assertEquals(1,recognized.get());assertEquals(1,v.targets.size());assertTrue(v.targets.getFirst().completion);
    }
    @Test void atomicInsufficientQuotaCreatesNoCalls()throws Exception {
        var v=two(Strategy.SMART);db.update("INSERT INTO ai_daily_quota(owner_username,quota_date,used) VALUES(?,CURRENT_DATE,19)",username);
        UUID id=v.id;assertThrows(ProcessingException.class,()->batches.detect(id,UUID.randomUUID(),new Detect(List.of(),"test","gpt-6-luna")));assertTrue(batches.get(id).calls.isEmpty());assertEquals(19,quota());
    }
    @Test void failedGenerationKeepsOtherResultsAndUnknownReceiptsAreNeverRequeued()throws Exception {
        var v=two(Strategy.SMART);batches.detect(v.id,UUID.randomUUID(),new Detect(List.of(),"test","gpt-6-luna"));v=settled(v.id);
        var targets=v.targets.stream().map(t->t.id).toList();
        doThrow(new ProcessingException(org.springframework.http.HttpStatus.BAD_GATEWAY,"PROVIDER_HTTP_502","upstream uncertain"))
            .when(generation).generate(any(),any());
        batches.generate(v.id,UUID.randomUUID(),new Generate(targets,2,1024,"FIXED","test","gpt-image-2.5-flare","low"));v=settled(v.id);
        assertEquals("UNKNOWN",v.calls.getLast().status());assertTrue(v.targets.stream().allMatch(t->t.status.equals("UNKNOWN")));verify(generation,times(1)).generate(any(),any());
        var first=v.calls.getFirst();db.update("UPDATE batch_call SET status='UNKNOWN' WHERE id=?",first.id().toString());
        assertEquals("UNKNOWN",batches.get(v.id).calls.getFirst().status());assertEquals("NEEDS_REVIEW",batches.get(v.id).status);
        assertEquals(2,recognized.get());assertEquals(3,quota());
    }

    @Test void partialFailureRetainsCompletedItemAndLocalStatusAdvances()throws Exception {
        var v=two(Strategy.SMART);batches.detect(v.id,UUID.randomUUID(),new Detect(List.of(),"test","gpt-6-luna"));v=settled(v.id);
        var counter=new AtomicInteger();doAnswer(i->{
            if(counter.incrementAndGet()==2)throw new ProcessingException(org.springframework.http.HttpStatus.BAD_GATEWAY,"PROVIDER_HTTP_502","uncertain");
            var p=(GenerationService.Prepared)i.getArgument(0);return new GenerationService.Output(BatchImages.png(new BufferedImage(1024,1024,BufferedImage.TYPE_INT_RGB)),p.provider(),p.model(),p.protocol(),null,"generated");
        }).when(generation).generate(any(),any());
        batches.generate(v.id,UUID.randomUUID(),new Generate(v.targets.stream().map(t->t.id).toList(),1,1024,"FIXED","test","gpt-image-2.5-flare","low"));v=settled(v.id);
        assertEquals(1,v.targets.stream().filter(t->t.jobId!=null).count());assertEquals(1,v.targets.stream().filter(t->t.status.equals("UNKNOWN")).count());
        var saved=v.targets.stream().filter(t->t.jobId!=null).findFirst().orElseThrow();
        for(int i=0;i<200;i++){v=batches.get(v.id);if(v.targets.stream().filter(t->t.id.equals(saved.id)).noneMatch(t->Set.of("QUEUED","RUNNING").contains(t.status)))break;Thread.sleep(20);}
        assertTrue(v.targets.stream().filter(t->t.id.equals(saved.id)).noneMatch(t->Set.of("QUEUED","RUNNING").contains(t.status)));assertNotNull(saved.outputUrl);assertEquals(4,quota());
    }

}
