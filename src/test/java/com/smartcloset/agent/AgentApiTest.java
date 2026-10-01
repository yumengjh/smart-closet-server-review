package com.smartcloset.agent;

import com.smartcloset.security.ClosetAccountService;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.config.import=optional:file:./target/no-local-secrets.yml","closet.model-path=target/absent-agent-model.onnx",
 "agent.secret=01234567890123456789012345678901","agent.daily-limit=2",
 "generation.providers.yumg.protocol=OPENAI_IMAGES","generation.providers.yumg.base-url=http://127.0.0.1:1/v1","generation.providers.yumg.api-key=fake-test-key"})
class AgentApiTest {
 @TempDir static Path storage;
 static final JsonMapper json=JsonMapper.builder().build();
 static final HttpServer runtime;
 static { try { runtime=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); runtime.createContext("/health",exchange->{exchange.sendResponseHeaders(200,0);exchange.close();});runtime.start(); }catch(Exception e){throw new ExceptionInInitializerError(e);} }
 @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("closet.storage-dir",()->storage.toString());r.add("agent.runtime-url",()->"http://127.0.0.1:"+runtime.getAddress().getPort());}
 @AfterAll static void shutdown(){runtime.stop(0);}
 @LocalServerPort int port;
 @Autowired AgentRepository repo; @Autowired AgentService service; @Autowired HelpService help;
 @Autowired ClosetAccountService accounts; @Autowired JdbcTemplate db;
 HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build(); String csrf;
 HttpResponse<String> send(String method,String path,Object body)throws Exception{
  var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json");
  if(csrf!=null&&!method.equals("GET"))b.header("X-XSRF-TOKEN",csrf);
  return client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
 }
 String login()throws Exception{
  String owner="agent-"+UUID.randomUUID().toString().substring(0,8);accounts.create(owner,"agent-test-password","USER");
  csrf=json.readTree(send("GET","/api/auth/csrf",null).body()).path("token").asText();
  assertEquals(200,send("POST","/api/auth/login",Map.of("username",owner,"password","agent-test-password")).statusCode());
  assertEquals(204,send("POST","/api/auth/password",Map.of("currentPassword","agent-test-password","newPassword","agent-test-password")).statusCode());return owner;
 }
 @Test void authenticationCsrfIsolationAndIndependentMemory()throws Exception{
  assertEquals(401,send("GET","/api/agent/sessions",null).statusCode());String owner=login();
  var s=json.readTree(send("POST","/api/agent/sessions",Map.of()).body());String id=s.path("id").asText();
  assertEquals(200,send("POST","/api/agent/memories",Map.of("content","我喜欢蓝色","category","COLOR")).statusCode());
  assertEquals(400,send("PUT","/api/agent/preferences",Map.of("model","evil-unapproved-model")).statusCode());
  assertEquals(204,send("DELETE","/api/agent/sessions/"+id,null).statusCode());assertEquals(1,repo.memories(owner).size());
  var second=repo.create(owner);String stranger="stranger-"+UUID.randomUUID().toString().substring(0,8);accounts.create(stranger,"agent-test-password","USER");
  assertThrows(com.smartcloset.image.ProcessingException.class,()->repo.session(stranger,second.id()));
  assertThrows(com.smartcloset.image.ProcessingException.class,()->repo.deleteMemory(stranger,repo.memories(owner).getFirst().id()));
  String old=csrf;csrf=null;assertEquals(403,send("POST","/api/agent/sessions",Map.of()).statusCode());csrf=old;
  assertEquals(403,send("POST","/api/agent/internal/tool",Map.of("name","search_help","args",Map.of("query","搭配"))).statusCode());
 }
 @Test void dailyQuotaIdempotencyAndBusyDeletion()throws Exception{
  String owner=login();var s=repo.create(owner);String request=UUID.randomUUID().toString();repo.begin(owner,s.id(),request,"请帮我搭配");
  assertThrows(com.smartcloset.image.ProcessingException.class,()->repo.delete(owner,s.id()));
  repo.finish(owner,s.id(),request,"回复","COMPLETE",List.of());
  assertThrows(com.smartcloset.image.ProcessingException.class,()->repo.begin(owner,s.id(),request,"请帮我搭配"));
  assertEquals(1,repo.quota(owner).get("used"));String r2=UUID.randomUUID().toString();repo.begin(owner,s.id(),r2,"再试试");repo.finish(owner,s.id(),r2,"","FAILED",List.of());
  assertThrows(com.smartcloset.image.ProcessingException.class,()->repo.begin(owner,s.id(),UUID.randomUUID().toString(),"超额"));
  assertEquals(2,repo.quota(owner).get("used"));repo.clear(owner);assertEquals(2,repo.quota(owner).get("used"));
 }
 @Test void staleContextSingleUseAndHistoryReadback()throws Exception{
  String owner=login();var s=repo.create(owner);var context=json.readTree("{\"route\":\"/outfits/new\",\"scene\":{\"elements\":[]}}");
  var p=repo.propose(owner,s.id(),"SAVE",json.createObjectNode(),context);
  assertThrows(com.smartcloset.image.ProcessingException.class,()->service.accept(owner,p.id(),json.readTree("{\"route\":\"/wardrobe\"}"),true));
  service.accept(owner,p.id(),context,true);assertThrows(com.smartcloset.image.ProcessingException.class,()->service.accept(owner,p.id(),context,true));
  var r=UUID.randomUUID().toString();repo.begin(owner,s.id(),r,"保存");repo.finish(owner,s.id(),r,"等待确认","COMPLETE",List.of(Map.of("event","proposal","data",p)));
  assertTrue(repo.messages(owner,s.id()).stream().filter(m->m.role().equals("assistant")).findFirst().orElseThrow().events().get(0).path("data").path("consumed").asBoolean());
 }
 @Test void sseCompletesAndPersistsWithoutLeakingCredentials()throws Exception{
  String owner=login();var s=repo.create(owner);
  runtime.createContext("/run",exchange->{
   assertEquals("01234567890123456789012345678901",exchange.getRequestHeaders().getFirst("X-Agent-Secret"));
   var input=json.readTree(exchange.getRequestBody().readAllBytes());assertEquals("gpt-6-luna",input.path("model").asText());
   var result=service.tool(input.path("callbackToken").asText(),new AgentService.ToolRequest("search_help",json.readTree("{\"query\":\"组合台保存草稿\"}")));
   assertNotNull(result);
   assertThrows(com.smartcloset.image.ProcessingException.class,()->service.tool(input.path("callbackToken").asText(),new AgentService.ToolRequest("navigate",json.readTree("{\"path\":\"https://example.com\"}"))));
   assertThrows(com.smartcloset.image.ProcessingException.class,()->service.tool(input.path("callbackToken").asText(),new AgentService.ToolRequest("remember_preference",json.readTree("{\"evidence\":\"不存在的原话\",\"content\":\"红色\",\"category\":\"COLOR\"}"))));
   byte[] out="{\"event\":\"token\",\"data\":{\"text\":\"测试回复\"}}\n{\"event\":\"done\",\"data\":{}}\n".getBytes(StandardCharsets.UTF_8);
   exchange.getResponseHeaders().set("Content-Type","application/x-ndjson");exchange.sendResponseHeaders(200,out.length);exchange.getResponseBody().write(out);exchange.close();
  });
  try{
   var response=send("POST","/api/agent/sessions/"+s.id()+"/messages",Map.of("requestId",UUID.randomUUID().toString(),"content","怎样使用组合台？","context",Map.of("route","/wardrobe")));
   assertEquals(200,response.statusCode(),response.body());assertTrue(response.body().contains("event: done"));assertFalse(response.body().contains("fake-test-key"));
   for(int n=0;n<20 && repo.messages(owner,s.id()).size()<2;n++)Thread.sleep(25);
   assertEquals(2,repo.messages(owner,s.id()).size());assertEquals(1,repo.quota(owner).get("used"));
  }finally{runtime.removeContext("/run");}
 }
 private String clothing(String owner,String status) {
  String id=UUID.randomUUID().toString(),job=UUID.randomUUID().toString(),asset=UUID.randomUUID().toString();long now=System.currentTimeMillis();
  db.update("INSERT INTO workflow_job(id,fingerprint,status,created_at_ms,updated_at_ms,request_json,requires_review,version,owner_username) VALUES(?,?,'SUCCEEDED',?,?,'{}',0,0,?)",job,UUID.randomUUID().toString(),now,now,owner);
  db.update("INSERT INTO image_asset(id,job_id,file_name,sha256,byte_size,width,height,kind,review_status,created_at_ms) VALUES(?,?,'input.png','fixture',1,120,160,'INPUT','APPROVED',?)",asset,job,now);
  db.update("INSERT INTO garment(id,source_asset_id,display_asset_id,source_origin,overrides_json,display_user_selected,revision,created_at_ms,updated_at_ms,owner_username,closet_status) VALUES(?,?,?,'USER_UPLOAD','{}',0,0,?,?,?,?)",id,asset,asset,now,now,owner,status);
  db.update("UPDATE workflow_job SET garment_id=? WHERE id=?",id,job);return id;
 }
 private JsonNode candidates(List<List<String>> sets){return json.valueToTree(Map.of("candidates",sets.stream().map(ids->Map.of("name","方案","reason","真实衣物","garmentIds",ids)).toList()));}
 @Test void toolsRequireOwnerActiveGarmentsPinnedSelectionAndConsent()throws Exception{
  String owner=login();var s=repo.create(owner);String a=clothing(owner,"ACTIVE"),b=clothing(owner,"ACTIVE"),c=clothing(owner,"ACTIVE");
  String other="other-"+UUID.randomUUID().toString().substring(0,8);accounts.create(other,"agent-test-password","USER");String foreign=clothing(other,"ACTIVE"),pending=clothing(owner,"DRAFT");
  var token=new java.util.concurrent.atomic.AtomicReference<String>();
  runtime.createContext("/run",exchange->{
   var input=json.readTree(exchange.getRequestBody().readAllBytes());token.set(input.path("callbackToken").asText());
   assertThrows(com.smartcloset.image.ProcessingException.class,()->service.tool(token.get(),new AgentService.ToolRequest("get_garment",json.valueToTree(Map.of("id",foreign)))));
   assertThrows(com.smartcloset.image.ProcessingException.class,()->service.tool(token.get(),new AgentService.ToolRequest("propose_outfits",candidates(List.of(List.of(b),List.of(c),List.of(b,c))))));
   assertThrows(com.smartcloset.image.ProcessingException.class,()->service.tool(token.get(),new AgentService.ToolRequest("propose_outfits",candidates(List.of(List.of(a,b),List.of(a,pending),List.of(a,c))))));
   assertThrows(com.smartcloset.image.ProcessingException.class,()->service.tool(token.get(),new AgentService.ToolRequest("propose_outfits",candidates(List.of(List.of(a,b),List.of(b,a),List.of(a,c))))));
   assertThrows(com.smartcloset.image.ProcessingException.class,()->service.tool(token.get(),new AgentService.ToolRequest("remember_preference",json.valueToTree(Map.of("category","PERSONAL","content","身高170","evidence","身高170")))));
   var p=service.tool(token.get(),new AgentService.ToolRequest("propose_outfits",candidates(List.of(List.of(a,b),List.of(a,c),List.of(a,b,c)))));
   var frame=json.writeValueAsString(Map.of("event","proposal","data",p))+"\n{\"event\":\"done\",\"data\":{}}\n";
   byte[] out=frame.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,out.length);exchange.getResponseBody().write(out);exchange.close();
  });
  try{
   var response=send("POST","/api/agent/sessions/"+s.id()+"/messages",Map.of("requestId",UUID.randomUUID().toString(),"content","用这件衣物组合三套，我身高170","context",Map.of("route","/garment/"+a,"garmentId",a)));
   assertEquals(200,response.statusCode());assertTrue(response.body().contains("event: proposal"),response.body());
   assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM outfit WHERE owner_username=?",Integer.class,owner));assertTrue(repo.memories(owner).isEmpty());
   assertThrows(com.smartcloset.image.ProcessingException.class,()->service.tool(token.get(),new AgentService.ToolRequest("search_help",json.createObjectNode())));
  }finally{runtime.removeContext("/run");}
 }
 @Test void helpIsSharedAndUnknownRetrievalStaysUnknown(){assertEquals(3,help.articles().size());assertFalse(help.search("组合台保存草稿").isEmpty());assertTrue(help.search("宇宙量子飞船驾驶").isEmpty());}
}
