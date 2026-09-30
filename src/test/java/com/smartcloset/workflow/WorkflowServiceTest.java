package com.smartcloset.workflow;

import com.smartcloset.ClosetProperties;
import com.smartcloset.generation.*;
import com.smartcloset.image.*;
import com.smartcloset.workflow.persistence.WorkflowRepository;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import static com.smartcloset.workflow.Workflow.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.config.import=optional:file:./target/no-local-secrets.yml", "closet.model-path=target/absent.onnx", "workflow.poll-ms=50"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WorkflowServiceTest {
    @TempDir static Path storage;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) { p.add("closet.storage-dir", () -> storage.toString()); }
    @Autowired WorkflowService service;
    @Autowired WorkflowRepository repository;
    @Autowired WorkflowFiles files;
    @MockitoBean com.smartcloset.recognition.RecognitionService recognition;
    @Autowired com.smartcloset.garment.GarmentRepository garments;
    @Autowired com.smartcloset.security.ClosetModelPolicy modelPolicy;
    @Autowired ClosetProperties properties;
    @Autowired ObjectMapper json;
    @Autowired SegmentationService segmentation;
    @Autowired StickerRenderer stickers;
    @Autowired TransparencyService transparency;
    @MockitoBean GenerationService generation;
    WorkflowService restarted;
    @BeforeEach void configure() {
        when(generation.prepare(any())).thenAnswer(i -> {
            GenerationService.Request r = i.getArgument(0);
            return new GenerationService.Prepared(r.provider(), r.model(), r.prompt(), r.parameters(), GenerationProperties.Protocol.OPENAI_IMAGES);
        });
        when(generation.frozen(any(), any())).thenAnswer(i -> {
            GenerationService.Request r = i.getArgument(0);
            return new GenerationService.Prepared(r.provider(), r.model(), r.prompt(), r.parameters(), GenerationProperties.Protocol.OPENAI_IMAGES);
        });
        when(generation.revision(any())).thenReturn("test-revision");
    }
    @AfterEach void stopRestarted() { if (restarted != null) restarted.close(); }
    Request request() { return new Request(0d, null, null, List.of(new Step(Type.GENERATE,Mode.TEXT_TO_IMAGE,
            new GenerationService.Request("test","image","shirt",Map.of()),null))); }
    WorkflowService restart() throws Exception {
        service.close();
        restarted = new WorkflowService(properties,json,generation,segmentation,stickers,transparency,repository,files,recognition,garments,modelPolicy,30,50);
        return restarted;
    }
    Job await(WorkflowService target, UUID id) throws Exception {
        for (int i=0;i<200;i++) {var job=target.get(id);if(!Set.of(Status.QUEUED,Status.RUNNING,Status.CANCEL_REQUESTED).contains(job.status()))return job;Thread.sleep(25);}
        throw new AssertionError("Workflow did not finish");
    }
    Job stored(UUID id, Status status) {
        var now=Instant.now();
        return new Job(id,"test",status,now,now,request(),null,null,true,
                List.of(new StepResult(0,Type.GENERATE,status==Status.QUEUED?Status.PENDING:Status.RUNNING,now,null,null,null,null,null,null)),null);
    }
    byte[] image() throws Exception {
        var image=new BufferedImage(100,100,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();g.setColor(Color.BLUE);g.fillRect(20,20,60,60);g.dispose();
        var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();
    }
    @Test void queuesSecondJobCancelsItAndDeduplicatesConcurrentSubmission() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(generation.generate(any(),isNull())).thenAnswer(i->{entered.countDown();release.await(5,TimeUnit.SECONDS);throw ProcessingException.invalid("stub failure");});
        UUID id=UUID.randomUUID();
        try {
            service.submit(id,request(),null);assertTrue(entered.await(3,TimeUnit.SECONDS));
            assertEquals(id,service.submit(id,request(),null).id());
            var queued=service.submit(UUID.randomUUID(),request(),null);assertEquals(Status.QUEUED,queued.status());
            assertEquals(Status.CANCELLED,service.cancel(queued.id()).status());
            release.countDown();assertEquals(Status.FAILED,await(service,id).status());
            verify(generation,times(1)).generate(any(),isNull());
            assertEquals(Status.FAILED,restart().submit(id,request(),null).status());
            verify(generation,times(1)).generate(any(),isNull());
        } finally {release.countDown();}
    }
    @Test void restartRetainsUncertainCloudAttemptWithoutResubmitting() throws Exception {
        service.close();UUID id=UUID.randomUUID();repository.insert(stored(id,Status.RUNNING),List.of());
        repository.startAttempt(id,0,new GenerationService.Prepared("test","image","shirt",Map.of(),GenerationProperties.Protocol.OPENAI_IMAGES));
        var job=restart().get(id);assertEquals(Status.UNKNOWN,job.status());assertEquals("UNKNOWN",job.attempts().getFirst().status());
        verify(generation,never()).generate(any(),any());
    }
    @Test void queuedTaskSurvivesRestartAndProducesDurableAssets() throws Exception {
        service.close();UUID id=UUID.randomUUID();repository.insert(stored(id,Status.QUEUED),List.of());
        when(generation.generate(any(),isNull())).thenReturn(new GenerationService.Output(image(),"test","image",GenerationProperties.Protocol.OPENAI_IMAGES,null,"remote-123"));
        var job=await(restart(),id);assertEquals(Status.SUCCEEDED,job.status());assertEquals(1,job.assets().size());
        assertEquals("remote-123",job.attempts().getFirst().providerRequestId());assertEquals("SUCCEEDED",job.attempts().getFirst().status());
        assertFalse(Files.exists(files.directory(id).resolve("job.json")));
    }
    @Test void outputReceiptRecoversCrashBeforeDatabaseCommitWithoutGeneratingAgain() throws Exception {
        service.close();UUID id=UUID.randomUUID();var job=stored(id,Status.RUNNING);repository.insert(job,List.of());
        repository.startAttempt(id,0,new GenerationService.Prepared("test","image","shirt",Map.of(),GenerationProperties.Protocol.OPENAI_IMAGES));
        var asset=files.write(id,"step-0.png","GENERATE",image());
        var result=new StepResult(0,Type.GENERATE,Status.SUCCEEDED,Instant.now(),Instant.now(),null,files.url(id,"step-0.png"),null,null,null);
        files.receipt(id,new WorkflowFiles.Receipt(result,asset,"remote-recovered"));
        var recovered=await(restart(),id);assertEquals(Status.SUCCEEDED,recovered.status());assertEquals(1,recovered.assets().size());
        assertEquals("remote-recovered",recovered.attempts().getFirst().providerRequestId());verify(generation,never()).generate(any(),any());
    }
    @Test void legacyImportIsIdempotentAndOriginalJsonIsPreserved() throws Exception {
        service.close();UUID id=UUID.randomUUID();var dir=files.directory(id);Files.createDirectories(dir);
        var request=request();var raw=json.writeValueAsBytes(request);var digest=java.security.MessageDigest.getInstance("SHA-256");digest.update(raw);digest.update((byte)0);
        var now=Instant.now();var job=new Job(id,HexFormat.of().formatHex(digest.digest()),Status.SUCCEEDED,now,now,request,null,null,true,
                List.of(new StepResult(0,Type.GENERATE,Status.SUCCEEDED,now,now,null,null,null,null,null)),null);
        json.writeValue(dir.resolve("job.json").toFile(),job);byte[] original=Files.readAllBytes(dir.resolve("job.json"));
        var read=restart().submit(id,request,null);assertEquals(Status.SUCCEEDED,read.status());
        assertArrayEquals(original,Files.readAllBytes(dir.resolve("job.json")));verify(generation,never()).generate(any(),any());
    }
    @Test void concurrentSameKeyCreatesOneTask() throws Exception {
        service.close();UUID id=UUID.randomUUID();
        // Stop dispatch, while keeping submission enabled through a fresh slow-polling service.
        restarted=new WorkflowService(properties,json,generation,segmentation,stickers,transparency,repository,files,recognition,garments,modelPolicy,30,60000);
        var pool=Executors.newFixedThreadPool(4);
        try {
            var calls=new ArrayList<Callable<Job>>();for(int i=0;i<8;i++)calls.add(()->restarted.submit(id,request(),null));
            for(var future:pool.invokeAll(calls))assertEquals(id,future.get().id());
            assertEquals(Status.QUEUED,restarted.get(id).status());restarted.cancel(id);
        } finally {pool.shutdownNow();}
        verify(generation,never()).generate(any(),any());
    }
    @Test void cancellationDuringGenerationRetainsLateOutputButSkipsPostprocessing() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);byte[] png=image();
        when(generation.generate(any(),isNull())).thenAnswer(i->{entered.countDown();release.await(5,TimeUnit.SECONDS);
            return new GenerationService.Output(png,"test","image",GenerationProperties.Protocol.OPENAI_IMAGES,null,"late-result");});
        var sequence=new ArrayList<>(request().steps());sequence.add(new Step(Type.ENSURE_TRANSPARENCY,null,null,null));sequence.add(new Step(Type.STICKER,null,null,null));
        UUID id=UUID.randomUUID();
        try {
            service.submit(id,new Request(0d,null,null,sequence),null);assertTrue(entered.await(3,TimeUnit.SECONDS));
            assertEquals(Status.CANCEL_REQUESTED,service.cancel(id).status());release.countDown();
            var job=await(service,id);assertEquals(Status.CANCELLED,job.status());assertEquals(1,job.assets().size());
            assertEquals(Status.SUCCEEDED,job.steps().getFirst().status());assertEquals(Status.CANCELLED,job.steps().get(1).status());
            assertEquals("SUCCEEDED",job.attempts().getFirst().status());assertNull(job.selectedAssetId());
        }finally{release.countDown();}
    }
    @Test void canonicalParametersDeduplicateAndQueueLimitDoesNotRejectExistingTask() throws Exception {
        service.close();restarted=new WorkflowService(properties,json,generation,segmentation,stickers,transparency,repository,files,recognition,garments,modelPolicy,1,60000);
        var first=new LinkedHashMap<String,Object>();first.put("quality","high");first.put("size","1024x1024");
        var second=new LinkedHashMap<String,Object>();second.put("size","1024x1024");second.put("quality","high");
        var r1=new Request(0d,null,null,List.of(new Step(Type.GENERATE,Mode.TEXT_TO_IMAGE,new GenerationService.Request("test","image","shirt",first),null)));
        var r2=new Request(0d,null,null,List.of(new Step(Type.GENERATE,Mode.TEXT_TO_IMAGE,new GenerationService.Request("test","image","shirt",second),null)));
        UUID id=UUID.randomUUID();restarted.submit(id,r1,null);assertEquals(id,restarted.submit(id,r2,null).id());
        assertEquals("WORKFLOW_QUEUE_FULL",assertThrows(ProcessingException.class,()->restarted.submit(UUID.randomUUID(),r1,null)).code());
        restarted.cancel(id);
    }


    com.smartcloset.recognition.Recognition.Prepared recognitionPrepared() {
        return new com.smartcloset.recognition.Recognition.Prepared("test", "vision", "revision", "prompt-v1", "garment-v1", "recognize", json.createObjectNode());
    }
    Job storedRecognition(UUID id, boolean withReceipt) throws Exception {
        var prepared=recognitionPrepared();var now=Instant.now();
        var step=new Step(Type.RECOGNIZE,null,null,null,null,null,new com.smartcloset.recognition.Recognition.Request("test","vision"),prepared);
        var request=new Request(0d,null,null,List.of(step),UUID.randomUUID(),Origin.USER_UPLOAD);
        var input=files.write(id,"input.png","INPUT",image());
        var running=new StepResult(0,Type.RECOGNIZE,Status.RUNNING,now,null,files.url(id,"input.png"),null,null,null,null);
        var job=new Job(id,"recognition-recovery",Status.RUNNING,now,now,request,files.url(id,"input.png"),null,false,List.of(running),null);
        repository.insert(job,List.of(input));repository.startAttempt(id,0,prepared);
        if(withReceipt){
            var schema=new com.smartcloset.recognition.RecognitionSchema(json);
            var result=schema.validate(json.valueToTree(RecognitionWorkflowTest.fixture("READY","蓝色")));
            var completed=new StepResult(0,Type.RECOGNIZE,Status.SUCCEEDED,now,Instant.now(),job.inputUrl(),null,null,null,null,null,result,json.valueToTree(result));
            files.receipt(id,new WorkflowFiles.Receipt(completed,null,"recovered-recognition"));
        }
        return job;
    }
    @Test void recognitionReceiptRecoversMetadataWithoutCallingModelAgain() throws Exception {
        service.close();UUID id=UUID.randomUUID();var job=storedRecognition(id,true);
        var recovered=await(restart(),id);assertEquals(Status.SUCCEEDED,recovered.status());
        assertEquals("recovered-recognition",recovered.attempts().getFirst().providerRequestId());
        var garment=garments.get(job.request().garmentId());assertEquals(List.of("蓝色"),garment.effective().get("primaryColor"));
        assertEquals(1,recovered.assets().size());verify(recognition,never()).recognize(any(),any());
        var edited=garments.edit(garment.id(),new com.smartcloset.garment.GarmentRepository.Edit(garment.revision(),Map.of("brand",List.of()),List.of()));
        restarted.close();restarted=null;restart();assertTrue(garments.get(edited.id()).overrides().containsKey("brand"));
    }
    @Test void recognitionWithoutReceiptBecomesUnknownAndIsNeverReplayed() throws Exception {
        service.close();UUID id=UUID.randomUUID();storedRecognition(id,false);
        var recovered=await(restart(),id);assertEquals(Status.NEEDS_REVIEW,recovered.status());
        assertEquals(Status.UNKNOWN,recovered.steps().getFirst().status());assertEquals("UNKNOWN",recovered.attempts().getFirst().status());
        verify(recognition,never()).recognize(any(),any());
    }
}
