package com.smartcloset.workflow;

import com.sun.net.httpserver.HttpServer;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.config.import=optional:file:./target/no-local-secrets.yml","closet.model-path=target/absent.onnx"})
class WorkflowApiTest {
    @TempDir static Path storage;
    static HttpServer provider;
    static AtomicInteger calls=new AtomicInteger();
    static volatile boolean fail;
    static volatile byte[] generatedOverride;
    static {
        try {
            provider=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            provider.createContext("/v1/images",e->{calls.incrementAndGet();e.getRequestBody().readAllBytes();
                byte[] body=(fail?"{\"error\":\"fake-secret\"}":"{\"data\":[{\"b64_json\":\""+Base64.getEncoder().encodeToString(generatedOverride == null ? fixture() : generatedOverride)+"\"}]}").getBytes();
                e.sendResponseHeaders(fail?502:200,body.length);e.getResponseBody().write(body);e.close();});provider.start();
        } catch(IOException e){throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void config(DynamicPropertyRegistry p){
        p.add("closet.storage-dir",()->storage.toString());p.add("generation.providers.test.protocol",()->"OPENAI_IMAGES");
        p.add("generation.providers.test.base-url",()->"http://127.0.0.1:"+provider.getAddress().getPort()+"/v1");
        p.add("generation.providers.test.api-key",()->"fake-secret");p.add("generation.providers.test.default-model",()->"stub-image");
    }
    @BeforeEach void resetProvider(){fail=false;generatedOverride=null;}
    @AfterAll static void stop(){provider.stop(0);}
    @LocalServerPort int port;
    HttpClient client=HttpClient.newHttpClient();JsonMapper json=JsonMapper.builder().build();
    static byte[] fixture() throws IOException{
        var image=new BufferedImage(160,200,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,160,200);g.setColor(Color.BLUE);g.fillRect(40,40,80,120);g.dispose();
        var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();
    }
    String generate="{\"type\":\"GENERATE\",\"mode\":\"TEXT_TO_IMAGE\",\"generation\":{\"provider\":\"test\",\"prompt\":\"Blue shirt\"}}";
    HttpResponse<byte[]> get(String path)throws Exception{return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());}
    HttpResponse<byte[]> post(String body,UUID key)throws Exception{return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/workflows")).header("Content-Type","application/json").header("Idempotency-Key",key.toString()).POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofByteArray());}
    JsonNode await(UUID id)throws Exception{
        for(int i=0;i<200;i++){var job=json.readTree(get("/api/workflows/"+id).body());if(!Set.of("QUEUED","RUNNING").contains(job.path("status").asText()))return job;Thread.sleep(30);}
        throw new AssertionError("workflow timeout");
    }
    @Test void realHttpGenerationSegmentationStickerAndPersistentIdempotency()throws Exception{
        fail=false;int before=calls.get();UUID id=UUID.randomUUID();String body="{\"steps\":["+generate+",{\"type\":\"SEGMENT\",\"method\":\"GRABCUT\"},{\"type\":\"STICKER\"}]}";
        assertEquals(202,post(body,id).statusCode());var job=await(id);assertEquals("SUCCEEDED",job.path("status").asText(),job.toString());assertTrue(job.path("requiresReview").asBoolean());
        assertEquals(3,job.path("steps").size());for(var step:job.path("steps")){assertEquals("SUCCEEDED",step.path("status").asText());assertEquals(200,get(step.path("outputUrl").asText()).statusCode());}
        assertNotNull(ImageIO.read(new ByteArrayInputStream(get(job.path("outputUrl").asText()).body())));
        assertEquals(202,post(body,id).statusCode());assertEquals(before+1,calls.get());
        assertEquals(409,post(body.replace("Blue shirt","Red shirt"),id).statusCode());
        assertFalse(Files.exists(storage.resolve("workflows").resolve(id.toString()).resolve("job.json")));
        assertEquals(3,job.path("assets").size());assertEquals(1,job.path("attempts").size());
        assertFalse(new String(get("/api/generation/providers").body()).contains("fake-secret"));
        assertEquals(404,get("/api/workflows/"+id+"/files/job.json").statusCode());
    }
    @Test void cloudFailureIsTerminalAndLaterStepsAreSkipped()throws Exception{
        fail=true;int before=calls.get();UUID id=UUID.randomUUID();String body="{\"steps\":["+generate+",{\"type\":\"SEGMENT\"}]}";
        assertEquals(202,post(body,id).statusCode());var job=await(id);assertEquals("UNKNOWN",job.path("status").asText());
        assertEquals("PROVIDER_HTTP_502",job.at("/error/code").asText());assertEquals("SKIPPED",job.at("/steps/1/status").asText());assertFalse(job.toString().contains("fake-secret"));
        post(body,id);assertEquals(before+1,calls.get());fail=false;
    }
    @Test void rejectsInvalidSequencesBeforeAnyCloudCall()throws Exception{
        int before=calls.get();
        for(String body:new String[]{"{\"steps\":[{\"type\":\"STICKER\"}]}","{\"steps\":["+generate.replace("TEXT_TO_IMAGE","IMAGE_TO_IMAGE")+"]}","{\"steps\":["+generate+",{\"type\":\"STICKER\"}]}","{\"steps\":[]}"})
            assertEquals(400,post(body,UUID.randomUUID()).statusCode(),body);
        assertEquals(before,calls.get());
    }
    @Test void multipartRotatesBeforeSavingAndLocalOnlyDoesNotCallProvider()throws Exception{
        int before=calls.get();String workflow="{\"rotationDegrees\":90,\"steps\":[{\"type\":\"SEGMENT\",\"method\":\"GRABCUT\"},{\"type\":\"STICKER\"}]}";
        var bytes=new ByteArrayOutputStream();bytes.write(("--test\r\nContent-Disposition: form-data; name=\"workflow\"\r\nContent-Type: application/json\r\n\r\n"+workflow+"\r\n--test\r\nContent-Disposition: form-data; name=\"file\"; filename=\"shirt.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes());bytes.write(fixture());bytes.write("\r\n--test--\r\n".getBytes());
        UUID id=UUID.randomUUID();var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/workflows")).header("Content-Type","multipart/form-data; boundary=test").header("Idempotency-Key",id.toString()).POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(202,response.statusCode(),new String(response.body()));var job=await(id);assertEquals("SUCCEEDED",job.path("status").asText(),job.toString());assertFalse(job.path("requiresReview").asBoolean());
        var input=ImageIO.read(new ByteArrayInputStream(get(job.path("inputUrl").asText()).body()));assertEquals(200,input.getWidth());assertEquals(160,input.getHeight());assertEquals(before,calls.get());
    }
    byte[] transparent(boolean suspicious)throws IOException{
        var image=new BufferedImage(160,200,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();
        g.setColor(Color.BLUE);g.fillRect(40,40,80,120);g.dispose();image.setRGB(39,50,0x806699aa);
        if(suspicious)image.setRGB(0,100,0xff0000ff);
        var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();
    }
    HttpResponse<byte[]> multipart(String workflow,byte[] file,UUID id)throws Exception{
        var bytes=new ByteArrayOutputStream();bytes.write(("--alpha-test\r\nContent-Disposition: form-data; name=\"workflow\"\r\nContent-Type: application/json\r\n\r\n"+workflow+"\r\n--alpha-test\r\nContent-Disposition: form-data; name=\"file\"; filename=\"shirt.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes());
        bytes.write(file);bytes.write("\r\n--alpha-test--\r\n".getBytes());
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/workflows")).header("Content-Type","multipart/form-data; boundary=alpha-test")
                .header("Idempotency-Key",id.toString()).POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    @Test void generatedTransparentImageBypassesSegmentationAndFeedsStickerDirectly()throws Exception{
        generatedOverride=transparent(false);UUID id=UUID.randomUUID();
        String body="{\"steps\":["+generate+",{\"type\":\"ENSURE_TRANSPARENCY\"},{\"type\":\"STICKER\"}]}";
        assertEquals(202,post(body,id).statusCode());var job=await(id);assertEquals("SUCCEEDED",job.path("status").asText(),job.toString());
        assertEquals("REUSED_ALPHA",job.at("/steps/1/transparency/action").asText());assertTrue(job.at("/steps/1/segmentationId").isNull());
        assertArrayEquals(get(job.at("/steps/0/outputUrl").asText()).body(),get(job.at("/steps/1/outputUrl").asText()).body());
        assertEquals(200,get(job.path("outputUrl").asText()).statusCode());
    }
    @Test void preserveOpaqueResultStopsForReviewAndKeepsItAvailable()throws Exception{
        UUID id=UUID.randomUUID();int before=calls.get();String body="{\"steps\":["+generate+",{\"type\":\"ENSURE_TRANSPARENCY\",\"policy\":\"PRESERVE\"},{\"type\":\"STICKER\"}]}";
        assertEquals(202,post(body,id).statusCode());var job=await(id);assertEquals("NEEDS_REVIEW",job.path("status").asText());
        assertEquals("OPAQUE",job.at("/steps/1/transparency/before/verdict").asText());assertEquals("SKIPPED",job.at("/steps/2/status").asText());
        assertEquals(200,get(job.path("outputUrl").asText()).statusCode());post(body,id);assertEquals(before+1,calls.get());
    }
    @Test void transparentUploadRotationAndSuspiciousInputKeepAlphaWithoutCloudCalls()throws Exception{
        int before=calls.get();UUID id=UUID.randomUUID();String body="{\"rotationDegrees\":90,\"steps\":[{\"type\":\"ENSURE_TRANSPARENCY\"},{\"type\":\"STICKER\"}]}";
        assertEquals(202,multipart(body,transparent(false),id).statusCode());var job=await(id);assertEquals("SUCCEEDED",job.path("status").asText(),job.toString());
        var input=ImageIO.read(new ByteArrayInputStream(get(job.path("inputUrl").asText()).body()));assertEquals(200,input.getWidth());assertEquals(160,input.getHeight());
        assertEquals(0,input.getRGB(0,0));assertEquals(0x806699aa,input.getRGB(149,39));
        UUID suspicious=UUID.randomUUID();assertEquals(202,multipart(body,transparent(true),suspicious).statusCode());
        assertEquals("NEEDS_REVIEW",await(suspicious).path("status").asText());assertEquals(before,calls.get());
    }
    @Test void optionalPrecutFeedsTheEditAndPostcheckMayReuseGeneratedAlpha()throws Exception{
        generatedOverride=transparent(false);UUID id=UUID.randomUUID();String edit=generate.replace("TEXT_TO_IMAGE","IMAGE_TO_IMAGE");
        String body="{\"steps\":[{\"type\":\"SEGMENT\",\"method\":\"GRABCUT\"},"+edit+",{\"type\":\"ENSURE_TRANSPARENCY\"},{\"type\":\"STICKER\"}]}";
        assertEquals(202,multipart(body,fixture(),id).statusCode());var job=await(id);assertEquals("SUCCEEDED",job.path("status").asText(),job.toString());
        assertEquals(job.at("/steps/0/outputUrl").asText(),job.at("/steps/1/inputUrl").asText());assertEquals("REUSED_ALPHA",job.at("/steps/2/transparency/action").asText());
    }
    @Test void autoOpaqueSegmentsAndEmptyAlphaIsTerminal()throws Exception{
        UUID id=UUID.randomUUID();String body="{\"steps\":[{\"type\":\"ENSURE_TRANSPARENCY\",\"method\":\"GRABCUT\"},{\"type\":\"STICKER\"}]}";
        assertEquals(202,multipart(body,fixture(),id).statusCode());var job=await(id);assertEquals("SUCCEEDED",job.path("status").asText(),job.toString());
        assertEquals("SEGMENTED",job.at("/steps/0/transparency/action").asText());
        var empty=new BufferedImage(100,100,BufferedImage.TYPE_INT_ARGB);var bytes=new ByteArrayOutputStream();ImageIO.write(empty,"png",bytes);
        id=UUID.randomUUID();assertEquals(202,multipart(body,bytes.toByteArray(),id).statusCode());job=await(id);
        assertEquals("FAILED",job.path("status").asText());assertEquals("REJECTED",job.at("/steps/0/transparency/action").asText());
        assertEquals("SKIPPED",job.at("/steps/1/status").asText());
    }

    HttpResponse<byte[]> action(String path,String body,UUID key)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json");
        if(key!=null)request.header("Idempotency-Key",key.toString());
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    @Test void failedPostprocessingCanContinueWithoutAnotherGenerationAndReviewPersists()throws Exception{
        int before=calls.get();UUID id=UUID.randomUUID();
        String body="{\"steps\":["+generate+",{\"type\":\"ENSURE_TRANSPARENCY\",\"policy\":\"PRESERVE\"},{\"type\":\"STICKER\"}]}";
        assertEquals(202,post(body,id).statusCode());var parent=await(id);assertEquals("NEEDS_REVIEW",parent.path("status").asText());
        String assetId=parent.path("assets").get(0).path("id").asText();UUID child=UUID.randomUUID();
        String continuation="{\"assetId\":\""+assetId+"\",\"policy\":\"FORCE\",\"method\":\"GRABCUT\"}";
        String path="/api/workflows/"+id+"/continue";
        assertEquals(202,action(path,continuation,child).statusCode());
        assertEquals(202,action(path,continuation,child).statusCode());
        var result=await(child);assertEquals("SUCCEEDED",result.path("status").asText(),result.toString());
        assertEquals(id.toString(),result.path("parentJobId").asText());assertTrue(result.path("requiresReview").asBoolean());
        assertEquals(0,result.path("attempts").size());assertEquals(before+1,calls.get());
        assertEquals("NEEDS_REVIEW",await(id).path("status").asText());
        String selected="";
        for(var asset:result.path("assets"))if(asset.path("kind").asText().equals("STICKER"))selected=asset.path("id").asText();
        assertFalse(selected.isBlank());
        var reviewed=action("/api/workflows/"+child+"/assets/"+selected+"/review","{\"decision\":\"APPROVED\",\"note\":\"颜色和版型已核对\"}",null);
        assertEquals(200,reviewed.statusCode());assertEquals(selected,json.readTree(reviewed.body()).path("selectedAssetId").asText());
        assertEquals(selected,await(child).path("selectedAssetId").asText());
        assertEquals(400,action("/api/workflows/"+id+"/assets/"+selected+"/review","{\"decision\":\"APPROVED\"}",null).statusCode());
        var rejected=action("/api/workflows/"+child+"/assets/"+selected+"/review","{\"decision\":\"REJECTED\"}",null);
        assertTrue(json.readTree(rejected.body()).path("selectedAssetId").isNull());
        var history=json.readTree(get("/api/workflows?status=SUCCEEDED&page=1&size=50").body());
        assertTrue(history.path("total").asLong()>0);
        for(var item:history.path("items"))assertEquals("SUCCEEDED",item.path("status").asText());
        assertEquals(400,get("/api/workflows?size=10000").statusCode());
        assertEquals(400,get("/api/workflows?page=0").statusCode());
        assertEquals(409,action(path,continuation.replace("FORCE","PRESERVE"),child).statusCode());
    }

}
