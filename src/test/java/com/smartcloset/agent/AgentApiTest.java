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
 "agent.secret=01234567890123456789012345678901","agent.daily-limit=2","agent.max-tool-calls=20",
 "generation.providers.yumg.protocol=OPENAI_IMAGES","generation.providers.yumg.base-url=http://127.0.0.1:1/v1","generation.providers.yumg.api-key=fake-test-key"})
class AgentApiTest {
 @TempDir static Path storage;
 static final JsonMapper json=JsonMapper.builder().build();
 static final HttpServer runtime;
 static { try { runtime=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); runtime.createContext("/health",exchange->{exchange.sendResponseHeaders(200,0);exchange.close();});runtime.start(); }catch(Exception e){throw new ExceptionInInitializerError(e);} }
 @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("closet.storage-dir",()->storage.toString());r.add("agent.runtime-url",()->"http://127.0.0.1:"+runtime.getAddress().getPort());}
 @AfterAll static void shutdown(){runtime.stop(0);}
 @LocalServerPort int port;
 @Autowired com.smartcloset.person.PrivateMedia privateMedia; @Autowired AgentTraceService traces; @Autowired AgentRepository repo; @Autowired AgentService service; @Autowired HelpService help;
 @Autowired com.smartcloset.garment.ClosetSummaryService summaries; @Autowired com.smartcloset.garment.GarmentRepository garments; @Autowired CompositionReviews reviews; @Autowired AgentMemoryDocuments documents; @Autowired AgentCommands commands;
 @Autowired com.smartcloset.recommend.RecommendationService recommendations; @Autowired AgentPlatformCommands platformCommands; @Autowired ClosetAccountService accounts; @Autowired JdbcTemplate db;
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
 @Test void avatarPreferenceIsIndependentValidatedAndAccountScoped()throws Exception{
  String owner=login();assertEquals("personal",repo.avatarMode(owner));
  assertEquals(200,send("PUT","/api/agent/preferences",Map.of("avatarMode","basic")).statusCode());
  assertEquals("basic",repo.avatarMode(owner));assertEquals("gpt-6-luna",repo.preference(owner));
  assertEquals(200,send("PUT","/api/agent/preferences",Map.of("model","gpt-6-luna")).statusCode());assertEquals("basic",repo.avatarMode(owner));
  assertEquals(400,send("PUT","/api/agent/preferences",Map.of("avatarMode","invalid","model","gpt-6-luna")).statusCode());assertEquals("basic",repo.avatarMode(owner));
  String other="avatar-"+UUID.randomUUID();accounts.create(other,"agent-test-password","USER");assertEquals("personal",repo.avatarMode(other));
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
   var input=json.readTree(exchange.getRequestBody().readAllBytes());assertEquals("gpt-6-luna",input.path("model").asText());assertEquals("personal",input.path("avatarMode").asText());
   var result=service.tool(input.path("callbackToken").asText(),new AgentService.ToolRequest("search_help",json.readTree("{\"query\":\"组合台保存草稿\"}")));
   assertNotNull(result);
   assertThrows(com.smartcloset.image.ProcessingException.class,()->service.tool(input.path("callbackToken").asText(),new AgentService.ToolRequest("navigate",json.readTree("{\"path\":\"https://example.com\"}"))));
   assertThrows(com.smartcloset.image.ProcessingException.class,()->service.tool(input.path("callbackToken").asText(),new AgentService.ToolRequest("remember_preference",json.readTree("{\"evidence\":\"不存在的原话\",\"content\":\"红色\",\"category\":\"COLOR\"}"))));
   byte[] out=("{\"event\":\"avatar\",\"data\":{\"state\":\"bad\",\"expression\":\"happy\"}}\n{\"event\":\"avatar\",\"data\":{\"state\":\"wink\",\"expression\":\"happy\"}}\n{\"event\":\"avatar\",\"data\":{\"state\":\"wide\",\"expression\":\"happy\"}}\n"+"{\"event\":\"token\",\"data\":{\"text\":\"测试回复\"}}\n{\"event\":\"done\",\"data\":{}}\n").getBytes(StandardCharsets.UTF_8);
   exchange.getResponseHeaders().set("Content-Type","application/x-ndjson");exchange.sendResponseHeaders(200,out.length);exchange.getResponseBody().write(out);exchange.close();
  });
  try{
   var response=send("POST","/api/agent/sessions/"+s.id()+"/messages",Map.of("requestId",UUID.randomUUID().toString(),"content","怎样使用组合台？","context",Map.of("route","/wardrobe")));
   assertEquals(200,response.statusCode(),response.body());assertTrue(response.body().contains("event: done"));assertEquals(1,response.body().split("event: avatar",-1).length-1);assertFalse(response.body().contains("fake-test-key"));
   for(int n=0;n<20 && repo.messages(owner,s.id()).size()<2;n++)Thread.sleep(25);
   assertEquals(2,repo.messages(owner,s.id()).size());assertEquals(1,repo.quota(owner).get("used"));for(var m:repo.messages(owner,s.id()))for(var e:m.events())assertNotEquals("avatar",e.path("event").asText());
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
 private String clothing(String owner,String status,String category) {
  String id=clothing(owner,status);db.update("UPDATE garment SET overrides_json=? WHERE id=?",json.writeValueAsString(Map.of("category",List.of(category))),id);return id;
 }
 @Test void toolsRequireOwnerActiveGarmentsPinnedSelectionAndConsent()throws Exception{
  String owner=login();var s=repo.create(owner);String a=clothing(owner,"ACTIVE","衬衫"),b=clothing(owner,"ACTIVE","长裤"),c=clothing(owner,"ACTIVE","短裤"),d=clothing(owner,"ACTIVE","半身裙");
  String other="other-"+UUID.randomUUID().toString().substring(0,8);accounts.create(other,"agent-test-password","USER");String foreign=clothing(other,"ACTIVE"),pending=clothing(owner,"DRAFT");
  var token=new java.util.concurrent.atomic.AtomicReference<String>();
  runtime.createContext("/run",exchange->{
   var input=json.readTree(exchange.getRequestBody().readAllBytes());token.set(input.path("callbackToken").asText());
   assertThrows(com.smartcloset.image.ProcessingException.class,()->service.tool(token.get(),new AgentService.ToolRequest("get_garment",json.valueToTree(Map.of("id",foreign)))));
   assertThrows(com.smartcloset.image.ProcessingException.class,()->service.tool(token.get(),new AgentService.ToolRequest("propose_outfits",candidates(List.of(List.of(b),List.of(c),List.of(b,c))))));
   assertThrows(com.smartcloset.image.ProcessingException.class,()->service.tool(token.get(),new AgentService.ToolRequest("propose_outfits",candidates(List.of(List.of(a,b),List.of(a,pending),List.of(a,c))))));
   assertThrows(com.smartcloset.image.ProcessingException.class,()->service.tool(token.get(),new AgentService.ToolRequest("propose_outfits",candidates(List.of(List.of(a,b),List.of(b,a),List.of(a,c))))));
   assertThrows(com.smartcloset.image.ProcessingException.class,()->service.tool(token.get(),new AgentService.ToolRequest("remember_preference",json.valueToTree(Map.of("category","PERSONAL","content","身高170","evidence","身高170")))));
   var p=service.tool(token.get(),new AgentService.ToolRequest("propose_outfits",candidates(List.of(List.of(a,b),List.of(a,c),List.of(a,d)))));
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
 @Test void outfitToolsRejectConflictsBeforePreviewOrCanvasAndReturnMetadataEvidence()throws Exception {
  String owner=login();var session=repo.create(owner);
  String top=clothing(owner,"ACTIVE","衬衫"),bottom=clothing(owner,"ACTIVE","长裤"),dress=clothing(owner,"ACTIVE","连衣裙"),
      outer=clothing(owner,"ACTIVE","夹克"),cardigan=clothing(owner,"ACTIVE","开衫"),unknown=clothing(owner,"ACTIVE");
  var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
  runtime.createContext("/run",exchange->{
   String frame;
   try {
    var input=json.readTree(exchange.getRequestBody().readAllBytes());String token=input.path("callbackToken").asText();
    var metadata=json.valueToTree(service.tool(token,new AgentService.ToolRequest("get_garment",json.valueToTree(Map.of("id",cardigan)))));
    assertEquals("OUTER",metadata.path("wearing").path("role").asText());
    assertEquals("USER_CONFIRMED",metadata.path("quality").path("category").path("status").asText());
    assertEquals("UNKNOWN",metadata.path("quality").path("primaryColor").path("status").asText());
    assertTrue(metadata.path("fields").has("closure"));assertEquals(3,input.path("context").path("garments").size());
    var search=json.valueToTree(service.tool(token,new AgentService.ToolRequest("search_wardrobe",json.readTree("{\"query\":\"衬衫 长裤\",\"page\":9}"))));
    // Multi-condition queries are parsed into category filters; a page past the end is empty, never a fallback to the whole closet.
    assertFalse(search.path("fallbackApplied").asBoolean());assertEquals(search.path("total").asInt(),search.path("matchedTotal").asInt());
    assertEquals(9,search.path("page").asInt());assertEquals("衬衫 长裤",search.path("appliedQuery").asText());assertEquals(0,search.path("items").size());
    assertFalse(search.path("hasMore").asBoolean());assertTrue(search.path("parsed").path("filters").path("category").toString().contains("衬衫"));
    for(String name:List.of("build_outfits","propose_outfits"))for(var ids:List.of(List.of(top,bottom,outer,cardigan),List.of(dress,top),List.of(top,bottom,outer),List.of(dress,cardigan),List.of(top,unknown),List.of(outer)))
     assertThrows(com.smartcloset.image.ProcessingException.class,()->service.tool(token,new AgentService.ToolRequest(name,candidates(List.of(ids)))));
    for(String name:List.of("edit_workspace","propose_edit")) {
     var operations=json.valueToTree(Map.of("summary","添加开衫","operations",List.of(Map.of("op","add","garmentId",cardigan))));
     assertThrows(com.smartcloset.image.ProcessingException.class,()->service.tool(token,new AgentService.ToolRequest(name,operations)));
    }
    var inverted=json.valueToTree(Map.of("summary","错误层级","operations",List.of(Map.of("op","transform","elementId","outer","patch",Map.of("zIndex",-1)))));
    assertThrows(com.smartcloset.image.ProcessingException.class,()->service.tool(token,new AgentService.ToolRequest("propose_edit",inverted)));
    var proposal=service.tool(token,new AgentService.ToolRequest("propose_outfits",candidates(List.of(List.of(dress),List.of(top,bottom)))));
    assertNotNull(proposal);
    // A category change between preview and acceptance must invalidate the old combination.
    db.update("UPDATE garment SET overrides_json=? WHERE id=?",json.writeValueAsString(Map.of("category",List.of("夹克"))),dress);
    String id=json.valueToTree(proposal).path("id").asText();
    assertThrows(com.smartcloset.image.ProcessingException.class,()->service.accept(owner,id,json.readTree("{\"route\":\"/wardrobe\"}"),false));
    var pool=json.valueToTree(service.tool(token,new AgentService.ToolRequest("get_outfit_candidates",json.readTree("{\"requiredGarmentIds\":[]}"))));
    assertFalse(pool.path("candidates").isEmpty());assertFalse(pool.path("garments").isEmpty());
    assertFalse(pool.path("garments").get(0).path("quality").path("category").has("evidence"));
    String candidate=pool.path("candidates").get(0).path("candidateId").asText();
    var picked=json.valueToTree(Map.of("candidates",List.of(Map.of("candidateId",candidate,"name","可靠方案","reason","从预校验候选选择"))));
    assertNotNull(service.tool(token,new AgentService.ToolRequest("propose_outfits",picked)));
    assertThrows(com.smartcloset.image.ProcessingException.class,()->service.tool(token,new AgentService.ToolRequest("propose_outfits",candidates(List.of(List.of(top,bottom))))));
    frame="{\"event\":\"done\",\"data\":{}}\n";
   } catch(Throwable e) {failure.set(e);frame="{\"event\":\"error\",\"data\":{}}\n";}
   byte[] bytes=frame.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
  });
  try {
   var context=Map.of("route","/outfits/new","scene",Map.of("elements",List.of(Map.of("elementId","top","garmentId",top,"zIndex",0),Map.of("elementId","bottom","garmentId",bottom,"zIndex",1),Map.of("elementId","outer","garmentId",outer,"zIndex",2))));
   var response=send("POST","/api/agent/sessions/"+session.id()+"/messages",Map.of("requestId",UUID.randomUUID().toString(),"content","帮我搭配","context",context));
   if(failure.get()!=null)throw new AssertionError("Outfit callback checks failed",failure.get());
   assertTrue(response.body().contains("event: done"),response.body());assertFalse(response.body().contains("event: canvas_operation"));
  }finally{runtime.removeContext("/run");}
 }
 @Test void choiceAndListCardsCarryDisplayUrlsThatTheModelNeverReads()throws Exception {
  String owner=login();var session=repo.create(owner);String first=clothing(owner,"ACTIVE","衬衫"),second=clothing(owner,"ACTIVE","衬衫");
  var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
  runtime.createContext("/run",exchange->{
   String frame;
   try {
    var input=json.readTree(exchange.getRequestBody().readAllBytes());String token=input.path("callbackToken").asText();
    var search=json.valueToTree(service.tool(token,new AgentService.ToolRequest("search_wardrobe",json.readTree("{\"query\":\"衬衫\"}"))));
    var choices=search.path("choiceCard").path("payload").path("candidates");
    assertEquals(2,choices.size(),search.toString());
    for(var c:choices)assertTrue(c.path("displayUrl").asText().matches("/api/workflows/[0-9a-f-]+/files/input\\.png"),c.toString());
    var summary=json.valueToTree(service.tool(token,new AgentService.ToolRequest("closet_summary",json.readTree("{\"category\":\"衬衫\",\"show\":true}"))));
    var listed=summary.path("listCard").path("payload").path("items");
    assertEquals(Set.of(first,second),new HashSet<>(listed.findValuesAsString("id")),summary.toString());
    for(var item:listed)assertTrue(item.path("displayUrl").asText().startsWith("/api/workflows/"),item.toString());
    // The model-facing list stays as it was: pictures ride only on the card, which the runtime swaps for a note.
    for(var item:summary.path("items"))assertFalse(item.has("displayUrl"),item.toString());
    frame="{\"event\":\"done\",\"data\":{}}\n";
   } catch(Throwable e) {failure.set(e);frame="{\"event\":\"error\",\"data\":{}}\n";}
   byte[] bytes=frame.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
  });
  try {
   var response=send("POST","/api/agent/sessions/"+session.id()+"/messages",Map.of("requestId",UUID.randomUUID().toString(),"content","列出我的衬衫","context",Map.of("route","/wardrobe")));
   if(failure.get()!=null)throw new AssertionError("Card callback checks failed",failure.get());
   assertTrue(response.body().contains("event: done"),response.body());
  }finally{runtime.removeContext("/run");}
 }
 @Test void countsUseAuthoritativeActiveRecordsOverlappingSeasonsAndUnknowns()throws Exception {
  String owner=login();String dress=clothing(owner,"ACTIVE","连衣裙");clothing(owner,"ACTIVE","半身裙");clothing(owner,"ACTIVE","衬衫");String ambiguous=clothing(owner,"ACTIVE","裙装");clothing(owner,"DRAFT","衬衫");clothing(owner,"TRASHED","长裤");
  db.update("UPDATE garment SET overrides_json=? WHERE id=?",json.writeValueAsString(Map.of("category",List.of("连衣裙"),"seasons",List.of("春季","夏季"))),dress);
  db.update("UPDATE garment SET overrides_json=? WHERE id=?",json.writeValueAsString(Map.of("category",List.of("裙装"),"seasons",List.of("未知"))),ambiguous);
  var result=summaries.summary(owner,"裙子","");assertFalse(((Map<?,?>)result.get("seasonCounts")).containsKey("未知"));assertEquals(2,result.get("count"));assertEquals(4,result.get("totalActive"));assertEquals(1L,result.get("unknownCategoryCount"));assertEquals(3L,result.get("unknownSeasonCount"));assertEquals(0,summaries.summary(owner,"裤子","").get("count"));assertEquals(1,summaries.summary(owner,"","夏季").get("count"));
  assertEquals(2,garments.listCloset(owner,"ACTIVE","","裙子","","",1,20).total());assertEquals(0,garments.listCloset(owner,"ACTIVE","不存在","","","",1,20).total());
 }
 @Test void sensitiveMemoryRequiresConcreteConfirmationAndUndoUsesRevision()throws Exception {
  String owner=login();var doc=documents.get(owner,"SENSITIVE");var parameters=json.valueToTree(Map.of("documentKind","SENSITIVE","operation","APPEND","newContent","身高170 cm","evidence","记住我身高170"));var prepared=commands.prepare(owner,new AgentCommands.Prepare("MEMORY_PATCH",null,doc.revision(),parameters,"sensitive-1"),"记住我身高170");
  assertEquals("PREPARED",prepared.status());assertTrue(documents.get(owner,"SENSITIVE").content().isBlank());var confirmed=commands.execute(owner,prepared.id());assertTrue(confirmed.contextChanged());assertTrue(documents.get(owner,"SENSITIVE").content().contains("170"));assertTrue(documents.permissions(owner).isEmpty());commands.undo(owner,prepared.id());assertTrue(documents.get(owner,"SENSITIVE").content().isBlank());
 }
 @Test void revokedMemoryBetweenPreparationAndStreamingNeverReachesRuntime()throws Exception {
  String owner=login();var session=repo.create(owner);var called=new java.util.concurrent.atomic.AtomicInteger();
  runtime.createContext("/run",exchange->{called.incrementAndGet();byte[] out="{\"event\":\"done\",\"data\":{}}\n".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,out.length);exchange.getResponseBody().write(out);exchange.close();});
  try {
   var request=json.treeToValue(json.valueToTree(Map.of("requestId",UUID.randomUUID().toString(),"content","你好","context",Map.of("route","/wardrobe"))),AgentService.Request.class);
   var prepared=service.stream(owner,session.id(),request);
   documents.revoke(owner,"OUTFIT");
   var output=new java.io.ByteArrayOutputStream();prepared.writeTo(output);
   assertEquals(0,called.get());assertTrue(output.toString(StandardCharsets.UTF_8).contains("AGENT_RUN_FAILED"));
  }finally{runtime.removeContext("/run");}
 }
 @Test void rawMemoryHasCasPurposeConsentAndForgetBarrier()throws Exception {
  String owner=login();var session=repo.create(owner);long epoch=documents.epoch(owner,session.id());
  var sensitive=documents.get(owner,"SENSITIVE");var saved=documents.save(owner,"SENSITIVE",new AgentMemoryDocuments.Edit(sensitive.revision(),"身高 170，怕冷"));
  assertTrue(documents.epoch(owner,session.id())>epoch);assertFalse(json.writeValueAsString(documents.modelInput(owner,Set.of("OUTFIT"))).contains("170"));
  documents.authorize(owner,"OUTFIT",saved.revision());assertTrue(json.writeValueAsString(documents.modelInput(owner,Set.of("OUTFIT"))).contains("170"));assertFalse(json.writeValueAsString(documents.modelInput(owner,Set.of())).contains("170"));
  assertThrows(com.smartcloset.image.ProcessingException.class,()->documents.save(owner,"SENSITIVE",new AgentMemoryDocuments.Edit(sensitive.revision(),"覆盖")));
  documents.revoke(owner,"OUTFIT");assertFalse(json.writeValueAsString(documents.modelInput(owner,Set.of("OUTFIT"))).contains("170"));
 }
 @Test void commandIsIdempotentAndUndoCannotOverwriteLaterEdits()throws Exception {
  String owner=login();String target=clothing(owner,"ACTIVE","衬衫");var request=new AgentCommands.Prepare("GARMENT_BASIC",UUID.fromString(target),0,json.valueToTree(Map.of("name","白衬衫")),"rename-1");
  var command=commands.prepare(owner,request,"把这件改名为白衬衫");assertEquals("SUCCEEDED",command.status());assertEquals(command.id(),commands.prepare(owner,request,"把这件改名为白衬衫").id());assertEquals(1,garments.get(UUID.fromString(target)).revision());
  garments.editBasic(owner,UUID.fromString(target),new com.smartcloset.garment.GarmentRepository.BasicEdit(1L,"通勤衬衫",null));assertThrows(com.smartcloset.image.ProcessingException.class,()->commands.undo(owner,command.id()));assertEquals("通勤衬衫",garments.get(UUID.fromString(target)).name());
  var favorite=commands.prepare(owner,new AgentCommands.Prepare("GARMENT_BASIC",UUID.fromString(target),2,json.valueToTree(Map.of("favorite",true)),"fav-2"),"收藏这件");commands.undo(owner,favorite.id());assertFalse(garments.get(UUID.fromString(target)).favorite());
 }
 @Test void reviewUsesRealCategoryAndDeduplicatesCanvasInstances()throws Exception {
  String owner=login();String dress=clothing(owner,"ACTIVE","连衣裙"),coat=clothing(owner,"ACTIVE","外套"),cardigan=clothing(owner,"ACTIVE","开衫");
  var scene=json.valueToTree(Map.of("elements",List.of(Map.of("garmentId",dress,"role","BOTTOM"),Map.of("garmentId",dress,"role","TOP"))));
  var review=reviews.review(owner,new CompositionReviews.Request(null,null,scene,null,null,false));assertTrue(review.passed());assertEquals(1,review.items().size());assertEquals("ONE_PIECE",review.items().getFirst().role());assertEquals("NO_SHOES",review.warnings().getFirst().code());
  var invalid=json.valueToTree(Map.of("elements",List.of(Map.of("garmentId",dress),Map.of("garmentId",coat,"role","TOP"),Map.of("garmentId",cardigan,"role","ACCESSORY"))));assertFalse(reviews.review(owner,new CompositionReviews.Request(null,null,invalid,null,null,false)).passed());
 }
 @Test void recommendToolShowsAnExplainedCardChecksReasonsAndAcceptsLikeOutfits()throws Exception {
  String owner=login();var session=repo.create(owner);
  String shirt=clothing(owner,"ACTIVE","衬衫"),trousers=clothing(owner,"ACTIVE","长裤"),jeans=clothing(owner,"ACTIVE","牛仔裤"),tee=clothing(owner,"ACTIVE","T恤"),shoes=clothing(owner,"ACTIVE","运动鞋");
  db.update("UPDATE garment SET overrides_json=? WHERE id=?",json.writeValueAsString(Map.of("category",List.of("衬衫"),"primaryColor",List.of("白色"))),shirt);
  var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();var proposalId=new java.util.concurrent.atomic.AtomicReference<String>();
  runtime.createContext("/run",exchange->{
   String frame;
   try {
    var input=json.readTree(exchange.getRequestBody().readAllBytes());String token=input.path("callbackToken").asText();
    assertTrue(input.path("allowedTools").toString().contains("recommend_outfits"));
    var result=json.valueToTree(service.tool(token,new AgentService.ToolRequest("recommend_outfits",json.readTree("{\"intent\":{\"occasion\":\"COMMUTE\",\"temperatureC\":18},\"count\":2}"))));
    assertEquals(2,result.path("shown").size());assertFalse(result.path("shown").get(0).path("highlights").isEmpty());
    assertFalse(result.toString().contains("\"fields\""),"model must not receive full garment metadata");
    var card=result.path("presented").path("proposal");assertEquals("RECOMMENDATION",card.path("kind").asText());proposalId.set(card.path("id").asText());
    assertEquals(11,card.path("payload").path("candidates").get(0).path("recommendation").path("dimensions").size());
    String runId=result.path("runId").asText(),key=result.path("shown").get(0).path("outfitKey").asText();
    var invented=json.valueToTree(Map.of("runId",runId,"choices",List.of(Map.of("outfitKey",key,"reason","粉色大衣很温柔"))));
    var error=assertThrows(com.smartcloset.image.ProcessingException.class,()->service.tool(token,new AgentService.ToolRequest("present_outfits",invented)));
    assertTrue(error.getMessage().contains("粉色"),error.getMessage());
    assertThrows(com.smartcloset.image.ProcessingException.class,()->service.tool(token,new AgentService.ToolRequest("present_outfits",json.valueToTree(Map.of("runId",runId,"choices",List.of(Map.of("outfitKey",UUID.randomUUID().toString())))))));
    String first=result.path("shown").get(0).path("items").get(0).asText().replaceAll("（.*","");
    assertNotNull(service.tool(token,new AgentService.ToolRequest("present_outfits",json.valueToTree(Map.of("runId",runId,"choices",List.of(Map.of("outfitKey",key,"reason",first+"更利落")))))));
    frame=json.writeValueAsString(Map.of("event","proposal","data",card))+"\n{\"event\":\"done\",\"data\":{}}\n";
   } catch(Throwable e) {failure.set(e);frame="{\"event\":\"error\",\"data\":{}}\n";}
   byte[] bytes=frame.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
  });
  try {
   var response=send("POST","/api/agent/sessions/"+session.id()+"/messages",Map.of("requestId",UUID.randomUUID().toString(),"content","帮我搭配两套通勤穿的，18度","context",Map.of("route","/wardrobe")));
   if(failure.get()!=null)throw new AssertionError(failure.get());
   assertTrue(response.body().contains("event: proposal"),response.body());
   assertTrue(db.queryForObject("SELECT COUNT(*) FROM recommendation_log WHERE owner_username=? AND source='AGENT'",Integer.class,owner)>=2);
   for(int n=0;n<40&&db.queryForObject("SELECT COUNT(*) FROM model_usage WHERE owner_username=?",Integer.class,owner)==0;n++)Thread.sleep(25);
   assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM model_usage WHERE owner_username=? AND feature='AGENT'",Integer.class,owner));
   assertNotNull(service.accept(owner,proposalId.get(),json.readTree("{\"route\":\"/wardrobe\"}"),false));
  } finally {runtime.removeContext("/run");}
 }
 @Test void shortRecommendationFollowUpUsesLatestAssistantAndCompactHistory()throws Exception {
  for(boolean recommendation:new boolean[]{true,false}){
   String owner=login();var session=repo.create(owner);String request=UUID.randomUUID().toString();
   var payload=json.valueToTree(Map.of("runId",UUID.randomUUID().toString(),"intent","通勤 · 18°C","candidates",List.of(Map.of("name","通勤方案","garmentIds",List.of(clothing(owner,"ACTIVE","衬衫")),"recommendation",Map.of("dimensions",List.of("不应进入模型历史"),"evidence","完整评分")))));
   var card=repo.propose(owner,session.id(),"RECOMMENDATION",payload,json.createObjectNode());
   repo.begin(owner,session.id(),request,"帮我搭配通勤穿的");repo.finish(owner,session.id(),request,"推荐好了","COMPLETE",List.of(Map.of("event","proposal","data",card)));
   if(!recommendation){
    Thread.sleep(5); // messages are ordered by creation time; keep the intervening one strictly later
    String intervening=UUID.randomUUID().toString();repo.begin(owner,session.id(),intervening,"我有多少衣服？");repo.finish(owner,session.id(),intervening,"统计好了","COMPLETE",List.of());
    db.update("UPDATE agent_daily_quota SET used=1 WHERE owner_username=?",owner);
   }
   var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
   runtime.createContext("/run",exchange->{String frame;try{
    var input=json.readTree(exchange.getRequestBody().readAllBytes());String token=input.path("callbackToken").asText();
    assertTrue(strings(input.path("allowedTools")).contains("recommend_outfits"),"cards are always available");
    assertEquals(recommendation,strings(input.path("allowedTools")).contains("build_outfits"),"only a recognised outfit request may build boards");
    if(recommendation){
     String history=input.path("messages").toString();assertTrue(history.contains("garmentIds"));assertTrue(history.contains("outfits"));assertFalse(history.contains("dimensions"));assertFalse(history.contains("evidence"));assertFalse(history.contains("candidates"));
     assertNotNull(service.tool(token,new AgentService.ToolRequest("recommend_outfits",json.createObjectNode())));
    }else{
     var shown=json.valueToTree(service.tool(token,new AgentService.ToolRequest("recommend_outfits",json.readTree("{\"open\":\"WORKSPACE\"}"))));
     assertNotEquals("WORKSPACE",shown.path("presented").path("mode").asText(),"without an outfit request the board is not opened");
    }
    frame="{\"event\":\"done\",\"data\":{}}\n";
   }catch(Throwable e){failure.set(e);frame="{\"event\":\"error\",\"data\":{}}\n";}byte[] out=frame.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,out.length);exchange.getResponseBody().write(out);exchange.close();});
   try{var response=send("POST","/api/agent/sessions/"+session.id()+"/messages",Map.of("requestId",UUID.randomUUID().toString(),"content","不要那双","context",Map.of("route","/wardrobe")));if(failure.get()!=null)throw new AssertionError(failure.get());assertTrue(response.body().contains("event: done"),response.body());}
   finally{runtime.removeContext("/run");}
  }
 }
 private List<String> strings(JsonNode node){var out=new ArrayList<String>();for(var item:node)out.add(item.asText());return out;}
 @Test void recommendationFollowUpInheritsSessionIntentExcludesShownAndFallsBackSafely()throws Exception {
  for(boolean enough:new boolean[]{true,false}){
   String owner=login();var session=repo.create(owner);
   for(int i=0;i<(enough?2:1);i++){clothing(owner,"ACTIVE","衬衫");clothing(owner,"ACTIVE","长裤");clothing(owner,"ACTIVE","运动鞋");}
   String excluded=clothing(owner,"ACTIVE","凉鞋");
   var initial=new com.smartcloset.recommend.Intent(com.smartcloset.recommend.Intent.Occasion.COMMUTE,18d,"",null,List.of(),List.of(),List.of("红色"),"利落一点",List.of(),List.of(excluded),1,Map.of());
   var previous=recommendations.recommend(owner,initial,"AGENT");assertEquals(1,previous.outfits().size());
   var shownIds=previous.outfits().getFirst().garments().stream().map(com.smartcloset.recommend.RecommendationService.GarmentView::id).toList();
   var payload=json.valueToTree(Map.of("runId",previous.runId(),"intent",previous.intent().get("description"),"candidates",List.of(Map.of("name",previous.outfits().getFirst().name(),"garmentIds",shownIds,"recommendation",previous.outfits().getFirst()))));
   var card=repo.propose(owner,session.id(),"RECOMMENDATION",payload,json.createObjectNode());
   String request=UUID.randomUUID().toString();repo.begin(owner,session.id(),request,"18度帮我搭一套利落的通勤装，避开红色");repo.finish(owner,session.id(),request,"推荐好了","COMPLETE",List.of(Map.of("event","proposal","data",card)));
   // 另一会话更新的推荐不得覆盖本会话的条件。
   var otherSession=repo.create(owner);var other=recommendations.recommend(owner,new com.smartcloset.recommend.Intent(com.smartcloset.recommend.Intent.Occasion.HOME,30d,"",null,List.of(),List.of(),List.of(),"",List.of(),List.of(),1,Map.of()),"AGENT");
   repo.propose(owner,otherSession.id(),"RECOMMENDATION",json.valueToTree(Map.of("runId",other.runId())),json.createObjectNode());
   var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
   runtime.createContext("/run",exchange->{String frame;try{
    var input=json.readTree(exchange.getRequestBody().readAllBytes());String token=input.path("callbackToken").asText();assertTrue(strings(input.path("allowedTools")).contains("recommend_outfits"));
    var args=json.valueToTree(Map.of("basedOnPrevious",true,"intent",Map.of("temperatureC",19,"avoidColors",List.of())));
    var result=json.valueToTree(service.tool(token,new AgentService.ToolRequest("recommend_outfits",args)));assertEquals(1,result.path("shown").size(),result.toString());
    String runId=result.path("runId").asText();var intent=json.readTree(db.queryForObject("SELECT intent_json FROM recommendation_log WHERE owner_username=? AND run_id=? LIMIT 1",String.class,owner,runId));
    assertEquals(1,intent.path("count").asInt());assertEquals("COMMUTE",intent.path("occasion").asText());assertEquals(19d,intent.path("temperatureC").asDouble());assertEquals("利落一点",intent.path("freeText").asText());assertTrue(intent.path("avoidColors").isEmpty());
    assertTrue(strings(intent.path("excludedGarmentIds")).contains(excluded));
    if(enough){assertTrue(strings(intent.path("excludedGarmentIds")).containsAll(shownIds));var newIds=strings(result.path("presented").path("proposal").path("payload").path("candidates").get(0).path("garmentIds"));assertTrue(Collections.disjoint(shownIds,newIds));}
    else {assertEquals(List.of(excluded),strings(intent.path("excludedGarmentIds")));assertTrue(result.path("limits").toString().contains("没有更多不同的组合，部分衣物与上一轮重复"));}
    var fresh=json.valueToTree(service.tool(token,new AgentService.ToolRequest("recommend_outfits",json.valueToTree(Map.of("basedOnPrevious",false,"count",1,"intent",Map.of("occasion","DAILY"))))));
    var freshIntent=json.readTree(db.queryForObject("SELECT intent_json FROM recommendation_log WHERE owner_username=? AND run_id=? LIMIT 1",String.class,owner,fresh.path("runId").asText()));
    assertEquals("DAILY",freshIntent.path("occasion").asText());assertTrue(freshIntent.path("temperatureC").isNull());assertTrue(freshIntent.path("excludedGarmentIds").isEmpty());
    frame="{\"event\":\"done\",\"data\":{}}\n";
   }catch(Throwable e){failure.set(e);frame="{\"event\":\"error\",\"data\":{}}\n";}byte[] out=frame.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,out.length);exchange.getResponseBody().write(out);exchange.close();});
   try{var response=send("POST","/api/agent/sessions/"+session.id()+"/messages",Map.of("requestId",UUID.randomUUID().toString(),"content","重新搭配","context",Map.of("route","/wardrobe")));if(failure.get()!=null)throw new AssertionError(failure.get());assertTrue(response.body().contains("event: done"),response.body());}
   finally{runtime.removeContext("/run");}
  }
 }
 @Test void countTurnRejectsEvenMaliciousModelWriteTool()throws Exception {
  String owner=login();String item=clothing(owner,"ACTIVE","衬衫");var session=repo.create(owner);var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
  runtime.createContext("/run",exchange->{String frame;try{var input=json.readTree(exchange.getRequestBody().readAllBytes());String token=input.path("callbackToken").asText();assertFalse(input.path("allowedTools").toString().contains("build_outfits"));assertEquals(1,json.valueToTree(service.tool(token,new AgentService.ToolRequest("closet_summary",json.createObjectNode()))).path("count").asInt());for(String name:List.of("build_outfits","remember_preference","navigate","prepare_command"))assertThrows(com.smartcloset.image.ProcessingException.class,()->service.tool(token,new AgentService.ToolRequest(name,json.createObjectNode())));frame="{\"event\":\"done\",\"data\":{}}\n";}catch(Throwable e){failure.set(e);frame="{\"event\":\"error\",\"data\":{}}\n";}byte[] out=frame.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,out.length);exchange.getResponseBody().write(out);exchange.close();});
  try{var response=send("POST","/api/agent/sessions/"+session.id()+"/messages",Map.of("requestId",UUID.randomUUID().toString(),"content","我有多少件衣服？","context",Map.of("route","/wardrobe")));if(failure.get()!=null)throw new AssertionError(failure.get());assertTrue(response.body().contains("event: done"));assertEquals(0,garments.get(UUID.fromString(item)).revision());}finally{runtime.removeContext("/run");}
 }
 @Test void batchConfirmationChecksAllVersionsAndDoesNotPartiallyWrite()throws Exception {
  String owner=login();String a=clothing(owner,"ACTIVE","衬衫"),b=clothing(owner,"ACTIVE","长裤");var input=json.valueToTree(Map.of("operation","BASIC","favorite",true,"targets",List.of(Map.of("id",a,"revision",0),Map.of("id",b,"revision",0))));
  var prepared=platformCommands.prepare(owner,"GARMENT_BATCH","batch-cas",input);assertEquals("PREPARED",prepared.status());assertFalse(garments.get(UUID.fromString(a)).favorite());
  garments.editBasic(owner,UUID.fromString(b),new com.smartcloset.garment.GarmentRepository.BasicEdit(0L,"用户新名称",null));assertThrows(com.smartcloset.image.ProcessingException.class,()->commands.execute(owner,prepared.id()));assertFalse(garments.get(UUID.fromString(a)).favorite());
  var fresh=json.valueToTree(Map.of("operation","BASIC","favorite",true,"targets",List.of(Map.of("id",a,"revision",0),Map.of("id",b,"revision",1))));var command=platformCommands.prepare(owner,"GARMENT_BATCH","batch-fresh",fresh);assertEquals("SUCCEEDED",commands.execute(owner,command.id()).status());commands.execute(owner,command.id());assertEquals(1,garments.get(UUID.fromString(a)).revision());assertTrue(garments.get(UUID.fromString(b)).favorite());assertFalse(commands.get(owner,command.id()).canUndo());
 }
 @Test void legacyMemoryCannotResurrectForgottenContentOrOverrideRawEdits()throws Exception {
  String owner=login();var memory=repo.memory(owner,null,"我喜欢绿色","COLOR");var raw=documents.get(owner,"GENERAL");documents.save(owner,"GENERAL",new AgentMemoryDocuments.Edit(raw.revision(),""));assertThrows(com.smartcloset.image.ProcessingException.class,()->repo.memory(owner,memory.id(),"我喜欢蓝色","COLOR"));assertEquals("我喜欢绿色",repo.memories(owner).getFirst().content());assertTrue(documents.get(owner,"GENERAL").content().isBlank());
 }
 @Test void proposalAcceptanceRejectsChangedGarmentFacts()throws Exception {
  String owner=login();String id=clothing(owner,"ACTIVE","连衣裙");var session=repo.create(owner);var p=repo.propose(owner,session.id(),"OUTFITS",candidates(List.of(List.of(id))),json.valueToTree(Map.of("route","/wardrobe")));garments.editBasic(owner,UUID.fromString(id),new com.smartcloset.garment.GarmentRepository.BasicEdit(0L,"新名称",null));assertThrows(com.smartcloset.image.ProcessingException.class,()->repo.consume(owner,p.id()));assertFalse(repo.proposal(owner,p.id()).consumed());
 }
 @Test void sensitiveFactsCannotBeAutoSavedToGeneralMemory()throws Exception {
  String owner=login();var doc=documents.get(owner,"GENERAL");var input=json.valueToTree(Map.of("documentKind","GENERAL","operation","APPEND","newContent","身高170cm","evidence","记住我身高170"));assertThrows(com.smartcloset.image.ProcessingException.class,()->commands.prepare(owner,new AgentCommands.Prepare("MEMORY_PATCH",null,doc.revision(),input,"bad-classification"),"记住我身高170"));assertTrue(documents.get(owner,"GENERAL").content().isBlank());
 }
 @Test void privateMediaHttpIsNoStoreAndEvenAnAdministratorCannotReadAnotherOwner()throws Exception {
  String owner=login();var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(32,48,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);var image=privateMedia.put(owner,"PHOTO",bytes.toByteArray());var own=send("GET",image.url(),null);assertEquals(200,own.statusCode());assertTrue(own.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
  String admin="admin-"+UUID.randomUUID().toString().substring(0,8);accounts.create(admin,"agent-test-password","ADMIN");db.update("UPDATE closet_user SET must_change_password=0 WHERE username=?",admin);assertEquals(200,send("POST","/api/auth/login",Map.of("username",admin,"password","agent-test-password")).statusCode());assertEquals(404,send("GET",image.url(),null).statusCode());
 }
 @Test void helpIsSharedAndUnknownRetrievalStaysUnknown(){assertEquals(5,help.articles().size());assertFalse(help.search("邀请码注册").isEmpty());assertFalse(help.search("组合台保存草稿").isEmpty());assertFalse(help.search("为什么没推荐它").isEmpty());assertFalse(help.search("语义搜索严格度").isEmpty());assertTrue(help.search("宇宙量子飞船驾驶").isEmpty());}
 @Test void traceSnapshotsPersistArePaginatedRedactedAndAccountScoped()throws Exception {
  String owner=login();var session=repo.create(owner);String request=UUID.randomUUID().toString();
  repo.begin(owner,session.id(),request,"测试追踪");traces.start(session.id(),request,List.of("credential-fixture","身高170"));
  traces.append(request,"input","CAPTURED","输入",Map.of("system","提示 credential-fixture 身高170", "apiKey","credential-fixture","image","data:image/jpeg;base64,secret-image","memories",List.of(Map.of("category","PERSONAL","content","身高170")),"category",List.of("衬衫")));
  for(int n=0;n<105;n++)traces.append(request,"tool","COMPLETE","工具"+n,Map.of("n",n));
  traces.finish(request,"COMPLETE");repo.finish(owner,session.id(),request,"完成","COMPLETE",List.of());
  var page=json.readTree(send("GET","/api/agent/sessions/"+session.id()+"/runs/"+request+"/trace?limit=100",null).body());
  assertTrue(page.path("available").asBoolean());assertTrue(page.path("hasMore").asBoolean());assertEquals(100,page.path("items").size());
  var next=json.readTree(send("GET","/api/agent/sessions/"+session.id()+"/runs/"+request+"/trace?afterSeq=100",null).body());assertEquals(6,next.path("items").size());
  String detail=send("GET","/api/agent/sessions/"+session.id()+"/runs/"+request+"/trace/1",null).body();
  assertFalse(detail.contains("credential-fixture"));assertFalse(detail.contains("身高170"));assertFalse(detail.contains("secret-image"));assertTrue(detail.contains("衬衫"));
  assertEquals(request,repo.messages(owner,session.id()).getLast().requestId());
  assertThrows(com.smartcloset.image.ProcessingException.class,()->traces.list("unknown-owner",session.id(),request,0,100));
  assertFalse(json.readTree(send("GET","/api/agent/sessions/"+session.id()+"/runs/"+UUID.randomUUID()+"/trace",null).body()).path("available").asBoolean());
  repo.delete(owner,session.id());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_trace_event WHERE request_id=?",Integer.class,request));
 }
 @Test void boardReportsAreLinkedDeduplicatedAndSurviveLaterRuns()throws Exception {
  String owner=login();var session=repo.create(owner);String request=UUID.randomUUID().toString(),later=UUID.randomUUID().toString(),board=UUID.randomUUID().toString();
  traces.start(session.id(),request,List.of());traces.start(session.id(),later,List.of());traces.linkBoard(session.id(),board,request);traces.linkBoard(session.id(),board,later);
  var event=json.valueToTree(Map.of("eventId",UUID.randomUUID().toString(),"boardId",board,"workspaceId","agent:"+session.id(),"source","manual-drag","after",Map.of("version",2,"elements",List.of(Map.of("elementId","e","role","OUTER","x",570,"assetUrl","must-not-store")))));
  assertEquals(200,send("POST","/api/agent/sessions/"+session.id()+"/board-events",event).statusCode());
  assertTrue(json.readTree(send("POST","/api/agent/sessions/"+session.id()+"/board-events",event).body()).path("duplicate").asBoolean());
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM agent_trace_event WHERE request_id=?",Integer.class,request));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM agent_trace_event WHERE request_id=?",Integer.class,later));
  var detail=json.valueToTree(traces.detail(owner,session.id(),request,1));assertEquals("CLIENT_REPORTED",detail.path("evidenceSource").asText());assertFalse(detail.toString().contains("must-not-store"));
  ((tools.jackson.databind.node.ObjectNode)event).put("boardId",UUID.randomUUID().toString());assertEquals(404,send("POST","/api/agent/sessions/"+session.id()+"/board-events",event).statusCode());
  repo.delete(owner,session.id());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_trace_client_event WHERE session_id=?",Integer.class,session.id()));
 }

 @Test void interruptedRunKeepsAnAccessibleMessageAndDoesNotRetryOrConsumeQuota()throws Exception {
  String owner=login();var session=repo.create(owner);String request=UUID.randomUUID().toString();repo.begin(owner,session.id(),request,"搭一套");traces.start(session.id(),request,List.of());
  traces.append(request,"tool","COMPLETE","已查询衣柜",Map.of("items",List.of()));
  assertEquals("RUNNING",repo.messages(owner,session.id()).getLast().status());
  traces.recoverInterruptedRuns();assertEquals("FAILED",repo.messages(owner,session.id()).getLast().status());assertEquals(request,repo.messages(owner,session.id()).getLast().requestId());
  assertEquals("INTERRUPTED",json.valueToTree(traces.list(owner,session.id(),request,0,100)).path("status").asText());assertEquals(1,repo.quota(owner).get("used"));
  assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM agent_trace_event WHERE request_id=?",Integer.class,request));repo.delete(owner,session.id());
 }

 @Test void profilePatchPreservesOtherPreferencesAndUnknownRenderCanBeCancelled()throws Exception {
  String owner=login();var profiles=new com.smartcloset.person.WearerProfiles(db,json);profiles.update(owner,new com.smartcloset.person.WearerProfiles.Profile(0,"FEMALE",null,null,null,Map.of("waist",70d),Map.of("style","简约","thermal","怕冷"),null));
  var command=platformCommands.prepare(owner,"PROFILE_EDIT","profile-one-field",json.valueToTree(Map.of("revision",1,"fields",Map.of("preferences",Map.of("style","休闲")))));commands.execute(owner,command.id());assertEquals(Map.of("style","休闲","thermal","怕冷"),profiles.get(owner).preferences());assertEquals(Map.of("waist",70d),profiles.get(owner).measurements());
  String id=UUID.randomUUID().toString();long now=System.currentTimeMillis();db.update("INSERT INTO image_render_job(id,owner_username,purpose,input_json,status,idempotency_key,created_at_ms,updated_at_ms) VALUES(?,?,'OOTD','{}','UNKNOWN',?,?,?)",id,owner,id,now,now);
  var cancel=platformCommands.prepare(owner,"TASK_CANCEL","cancel-unknown",json.valueToTree(Map.of("taskId",id)));commands.execute(owner,cancel.id());assertEquals("CANCELLED",db.queryForObject("SELECT status FROM image_render_job WHERE id=?",String.class,id));assertEquals(1L,db.queryForObject("SELECT deletion_epoch FROM image_render_job WHERE id=?",Long.class,id));
 }

}
