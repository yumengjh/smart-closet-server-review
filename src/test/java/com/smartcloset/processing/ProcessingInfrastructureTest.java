package com.smartcloset.processing;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.annotation.DirtiesContext;
import com.smartcloset.security.ClosetAccountService;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.config.import=optional:file:./target/no-secrets.yml","closet.model-path=target/no-model.onnx"})
@Import(ProcessingInfrastructureTest.Fixture.class) @DirtiesContext
class ProcessingInfrastructureTest {
 static Path root;static{try{root=Files.createTempDirectory("processing-test-");}catch(Exception e){throw new RuntimeException(e);}}
 @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("closet.storage-dir",()->root.toString());}
 static AtomicBoolean candidateFailure=new AtomicBoolean();static AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger();static List<String> owners=new CopyOnWriteArrayList<>();
 @TestConfiguration static class Fixture {@Bean ProcessingHandler load(){return new ProcessingHandler(){public String kind(){return "LOAD";}public List<Candidate> candidates(){if(candidateFailure.get())throw new IllegalStateException("Injected candidate scan failure");return List.of();}public void execute(ProcessingStore.Task t)throws Exception{int n=active.incrementAndGet();peak.accumulateAndGet(n,Math::max);try{owners.add(t.owner());Thread.sleep(15);}finally{active.decrementAndGet();}}};}}
 @Autowired ProcessingStore store;@Autowired ProcessingResources resources;@Autowired ClosetAccountService accounts;
 @BeforeEach void prepare() {store.setConfig(new ProcessingStore.Config(1,1,1,2,1,null,Set.of("CPU","RECOGNITION","GENERATION")),revision(),"test");store.db().update("UPDATE resource_permit SET deadline_ms=0");store.db().update("UPDATE execution_task SET status='CANCELLED' WHERE status IN ('QUEUED','WAITING_QUOTA')");}
 long revision(){return ((Number)store.configView().get("revision")).longValue();}
 String user(){String name="infra-"+UUID.randomUUID();accounts.create(name,"test-password","USER");return name;}
 ProcessingStore.Task task(String owner){String id=UUID.randomUUID().toString();store.enqueue("LOAD",id,0,owner,"CPU",null,100);return store.db().queryForObject("SELECT id FROM execution_task WHERE business_id=?",(r,n)->store.get(r.getString(1)),id);}
 @Test void databaseRetains1000ItemsAndCpuCapacityAndThreeOwnerFairnessHold()throws Exception {
  active.set(0);peak.set(0);owners.clear();String a=user(),b=user(),c=user();for(int i=0;i<1000;i++)task(i<998?a:i==998?b:c);
  store.setConfig(new ProcessingStore.Config(1,1,1,2,1,null,Set.of("RECOGNITION","GENERATION")),revision(),"test");
  for(int i=0;i<160&&owners.size()<6;i++)Thread.sleep(25);
  assertTrue(owners.size()>=3,owners.toString());assertTrue(owners.subList(0,3).containsAll(List.of(a,b,c)),owners.toString());assertEquals(1,peak.get());assertTrue(store.db().queryForObject("SELECT COUNT(*) FROM execution_task WHERE kind='LOAD' AND status='QUEUED'",Integer.class)>980);
 }
 @Test void candidateScanFailureDoesNotBlockAlreadyQueuedDurableTasks()throws Exception {
  var queued=task(user());candidateFailure.set(true);
  try{
   store.setConfig(new ProcessingStore.Config(1,1,1,2,1,null,Set.of("RECOGNITION","GENERATION")),revision(),"test");
   for(int i=0;i<100&&!store.get(queued.id()).status().equals("SUCCEEDED");i++)Thread.sleep(25);
   assertEquals("SUCCEEDED",store.get(queued.id()).status());
  }finally{candidateFailure.set(false);}
 }
 @Test void sharedAccountReservesAssistantAndUnknownHoldsUntilDeadline() {
  long deadline=System.currentTimeMillis()+10000;String bg=resources.acquire("fixture-account","BACKGROUND",UUID.randomUUID().toString(),deadline);assertNotNull(bg);
  assertNull(resources.acquire("fixture-account","BACKGROUND",UUID.randomUUID().toString(),deadline));String assistant=resources.acquire("fixture-account","ASSISTANT",UUID.randomUUID().toString(),deadline);assertNotNull(assistant);assertNull(resources.acquire("fixture-account","ASSISTANT",UUID.randomUUID().toString(),deadline));
  resources.dispatch(bg);resources.finish(bg,true);resources.finish(assistant,false);assertNull(resources.acquire("fixture-account","BACKGROUND",UUID.randomUUID().toString(),deadline));store.db().update("UPDATE resource_permit SET deadline_ms=0 WHERE token=?",bg);assertNotNull(resources.acquire("fixture-account","BACKGROUND",UUID.randomUUID().toString(),deadline));
 }
 @Test void unsentRefundSentUnknownAndRetryChargeAreAtomic() {
  String owner=user();var t=task(owner);String first=store.attempt(t,1);assertNotNull(first);store.finishAttempt(first,"CANCELLED",null);assertEquals(0,quota(owner));String sent=store.attempt(t,1);store.dispatched(sent);store.finishAttempt(sent,"UNKNOWN","network uncertainty");assertEquals(1,quota(owner));String next=store.attempt(t,1);store.dispatched(next);store.finishAttempt(next,"SUCCEEDED",null);assertEquals(1,quota(owner));
 }
 @Test void interruptedUnsentIsQueuedAndSentIsUnknownWithoutReplay() {
  String owner=user();var safe=task(owner);store.claim(safe);String s=store.attempt(safe,1);var uncertain=task(owner);store.claim(uncertain);String u=store.attempt(uncertain,1);store.dispatched(u);store.recover();assertEquals("QUEUED",store.get(safe.id()).status());assertEquals("UNKNOWN",store.get(uncertain.id()).status());assertEquals(1,quota(owner));assertNotNull(s);
 }
 @Test void rpmCountsGrantedReservationsAndLimitReductionDoesNotKillInflight() {
  store.setConfig(new ProcessingStore.Config(1,1,1,2,1,1,Set.of("CPU")),revision(),"test");String first=resources.acquire("rpm-account","ASSISTANT",UUID.randomUUID().toString(),System.currentTimeMillis()+10000);assertNotNull(first);assertNull(resources.acquire("rpm-account","ASSISTANT",UUID.randomUUID().toString(),System.currentTimeMillis()+10000));store.setConfig(new ProcessingStore.Config(1,1,1,1,0,1,Set.of("CPU")),revision(),"test");assertEquals("GRANTED",store.db().queryForObject("SELECT status FROM resource_permit WHERE token=?",String.class,first));
 }
 @Test void confirmed429BackoffSurvivesResourceFacadeRestart(){
  String account="rate-"+UUID.randomUUID();String token=resources.acquire(account,"ASSISTANT",UUID.randomUUID().toString(),System.currentTimeMillis()+10000);
  resources.dispatch(token);resources.backoff(token,5000);resources.finish(token,false);
  var restarted=new ProcessingResources(store);assertNull(restarted.acquire(account,"BACKGROUND",UUID.randomUUID().toString(),System.currentTimeMillis()+10000));
  store.db().update("UPDATE resource_permit SET deadline_ms=0 WHERE token=?",token);assertNotNull(restarted.acquire(account,"BACKGROUND",UUID.randomUUID().toString(),System.currentTimeMillis()+10000));
 }
 @Test void waitingQuotaTaskResumesImmediatelyOnlyFromWaitingState(){
  var t=task(user());store.claim(t);store.defer(t,"WAITING_QUOTA",store.tomorrow(),"等待次日识别额度");
  assertTrue(store.resume(t.id()));var resumed=store.get(t.id());assertEquals("QUEUED",resumed.status());assertTrue(resumed.nextRunAt()<=System.currentTimeMillis());assertFalse(store.resume(t.id()));
 }
 int quota(String owner){return store.db().queryForObject("SELECT COALESCE(SUM(used),0) FROM ai_daily_quota WHERE owner_username=?",Integer.class,owner);}
}
