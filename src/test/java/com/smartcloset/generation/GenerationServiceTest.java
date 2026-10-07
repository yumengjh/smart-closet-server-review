package com.smartcloset.generation;

import com.smartcloset.ClosetProperties;
import com.smartcloset.image.ProcessingException;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static com.smartcloset.generation.GenerationProperties.*;
import static org.junit.jupiter.api.Assertions.*;

class GenerationServiceTest {
    HttpServer server;
    JsonMapper json = JsonMapper.builder().build();
    AtomicReference<String> path = new AtomicReference<>(), auth = new AtomicReference<>(), request = new AtomicReference<>(), contentType = new AtomicReference<>();
    String response; int status = 200;
    @BeforeEach void setup() throws Exception {
        response = "{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(image()) + "\"}],\"usage\":{\"total_tokens\":5}}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", e -> {
            path.set(e.getRequestURI().getPath()); auth.set(e.getRequestHeaders().getFirst("Authorization"));
            if (auth.get() == null) auth.set(e.getRequestHeaders().getFirst("x-goog-api-key"));
            contentType.set(e.getRequestHeaders().getFirst("Content-Type")); request.set(new String(e.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1));
            byte[] bytes = response.getBytes(); e.sendResponseHeaders(status, bytes.length);e.getResponseBody().write(bytes);e.close();
        });server.start();
    }
    @AfterEach void close() { server.stop(0); }
    URI base() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"); }
    GenerationService service(Protocol protocol) {
        var p = new Provider(protocol, base(), "fake-secret", "test-image", Map.of(), List.of(), 2);
        return new GenerationService(new GenerationProperties(Map.of("test", p)),
                new ClosetProperties(Path.of("target"), Path.of("absent"), 12000000, 1000, 2), json);
    }
    GenerationService.Request spec(Map<String,Object> params) { return new GenerationService.Request("test", null, "Make a shirt", params); }
    static byte[] image() throws IOException {
        var image = new BufferedImage(80, 64, BufferedImage.TYPE_INT_ARGB);image.setRGB(25,25,0x806699aa);
        var out = new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();
    }
    @Test void openaiGenerationKeepsParametersAndAlpha() throws Exception {
        var service = service(Protocol.OPENAI_IMAGES);var result = service.generate(service.prepare(spec(Map.of("quality","low","size","1024x1024"))),null);
        assertEquals("/v1/images/generations",path.get());assertEquals("Bearer fake-secret",auth.get());
        var sent = json.readTree(request.get());assertEquals(1,sent.path("n").asInt());assertEquals("low",sent.path("quality").asText());
        assertEquals("test-image",sent.path("model").asText());assertEquals(5,result.usage().path("total_tokens").asInt());
        var png=ImageIO.read(new ByteArrayInputStream(result.png()));assertEquals(0,png.getRGB(0,0));assertEquals(128,png.getRGB(25,25)>>>24);
        assertFalse(json.writeValueAsString(service.providers()).contains("fake-secret"));
    }
    @Test void frozenConfigurationDoesNotAdoptNewDefaultsAndRejectsEndpointChanges() {
        var oldProvider=new Provider(Protocol.OPENAI_IMAGES,base(),"old-key","test-image",Map.of("quality","low"),List.of(),2);
        var closet=new ClosetProperties(Path.of("target"),Path.of("absent"),12000000,1000,2);
        var oldService=new GenerationService(new GenerationProperties(Map.of("test",oldProvider)),closet,json);
        var prepared=oldService.prepare(spec(Map.of()));
        var saved=new GenerationService.Request(prepared.provider(),prepared.model(),prepared.prompt(),prepared.parameters());
        var changedDefaults=new Provider(Protocol.OPENAI_IMAGES,base(),"rotated-key","other-model",Map.of("quality","high","size","2048x2048"),List.of(),2);
        var newService=new GenerationService(new GenerationProperties(Map.of("test",changedDefaults)),closet,json);
        var frozen=newService.frozen(saved,oldService.revision("test"));
        assertEquals("low",frozen.parameters().get("quality"));assertFalse(frozen.parameters().containsKey("size"));assertEquals("test-image",frozen.model());
        var otherEndpoint=new Provider(Protocol.OPENAI_IMAGES,URI.create("https://example.com/v1"),"key","test-image",Map.of(),List.of(),2);
        var otherService=new GenerationService(new GenerationProperties(Map.of("test",otherEndpoint)),closet,json);
        assertEquals("PROVIDER_CONFIG_CHANGED",assertThrows(ProcessingException.class,()->otherService.frozen(saved,oldService.revision("test"))).code());
    }
    @Test void openaiEditUsesMultipartAndInputImage() throws Exception {
        var service=service(Protocol.OPENAI_IMAGES);
        byte[] input = image();
        String prompt = "整理衣物，保留印花位置";
        service.generate(service.prepare(new GenerationService.Request("test", null, prompt, Map.of("quality","high"))),input);
        assertEquals("/v1/images/edits",path.get());assertTrue(contentType.get().startsWith("multipart/form-data; boundary="));
        assertTrue(request.get().contains("name=\"image\"; filename=\"input.png\""));
        assertTrue(request.get().contains("name=\"quality\"\r\n\r\nhigh"));
        String boundary = contentType.get().substring(contentType.get().indexOf("boundary=") + 9);
        String body = request.get();
        int start = body.indexOf("\r\n\r\n", body.indexOf("filename=\"input.png\"")) + 4;
        int end = body.indexOf("\r\n--" + boundary + "--", start);
        assertArrayEquals(input, body.substring(start, end).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        String utf8Body = new String(body.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(utf8Body.contains("name=\"prompt\"\r\n\r\n" + prompt + "\r\n"));
    }
    @Test void geminiSupportsTextAndImageRequests() throws Exception {
        response="{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"done\"},{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\""+Base64.getEncoder().encodeToString(image())+"\"}}]}}],\"usageMetadata\":{\"totalTokenCount\":7}}";
        var service=service(Protocol.GEMINI);var prepared=service.prepare(spec(Map.of("imageConfig",Map.of("aspectRatio","1:1"))));
        var result=service.generate(prepared,image());assertEquals("/v1/models/test-image:generateContent",path.get());assertEquals("fake-secret",auth.get());
        var sent=json.readTree(request.get());assertEquals("image/png",sent.at("/contents/0/parts/1/inlineData/mimeType").asText());
        assertEquals(1,sent.at("/generationConfig/candidateCount").asInt());assertEquals(7,result.usage().path("totalTokenCount").asInt());
        service.generate(prepared,null);assertEquals(1,json.readTree(request.get()).at("/contents/0/parts").size());
    }
    @Test void noImageMalformedImageAndProviderFailureAreSanitized() {
        var service=service(Protocol.OPENAI_IMAGES);var spec=service.prepare(spec(Map.of()));
        response="{\"data\":[]}";assertEquals("PROVIDER_NO_IMAGE",assertThrows(ProcessingException.class,()->service.generate(spec,null)).code());
        response="{\"data\":[{\"b64_json\":\"aGVsbG8=\"}]}";assertEquals("PROVIDER_INVALID_IMAGE",assertThrows(ProcessingException.class,()->service.generate(spec,null)).code());
        status=502;response="fake-secret https://private-url/";var error=assertThrows(ProcessingException.class,()->service.generate(spec,null));
        assertEquals("PROVIDER_HTTP_502",error.code());assertFalse(error.getMessage().contains("fake-secret"));
    }
    @Test void refusesReservedParametersAndUntrustedOutputUrls() {
        var service=service(Protocol.OPENAI_IMAGES);
        for (String key:List.of("n","image","stream","base_url","api_key","headers","model","prompt"))
            assertThrows(ProcessingException.class,()->service.prepare(spec(Map.of(key,"bad"))),key);
        var prepared=service.prepare(spec(Map.of()));response="{\"data\":[{\"url\":\"http://127.0.0.1:1/private\"}]}";
        assertEquals("PROVIDER_IMAGE_HOST_NOT_ALLOWED",assertThrows(ProcessingException.class,()->service.generate(prepared,null)).code());
    }
    @Test void modelListingIsReadOnlyAndCandidateFlagIsHeuristic() {
        response="{\"data\":[{\"id\":\"gpt-image-1\"},{\"id\":\"chat-model\"}]}";
        var models=service(Protocol.OPENAI_IMAGES).models("test");assertEquals("/v1/models",path.get());assertTrue(models.get(0).imageCandidate());assertFalse(models.get(1).imageCandidate());
    }
    @Test void boundedTransportRejectsLargeBodiesAndRedirects() {
        var transport=new BoundedHttp();response="x".repeat(1024);
        var req=HttpRequest.newBuilder(base()).timeout(Duration.ofSeconds(2)).GET().build();
        assertThrows(ProcessingException.class,()->transport.send(req,64));
        status=302;response="redirect";assertEquals(302,transport.send(req,100).statusCode());
    }
    @Test void transportTimeoutIsBoundedAndNotRetried() throws Exception {
        server.removeContext("/");var calls=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/",e->{calls.incrementAndGet();try{Thread.sleep(500);}catch(InterruptedException ignored){}e.close();});
        var request=HttpRequest.newBuilder(base()).timeout(Duration.ofMillis(80)).GET().build();
        var error=assertThrows(ProcessingException.class,()->new BoundedHttp().send(request,100));
        assertEquals("PROVIDER_TIMEOUT",error.code());assertTrue(error.getMessage().contains("本机等待"));assertEquals(1,calls.get());
    }
    @Test void upstream504And429HaveSpecificSafeDiagnostics() {
        var service=service(Protocol.OPENAI_IMAGES);var prepared=service.prepare(spec(Map.of()));
        status=504;response="fake-secret signed-url";
        var timeout=assertThrows(ProcessingException.class,()->service.generate(prepared,null));
        assertEquals("PROVIDER_HTTP_504",timeout.code());assertTrue(timeout.getMessage().contains("网关等待上游超时"));
        assertFalse(timeout.getMessage().contains("余额"));assertFalse(timeout.getMessage().contains("fake-secret"));
        status=429;var limited=assertThrows(ProcessingException.class,()->service.generate(prepared,null));
        assertEquals("PROVIDER_HTTP_429",limited.code());assertTrue(limited.getMessage().contains("速率、并发或额度"));
        status=400;var invalid=assertThrows(ProcessingException.class,()->service.generate(prepared,null));assertTrue(invalid.getMessage().contains("生成参数"));
        status=401;var auth=assertThrows(ProcessingException.class,()->service.generate(prepared,null));assertTrue(auth.getMessage().contains("认证或访问权限"));
    }

    @Test void purposeLabeledReferencesAreSentTogetherWithoutDroppingItems()throws Exception {
        var service=service(Protocol.OPENAI_IMAGES);var prepared=service.prepare(spec(Map.of()));
        service.generateReferences(prepared,List.of(new GenerationService.Reference("PERSON_BASELINE","人物",image()),new GenerationService.Reference("GARMENT","上衣",image()),new GenerationService.Reference("GARMENT","裤子",image())));
        assertTrue(request.get().contains("filename=\"reference-0.png\""));assertTrue(request.get().contains("filename=\"reference-1.png\""));assertTrue(request.get().contains("filename=\"reference-2.png\""));assertTrue(request.get().contains("name=\"image[]\""));
        var gemini=service(Protocol.GEMINI);response="{\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\""+Base64.getEncoder().encodeToString(image())+"\"}}]}}]}";
        gemini.generateReferences(gemini.prepare(spec(Map.of())),List.of(new GenerationService.Reference("PERSON_BASELINE","人物",image()),new GenerationService.Reference("GARMENT","上衣",image())));
        var parts=json.readTree(request.get()).at("/contents/0/parts");assertEquals(5,parts.size());assertTrue(parts.get(1).path("text").asText().contains("PERSON_BASELINE"));assertTrue(parts.get(3).path("text").asText().contains("GARMENT"));
    }
}
