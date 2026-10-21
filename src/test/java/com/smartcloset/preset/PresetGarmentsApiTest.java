package com.smartcloset.preset;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcloset.security.ClosetAccountService;
import com.smartcloset.workflow.WorkflowFiles;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Live routing/security/media tests. Run outside sandboxes that prohibit binding a local HTTP port. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.config.import=optional:file:./target/no-local-secrets.yml", "closet.model-path=target/absent.onnx",
        "closet.embedding-model-path=target/absent-embedding.onnx", "closet.account.registration=OPEN"})
class PresetGarmentsApiTest {
    @TempDir static Path storage;
    @DynamicPropertySource static void config(DynamicPropertyRegistry p) { p.add("closet.storage-dir",()->storage.toString()); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired ClosetAccountService accounts;
    @Autowired WorkflowFiles files;
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final String PASSWORD="RegisteredPassword123!";
    final class Browser {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        HttpResponse<String> send(String method,String path,Object body) throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
            if (!method.equals("GET")) {
                var csrf=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/auth/csrf")).build(),HttpResponse.BodyHandlers.ofString());
                request.header("Content-Type","application/json").header("X-XSRF-TOKEN",JSON.readTree(csrf.body()).path("token").asText());
            }
            return http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> upload(String path,UUID key,byte[] photo) throws Exception {
            var csrf=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/auth/csrf")).build(),HttpResponse.BodyHandlers.ofString());
            String boundary="preset-photo-"+UUID.randomUUID(); var body=new ByteArrayOutputStream();
            body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"workflow\"\r\nContent-Type: application/json\r\n\r\n{\"mode\":\"本地贴纸\"}\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"photo.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            body.write(photo); body.write(("\r\n--"+boundary+"--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Idempotency-Key",key.toString())
                    .header("X-XSRF-TOKEN",JSON.readTree(csrf.body()).path("token").asText()).header("Content-Type","multipart/form-data; boundary="+boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
            return http.send(request,HttpResponse.BodyHandlers.ofString());
        }
        JsonNode ok(String method,String path,Object body) throws Exception {
            var r=send(method,path,body); assertEquals(2,r.statusCode()/100,r.statusCode()+" "+r.body()); return JSON.readTree(r.body());
        }
        Browser login(String username) throws Exception { ok("POST","/api/auth/login",Map.of("username",username,"password",PASSWORD)); return this; }
    }
    String account(String role) { String name="preset-"+UUID.randomUUID().toString().substring(0,8); accounts.create(name,PASSWORD,role,null,"ADMIN",null,false); return name; }
    record Seed(String owner,UUID garment,UUID asset,UUID job) {}
    Seed source() throws Exception {
        String owner=account("USER"); UUID garment=UUID.randomUUID(),job=UUID.randomUUID();
        var image=new BufferedImage(320,320,BufferedImage.TYPE_INT_ARGB); for(int y=10;y<310;y++)for(int x=10;x<310;x++)image.setRGB(x,y,0xff336699);
        var out=new ByteArrayOutputStream(); ImageIO.write(image,"png",out);
        var a=files.write(job,"base-cutout.png","BASE",out.toByteArray()); long now=System.currentTimeMillis();
        db.update("INSERT INTO workflow_job(id,fingerprint,status,created_at_ms,updated_at_ms,request_json,requires_review,version,owner_username) VALUES(?,?,'SUCCEEDED',?,?,'{}',0,0,?)",job.toString(),a.sha256,now,now,owner);
        db.update("INSERT INTO image_asset(id,job_id,file_name,sha256,byte_size,width,height,kind,review_status,created_at_ms) VALUES(?,?,?,?,?,?,?,'BASE','APPROVED',?)",a.id,job.toString(),a.fileName,a.sha256,a.byteSize,a.width,a.height,now);
        db.update("INSERT INTO garment(id,source_asset_id,display_asset_id,source_origin,overrides_json,display_user_selected,revision,created_at_ms,updated_at_ms,owner_username,closet_status,name) VALUES(?,?,?,'USER_UPLOAD',?,0,0,?,?,?,'ACTIVE','蓝衬衫')",garment.toString(),a.id,a.id,JSON.writeValueAsString(Map.of("category",List.of("衬衫"),"primaryColor",List.of("蓝色"),"visibleText",List.of("private-text"))),now,now,owner);
        db.update("UPDATE workflow_job SET garment_id=? WHERE id=?",garment.toString(),job.toString());
        return new Seed(owner,garment,UUID.fromString(a.id),job);
    }
    JsonNode draft(Browser admin,Seed seed) throws Exception {
        var result=admin.ok("POST","/api/admin/presets/import",Map.of("username",seed.owner(),"garmentIds",List.of(seed.garment())));
        assertEquals(1,result.size()); return result.get(0);
    }
    @Test void anonymousCannotReadOrImportPresets() throws Exception {
        var browser=new Browser(); assertEquals(401,browser.send("GET","/api/presets",null).statusCode());
        assertEquals(401,browser.send("POST","/api/presets/import",Map.of("ids",List.of(UUID.randomUUID()))).statusCode());
    }
    @Test void everyAdminEndpointRejectsOrdinaryUsersWith403() throws Exception {
        var user=new Browser().login(account("USER")); String id=UUID.randomUUID().toString();
        assertEquals(403,user.send("GET","/api/admin/presets",null).statusCode());
        assertEquals(403,user.send("GET","/api/admin/presets/coverage",null).statusCode());
        assertEquals(403,user.send("POST","/api/admin/presets/import",Map.of("username","source")).statusCode());
        assertEquals(403,user.send("PATCH","/api/admin/presets/"+id,Map.of("status","LISTED")).statusCode());
    }
    @Test void adminReviewUserImportFiltersAndIndependentEditsWorkThroughHttp() throws Exception {
        var seed=source(); var admin=new Browser().login(account("ADMIN")); var draft=draft(admin,seed); String id=draft.path("id").asText();
        assertEquals("DRAFT",draft.path("status").asText()); assertFalse(draft.path("fields").has("visibleText"));
        assertTrue(admin.ok("GET","/api/admin/presets?status=DRAFT",null).isArray());
        var user=new Browser().login(account("USER"));
        assertEquals(400,user.send("POST","/api/presets/import",Map.of("ids",List.of(id))).statusCode());
        admin.ok("PATCH","/api/admin/presets/"+id,Map.of("status","LISTED","gender","男款","styles",List.of("知性"),"seasons",List.of("秋"),"sort",10));
        var listed=user.ok("GET","/api/presets?gender="+enc("男款")+"&styles="+enc("知性")+"&season="+enc("秋"),null);
        assertTrue(java.util.stream.StreamSupport.stream(listed.spliterator(),false).anyMatch(x->x.path("id").asText().equals(id)));
        assertFalse(listed.toString().contains("sourceOwner"));
        var imported=user.ok("POST","/api/presets/import",Map.of("ids",List.of(id))); String garment=imported.path("garmentIds").get(0).asText();
        var detail=user.ok("GET","/api/garments/"+garment,null); assertEquals("PRESET",detail.path("sourceOrigin").asText());
        assertEquals("ACTIVE",detail.path("status").asText()); assertFalse(detail.path("favorite").asBoolean());
        user.ok("PATCH","/api/garments/"+garment+"/metadata",Map.of("revision",detail.path("revision").asLong(),"fields",Map.of("primaryColor",List.of("红色"))));
        var bob=new Browser().login(account("USER")); String other=bob.ok("POST","/api/presets/import",Map.of("ids",List.of(id))).path("garmentIds").get(0).asText();
        assertEquals("蓝色",bob.ok("GET","/api/garments/"+other,null).path("effective").path("primaryColor").get(0).asText());
        assertEquals(id,user.ok("POST","/api/presets/import",Map.of("ids",List.of(id))).path("skipped").get(0).asText());
        assertTrue(admin.ok("GET","/api/admin/presets/coverage",null).size()>0);
        assertEquals(400,user.send("POST","/api/presets/import",Map.of("ids",Collections.nCopies(101,id))).statusCode());
        admin.ok("PATCH","/api/admin/presets/"+id,Map.of("status","UNLISTED"));
        assertEquals("ACTIVE",user.ok("GET","/api/garments/"+garment,null).path("status").asText());
    }
    @Test void sharedFilesAndThumbnailsAreReadOnlyEvenForAdminAndSourceFilesStayPrivate() throws Exception {
        var seed=source(); var admin=new Browser().login(account("ADMIN")); var p=draft(admin,seed);
        String url=p.path("displayUrl").asText(),asset=p.path("displayAssetId").asText();
        String job=url.split("/")[3]; var user=new Browser().login(account("USER"));
        assertEquals(200,user.send("GET",url,null).statusCode()); assertEquals(200,user.send("GET",url+"?w=160",null).statusCode());
        for (var browser:List.of(user,admin)) {
            assertEquals(404,browser.send("GET","/api/workflows/"+job,null).statusCode(),"the shared job metadata is not public");
            assertEquals(404,browser.send("POST","/api/workflows/"+job+"/cancel",Map.of()).statusCode());
            assertEquals(404,browser.send("POST","/api/workflows/"+job+"/assets/"+asset+"/review",Map.of("decision","REJECT")).statusCode());
        }
        assertEquals(404,user.send("GET","/api/workflows/"+seed.job()+"/files/input.png",null).statusCode());
        assertEquals(401,new Browser().send("GET",url,null).statusCode());
        assertEquals(405,user.send("DELETE","/api/workflows/"+job,Map.of()).statusCode(),"there is no workflow deletion route");
    }
    @Test void replacingPhotoUsesAnOwnedJobAndRetainsFieldsAndPresetId() throws Exception {
        var seed=source(); var admin=new Browser().login(account("ADMIN")); var p=draft(admin,seed); String id=p.path("id").asText();
        admin.ok("PATCH","/api/admin/presets/"+id,Map.of("status","LISTED"));
        String alice=account("USER"); var user=new Browser().login(alice);
        String copy=user.ok("POST","/api/presets/import",Map.of("ids",List.of(id))).path("garmentIds").get(0).asText();
        var image=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB); var bytes=new ByteArrayOutputStream(); ImageIO.write(image,"png",bytes);
        UUID key=UUID.randomUUID(); var uploaded=user.upload("/api/closet/garments/"+copy+"/photo",key,bytes.toByteArray());
        assertEquals(202,uploaded.statusCode(),uploaded.body());
        assertEquals(copy,JSON.readTree(uploaded.body()).path("request").path("garmentId").asText());
        assertEquals(alice,db.queryForObject("SELECT owner_username FROM workflow_job WHERE id=?",String.class,key.toString()));
        String asset=db.queryForObject("SELECT id FROM image_asset WHERE job_id=? AND kind='INPUT'",String.class,key.toString());
        user.ok("POST","/api/workflows/"+key+"/cancel",Map.of());
        String state="";
        for (int i=0;i<100;i++) {
            state=user.ok("GET","/api/workflows/"+key,null).path("status").asText();
            if (!Set.of("QUEUED","RUNNING","CANCEL_REQUESTED").contains(state)) break;
            Thread.sleep(50);
        }
        assertFalse(Set.of("QUEUED","RUNNING","CANCEL_REQUESTED").contains(state));
        var before=user.ok("GET","/api/garments/"+copy,null);
        var after=user.ok("POST","/api/garments/"+copy+"/display-image",Map.of("revision",before.path("revision").asLong(),"assetId",asset));
        assertEquals("USER_UPLOAD",after.path("sourceOrigin").asText()); assertEquals(before.path("effective"),after.path("effective"));
        assertEquals(id,db.queryForObject("SELECT preset_id FROM garment WHERE id=?",String.class,copy));
        assertEquals(200,user.send("GET",p.path("displayUrl").asText(),null).statusCode());
        var other=new Browser().login(account("USER"));
        assertEquals(404,other.upload("/api/closet/garments/"+copy+"/photo",UUID.randomUUID(),bytes.toByteArray()).statusCode());
    }
    @Test void onlyPresetCopiesCanTakeADirectPhotoReplacement() throws Exception {
        var seed=source(); var owner=new Browser().login(seed.owner());
        var image=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB); var bytes=new ByteArrayOutputStream(); ImageIO.write(image,"png",bytes);
        var refused=owner.upload("/api/closet/garments/"+seed.garment()+"/photo",UUID.randomUUID(),bytes.toByteArray());
        assertEquals(400,refused.statusCode(),"an ordinary garment keeps using reprocessing: "+refused.body());
        var stranger=new Browser().login(account("USER"));
        assertEquals(404,stranger.upload("/api/closet/garments/"+seed.garment()+"/photo",UUID.randomUUID(),bytes.toByteArray()).statusCode());
    }
    @Test void reservedNamespaceCannotRegisterThroughHttp() throws Exception {
        assertEquals(400,new Browser().send("POST","/api/auth/register",Map.of("username","__preset__","password",PASSWORD)).statusCode());
        assertEquals(401,new Browser().send("POST","/api/auth/login",Map.of("username","__preset__","password",PASSWORD)).statusCode());
    }
    static String enc(String s) { return URLEncoder.encode(s,java.nio.charset.StandardCharsets.UTF_8); }
}
