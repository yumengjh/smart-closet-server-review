package com.smartcloset.workflow;

import com.smartcloset.garment.GarmentRepository;
import com.smartcloset.generation.*;
import com.smartcloset.image.ProcessingException;
import com.smartcloset.recognition.*;
import com.sun.net.httpserver.HttpServer;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.*;
import static com.smartcloset.workflow.Workflow.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.config.import=optional:file:./target/no-local-secrets.yml","workflow.poll-ms=50","closet.model-path=target/missing.onnx"})
@DirtiesContext
class RecognitionWorkflowTest {
    static final ObjectMapper codec = new ObjectMapper();
    static final AtomicInteger calls = new AtomicInteger();
    static volatile int responseCode = 200;
    static volatile String assessment = "READY", color = "蓝色", finish = "stop";
    static volatile JsonNode sent;
    static volatile CountDownLatch entered, release;
    static Path storage;
    static HttpServer upstream;
    static {
        try {
            storage = Files.createTempDirectory("closet-recognition-test-");
            upstream = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            upstream.createContext("/v1/chat/completions", exchange -> {
                calls.incrementAndGet(); sent=codec.readTree(exchange.getRequestBody().readAllBytes());
                String a=assessment,c=color,f=finish; int status=responseCode;
                if(entered!=null){entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
                var content=fixture(a,c);
                byte[] body=codec.writeValueAsBytes(Map.of("id","recognition-request", "choices",List.of(Map.of("finish_reason",f,"message",Map.of("content",codec.writeValueAsString(content)))),"usage",Map.of("total_tokens",120)));
                exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);exchange.close();
            });
            upstream.createContext("/v1/models/vision-test:generateContent", exchange -> {
                sent=codec.readTree(exchange.getRequestBody().readAllBytes());
                byte[] body=codec.writeValueAsBytes(Map.of("candidates",List.of(Map.of("finishReason","STOP","content",Map.of("parts",List.of(Map.of("text",codec.writeValueAsString(fixture("READY","蓝色"))))))),"usageMetadata",Map.of("totalTokenCount",80)));
                exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
            });
            upstream.start();
        }catch(Exception e){throw new RuntimeException(e);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("closet.storage-dir",()->storage.toString());
        p.add("generation.providers.test.protocol",()->"OPENAI_IMAGES");
        p.add("generation.providers.test.base-url",()->"http://127.0.0.1:"+upstream.getAddress().getPort()+"/v1");
        p.add("generation.providers.test.api-key",()->"test-secret");
        p.add("recognition.default-provider",()->"test");
        p.add("recognition.providers.test.credential-provider",()->"test");
        p.add("recognition.providers.test.protocol",()->"OPENAI_CHAT");
        p.add("recognition.providers.test.default-model",()->"vision-test");
        p.add("recognition.providers.gemini.credential-provider",()->"test");
        p.add("recognition.providers.gemini.protocol",()->"GEMINI");
        p.add("recognition.providers.gemini.default-model",()->"vision-test");
    }
    @Autowired WorkflowService service;
    @Autowired GarmentRepository garments;
    @Autowired RecognitionSchema schema;
    @Autowired RecognitionService recognition;
    @Autowired com.smartcloset.workflow.persistence.WorkflowRepository repository;
    @Autowired WorkflowFiles files;
    @Autowired com.smartcloset.ClosetProperties closet;
    @Autowired com.smartcloset.image.SegmentationService segmentation;
    @Autowired com.smartcloset.image.StickerRenderer stickers;
    @Autowired com.smartcloset.image.TransparencyService transparency;
    @Autowired ObjectMapper json;
    @MockitoBean GenerationService generation;
    @BeforeEach void setup() throws Exception {
        responseCode=200;assessment="READY";color="蓝色";finish="stop";entered=null;release=null;
        when(generation.prepare(any())).thenAnswer(i->{GenerationService.Request r=i.getArgument(0);return new GenerationService.Prepared(r.provider(),r.model(),r.prompt(),Map.of(),GenerationProperties.Protocol.OPENAI_IMAGES);});
        when(generation.frozen(any(),any())).thenAnswer(i->{GenerationService.Request r=i.getArgument(0);return new GenerationService.Prepared(r.provider(),r.model(),r.prompt(),Map.of(),GenerationProperties.Protocol.OPENAI_IMAGES);});
        when(generation.revision(any())).thenReturn("test");
        when(generation.generate(any(),any())).thenReturn(new GenerationService.Output(image(Color.RED),"test","image",GenerationProperties.Protocol.OPENAI_IMAGES,null));
    }
    @AfterAll static void stop(){upstream.stop(0);}
    static Map<String,Object> fixture(String assessment,String color) {
        var attrs=new LinkedHashMap<String,Object>();
        for(var field:RecognitionSchema.FIELDS)attrs.put(field.key(),Map.of("values",List.of(),"status","UNKNOWN","evidence","照片未提供足够依据"));
        if(Set.of("READY","PARTIAL").contains(assessment)){
            attrs.put("category",Map.of("values",List.of("T恤"),"status","OBSERVED","evidence","可见圆领短袖"));
            attrs.put("primaryColor",Map.of("values",List.of(color),"status","OBSERVED","evidence","主体布料颜色"));
        }
        return Map.of("assessment",assessment,"attributes",attrs,"issues",List.of());
    }
    static byte[] image(Color color)throws Exception {
        var image=new BufferedImage(100,120,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();g.setColor(color);g.fillRect(20,20,60,80);g.dispose();
        var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);return bytes.toByteArray();
    }
    Step identify(){return Step.recognize(new Recognition.Request("test","vision-test"));}
    Step generate(){return new Step(Type.GENERATE,Mode.IMAGE_TO_IMAGE,new GenerationService.Request("test","image","整理",Map.of()),null);}
    Job upload(Step...steps)throws Exception{return service.submit(UUID.randomUUID(),new Request(0d,null,null,List.of(steps)),image(Color.BLUE));}
    Job await(UUID id)throws Exception{
        for(int i=0;i<240;i++){var job=service.get(id);if(!Set.of(Status.QUEUED,Status.RUNNING,Status.CANCEL_REQUESTED).contains(job.status()))return job;Thread.sleep(25);}throw new AssertionError("timeout");
    }
    @Test void integratedRecognitionUsesOriginalAndProducesAnEditableGarment()throws Exception{
        var job=await(upload(identify(),generate()).id());assertEquals(Status.SUCCEEDED,job.status());
        assertNull(job.steps().getFirst().outputUrl());assertNotNull(job.steps().getFirst().recognition());assertEquals(2,job.assets().size());assertEquals(2,job.attempts().size());
        var input=sent.at("/messages/1/content/1/image_url/url").asText().split(",",2)[1];
        var decoded=ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(input)));assertEquals(Color.BLUE.getRGB(),decoded.getRGB(50,50));
        assertTrue(sent.at("/response_format/json_schema/strict").asBoolean());
        var garment=garments.get(job.request().garmentId());assertEquals(List.of("蓝色"),garment.effective().get("primaryColor"));
        assertEquals(job.outputUrl(),garment.displayUrl());assertEquals(job.inputUrl(),garment.sourceUrl());
        assertFalse(json.writeValueAsString(job).contains("test-secret"));
        var edited=garments.edit(garment.id(),new GarmentRepository.Edit(garment.revision(),Map.of("primaryColor",List.of("绿色"),"brand",List.of()),List.of()));
        color="红色";var rerun=service.rerun(garment.id(),UUID.randomUUID(),new RerunRequest(List.of(identify())));assertEquals(Status.SUCCEEDED,await(rerun.id()).status());
        var updated=garments.get(garment.id());assertEquals(List.of("绿色"),updated.effective().get("primaryColor"));assertEquals(List.of("红色"),updated.automatic().attributes().get("primaryColor").values());
        assertTrue(updated.overrides().containsKey("brand"));assertEquals(garment.displayAssetId(),updated.displayAssetId());assertEquals(2,updated.runs().size());
        assertThrows(ProcessingException.class,()->garments.edit(garment.id(),new GarmentRepository.Edit(edited.revision(),Map.of(),List.of("primaryColor"))));
        var reset=garments.edit(garment.id(),new GarmentRepository.Edit(updated.revision(),Map.of(),List.of("primaryColor")));assertEquals(List.of("红色"),reset.effective().get("primaryColor"));
    }
    @Test void recognitionFailureDoesNotPreventImageProcessingAndIsNotRetried()throws Exception{
        responseCode=502;int before=calls.get();var job=await(upload(identify(),generate()).id());
        assertEquals(Status.NEEDS_REVIEW,job.status());assertEquals(Status.UNKNOWN,job.steps().getFirst().status());assertEquals(Status.SUCCEEDED,job.steps().get(1).status());
        assertNotNull(job.outputUrl());assertNull(garments.get(job.request().garmentId()).automatic());assertEquals(before+1,calls.get());
    }
    @Test void ambiguousTargetBlocksGeneration()throws Exception{
        assessment="MULTIPLE_GARMENTS";var job=await(upload(identify(),generate()).id());
        assertEquals(Status.NEEDS_REVIEW,job.status());assertEquals(Status.SKIPPED,job.steps().get(1).status());verify(generation,never()).generate(any(),any());
        assertNull(garments.get(job.request().garmentId()).automatic());assertNotNull(job.steps().getFirst().rawRecognition());
    }
    @Test void invalidOrTruncatedOutputNeverReplacesPreviousMetadata()throws Exception{
        var job=await(upload(identify()).id());UUID id=job.request().garmentId();finish="length";
        var rerun=await(service.rerun(id,UUID.randomUUID(),new RerunRequest(List.of(identify()))).id());
        assertEquals(Status.NEEDS_REVIEW,rerun.status());assertEquals("RECOGNITION_INCOMPLETE",rerun.steps().getFirst().error().code());
        assertEquals(job.id(),garments.get(id).appliedRecognitionJobId());
        finish="stop";color="编造的颜色";rerun=await(service.rerun(id,UUID.randomUUID(),new RerunRequest(List.of(identify()))).id());
        assertEquals("RECOGNITION_INVALID_OUTPUT",rerun.steps().getFirst().error().code());assertEquals(List.of("蓝色"),garments.get(id).effective().get("primaryColor"));
    }
    @Test void newRequestAndUserEditWinAgainstLateResponseAndDuplicateKeys()throws Exception{
        var first=await(upload(identify()).id());UUID id=first.request().garmentId();
        entered=new CountDownLatch(1);release=new CountDownLatch(1);color="红色";
        UUID key=UUID.randomUUID();var request=new RerunRequest(List.of(identify()));var old=service.rerun(id,key,request);
        assertTrue(entered.await(3,TimeUnit.SECONDS));assertEquals(old.id(),service.rerun(id,key,request).id());
        color="白色";var newer=service.rerun(id,UUID.randomUUID(),request);
        var current=garments.get(id);garments.edit(id,new GarmentRepository.Edit(current.revision(),Map.of("category",List.of("卫衣")),List.of()));
        release.countDown();await(old.id());await(newer.id());
        var finalView=garments.get(id);assertEquals(newer.id(),finalView.appliedRecognitionJobId());assertEquals(List.of("白色"),finalView.effective().get("primaryColor"));assertEquals(List.of("卫衣"),finalView.effective().get("category"));
        entered=null;release=null;
    }
    @Test void manualDisplaySelectionSurvivesRerenderAndRejectsForeignAssets()throws Exception{
        var job=await(upload(identify(),generate()).id());var view=garments.get(job.request().garmentId());
        garments.select(view.id(),new GarmentRepository.SelectImage(view.revision(),view.sourceAssetId()));
        await(service.rerun(view.id(),UUID.randomUUID(),new RerunRequest(List.of(generate()))).id());
        var fresh=garments.get(view.id());assertEquals(view.sourceAssetId(),fresh.displayAssetId());
        var other=await(upload(identify()).id());var foreign=garments.get(other.request().garmentId());
        assertThrows(ProcessingException.class,()->garments.select(view.id(),new GarmentRepository.SelectImage(fresh.revision(),foreign.sourceAssetId())));
    }
    @Test void schemaRejectsFabricationAndContradictoryFields(){
        var data=codec.valueToTree(fixture("READY","蓝色"));
        ((tools.jackson.databind.node.ObjectNode)data.path("attributes").path("styles")).put("status","OBSERVED").set("values",codec.valueToTree(List.of("休闲")));
        assertThrows(ProcessingException.class,()->schema.validate(data));
        var bad=codec.valueToTree(fixture("NO_GARMENT","蓝色"));((tools.jackson.databind.node.ObjectNode)bad.path("attributes")).set("category",codec.valueToTree(Map.of("values",List.of("T恤"),"status","OBSERVED","evidence","猜测")));
        assertThrows(ProcessingException.class,()->schema.validate(bad));
    }
    @Test void nativeGeminiProtocolAndBoundedWhiteBackgroundInput()throws Exception {
        var large=new BufferedImage(2000,1000,BufferedImage.TYPE_INT_ARGB);var g=large.createGraphics();g.setColor(Color.BLUE);g.fillRect(500,250,1000,500);g.dispose();
        var bytes=new ByteArrayOutputStream();ImageIO.write(large,"png",bytes);
        var output=recognition.recognize(recognition.prepare(new Recognition.Request("gemini","vision-test")),bytes.toByteArray());
        assertEquals(Recognition.Assessment.READY,output.result().assessment());assertEquals(80,output.usage().path("totalTokenCount").asInt());
        assertEquals("application/json",sent.at("/generationConfig/responseMimeType").asText());assertTrue(sent.at("/generationConfig/responseJsonSchema/properties/attributes").isObject());
        var input=ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(sent.at("/contents/0/parts/1/inlineData/data").asText())));
        assertEquals(1600,input.getWidth());assertEquals(800,input.getHeight());assertEquals(Color.WHITE.getRGB(),input.getRGB(0,0));assertEquals(Color.BLUE.getRGB(),input.getRGB(800,400));
    }
}
