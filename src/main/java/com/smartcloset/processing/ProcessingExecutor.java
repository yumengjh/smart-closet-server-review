package com.smartcloset.processing;

import java.util.*;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import com.smartcloset.image.ProcessingException;
import org.slf4j.LoggerFactory;

@Service
public class ProcessingExecutor {
    private final ProcessingStore store;private final ProcessingResources resources;private final Map<String,ProcessingHandler> handlers=new LinkedHashMap<>();
    private final ScheduledExecutorService ticker=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("processing-dispatch").factory());
    private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String,Integer> running=new ConcurrentHashMap<>();private final Map<String,String> lastOwner=new ConcurrentHashMap<>();
    private final Map<String,Long> ownerTurns=new HashMap<>();private long turn;
    private volatile boolean closing;
    public ProcessingExecutor(ProcessingStore store,ProcessingResources resources,List<ProcessingHandler> handlers){this.store=store;this.resources=resources;for(var h:handlers)this.handlers.put(h.kind(),h);}
    @EventListener(ApplicationReadyEvent.class) public void start(){store.recover();ticker.scheduleWithFixedDelay(this::tick,50,250,TimeUnit.MILLISECONDS);}
    private synchronized void tick(){if(closing)return;try{
        for(var h:handlers.values()){
            try{for(var c:h.candidates())store.enqueue(c.kind(),c.businessId(),c.stage(),c.owner(),c.pool(),c.account(),c.priority());}
            catch(Exception e){LoggerFactory.getLogger(getClass()).error("Processing candidate scan failed for {}; other durable tasks continue",h.kind(),e);}
        }
        var cfg=store.config();var due=store.due();var blockedAccounts=new HashSet<String>();
        for(String pool:List.of("CPU","RECOGNITION","GENERATION")){
            int cap=switch(pool){case "CPU"->cfg.cpu();case "RECOGNITION"->cfg.recognition();default->cfg.generation();};
            if(cfg.paused().contains(pool)){for(var queued:due)if(pool.equals(queued.pool())&&!"资源池已暂停".equals(queued.waitReason()))store.defer(queued,"QUEUED",System.currentTimeMillis()+500,"资源池已暂停");continue;}if(running.getOrDefault(pool,0)>=cap){for(var queued:due)if(pool.equals(queued.pool())&&!"等待本阶段处理容量".equals(queued.waitReason()))store.defer(queued,"QUEUED",System.currentTimeMillis()+500,"等待本阶段处理容量");continue;}
            var candidates=due.stream().filter(t->pool.equals(t.pool())).sorted(Comparator.comparingLong((ProcessingStore.Task t)->ownerTurns.getOrDefault(pool+":"+t.owner(),0L)).thenComparing(Comparator.comparingInt(ProcessingStore.Task::priority).reversed()).thenComparingLong(ProcessingStore.Task::createdAt)).toList();
            for(var t:candidates){if(running.getOrDefault(pool,0)>=cap)break;var h=handlers.get(t.kind());if(h==null)continue;
                String permit=t.account()==null||blockedAccounts.contains(t.account())?null:resources.acquire(t.account(),"BACKGROUND","task:"+t.id()+":"+UUID.randomUUID(),System.currentTimeMillis()+610000);
                if(t.account()!=null&&permit==null){blockedAccounts.add(t.account());store.defer(t,"QUEUED",System.currentTimeMillis()+1000,"等待共享账户容量或RPM");continue;}
                String token=store.claim(t);if(token==null){resources.finish(permit,false);continue;}
                int units=h.quotaUnits(t);String attempt=store.attempt(t,units);
                if(attempt==null){resources.finish(permit,false);h.quotaUnavailable(t);if(pool.equals("GENERATION")){store.outcome(t,token,"SKIPPED","额度不足，保留基础图，可主动重做");}else store.defer(t,"WAITING_QUOTA",store.tomorrow(),"等待次日识别额度");continue;}
                running.merge(pool,1,Integer::sum);lastOwner.put(pool,Objects.toString(t.owner(),""));ownerTurns.put(pool+":"+t.owner(),++turn);workers.submit(()->run(h,t,token,permit,attempt));break;
            }
        }
    }catch(Exception e){LoggerFactory.getLogger(getClass()).error("Unified dispatcher retained durable state",e);}}
    private void run(ProcessingHandler h,ProcessingStore.Task t,String token,String permit,String attempt){String outcome="SUCCEEDED",error=null;boolean unknown=false;
        try{
            if(t.owner()!=null){var roles=store.db().query("SELECT role FROM closet_user WHERE username=? AND enabled=1",(r,n)->new SimpleGrantedAuthority("ROLE_"+r.getString(1)),t.owner());if(roles.isEmpty())throw new IllegalStateException("账号已停用");SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(t.owner(),null,roles));}
            resources.bind(permit,t.account(),attempt);
            if(store.get(t.id()).status().equals("CANCEL_REQUESTED")){h.cancel(t);outcome="CANCELLED";}else{h.execute(t);outcome=h.outcome(t);}
            if(store.get(t.id()).status().equals("CANCEL_REQUESTED")){h.cancel(t);outcome="CANCELLED";}
        }catch(Exception e){error=e instanceof ProcessingException?e.getMessage():"阶段处理失败，已完成的成果保留。";outcome="FAILED";
            String code=e instanceof ProcessingException p?p.code():"LOCAL_FAILED";
            if(code.equals("PROVIDER_HTTP_429"))resources.backoff(permit,e instanceof ProcessingException p?p.retryAfterMillis():1000);
            if(code.equals("PROVIDER_HTTP_429")||code.equals("RESOURCE_WAIT")||code.equals("PROCESSOR_BUSY")){store.defer(t,"QUEUED",System.currentTimeMillis()+(code.equals("PROCESSOR_BUSY")?500:e instanceof ProcessingException p?Math.max(1000,p.retryAfterMillis()):30000),"资源繁忙或上游限流，稍后继续");outcome="DEFERRED";}
            else if(code.startsWith("PROVIDER_HTTP_5")||Set.of("PROVIDER_TIMEOUT","PROVIDER_CONNECTION_FAILED","PROVIDER_INVALID_RESPONSE","PROVIDER_INTERRUPTED").contains(code)){outcome="UNKNOWN";unknown=Set.of("PROVIDER_TIMEOUT","PROVIDER_CONNECTION_FAILED","PROVIDER_INTERRUPTED").contains(code);}
            LoggerFactory.getLogger(getClass()).warn("Processing stage {} {}",t.id(),code,e);
        }finally{if(store.get(t.id()).status().equals("CANCEL_REQUESTED")){h.cancel(t);outcome="CANCELLED";}store.finishAttempt(attempt,outcome,error);if(!outcome.equals("DEFERRED"))store.outcome(t,token,outcome,error);resources.finish(permit,unknown||outcome.equals("UNKNOWN")&&h.unknownResource(t));resources.clear();SecurityContextHolder.clearContext();running.merge(t.pool(),-1,Integer::sum);}
    }
    public Map<String,Object> view(){return Map.of("config",store.configView(),"running",Map.copyOf(running),"counts",store.db().queryForList("SELECT pool,status,COUNT(*) AS total FROM execution_task GROUP BY pool,status"),"accounts",store.db().queryForList("SELECT account_id,purpose,status,COUNT(*) AS total FROM resource_permit WHERE status IN ('GRANTED','DISPATCHED','UNKNOWN') GROUP BY account_id,purpose,status"));}
    public void retry(String id){var t=store.get(id);handlers.get(t.kind()).retry(t);store.db().update("UPDATE execution_task SET status='QUEUED',next_run_at_ms=?,error_message=NULL WHERE id=? AND status IN ('FAILED','UNKNOWN')",System.currentTimeMillis(),id);}
    public void cancel(String id){var task=store.get(id);store.cancel(id);store.db().update("UPDATE execution_task SET status='CANCELLED' WHERE kind=? AND business_id=? AND status IN ('QUEUED','WAITING_QUOTA')",task.kind(),task.businessId());handlers.get(task.kind()).cancel(task);}
    @PreDestroy public void close(){closing=true;ticker.shutdownNow();workers.shutdown();try{workers.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
