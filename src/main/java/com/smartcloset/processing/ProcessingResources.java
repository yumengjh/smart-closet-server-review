package com.smartcloset.processing;

import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import com.smartcloset.image.ProcessingException;
import org.springframework.http.HttpStatus;

/** One owner process; every actual upstream request passes the same account gate. */
@Service
public class ProcessingResources {
    private final ProcessingStore store;
    @org.springframework.beans.factory.annotation.Autowired private com.smartcloset.generation.GenerationProperties providers;
    private record Context(String token,String account,String attempt){}
    private final Map<String,Long> cooldowns=new HashMap<>();
    private final Map<String,Long> assistantWaiting=new HashMap<>();
    private final ThreadLocal<Context> current=new ThreadLocal<>();
    public ProcessingResources(ProcessingStore store){this.store=store;}
    private String canonical(String id){if(providers==null)return id;var p=providers.providers().get(id);return p==null||p.apiKey()==null||p.apiKey().isBlank()?id:"credential:"+com.smartcloset.workflow.WorkflowFiles.hash(p.apiKey().getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0,24);}
    public synchronized String acquire(String account,String purpose,String binding,long deadline){account=canonical(account);
        long now=System.currentTimeMillis();if(cooldowns.getOrDefault(account,0L)>now||store.db().queryForObject("SELECT COUNT(*) FROM resource_permit WHERE account_id=? AND status='COOLDOWN' AND deadline_ms>?",Integer.class,account,now)>0)return null;store.db().update("UPDATE resource_permit SET status='FINISHED' WHERE status IN ('GRANTED','DISPATCHED','UNKNOWN') AND deadline_ms<?",now);
        var old=store.db().query("SELECT token,status FROM resource_permit WHERE binding=?",(r,n)->List.of(r.getString(1),r.getString(2)),binding);
        if(!old.isEmpty())return Set.of("GRANTED","DISPATCHED").contains(old.getFirst().get(1))?old.getFirst().get(0):null;
        var c=store.config();int total=store.db().queryForObject("SELECT COUNT(*) FROM resource_permit WHERE account_id=? AND status IN ('GRANTED','DISPATCHED','UNKNOWN')",Integer.class,account);
        int bg=store.db().queryForObject("SELECT COUNT(*) FROM resource_permit WHERE account_id=? AND purpose!='ASSISTANT' AND status IN ('GRANTED','DISPATCHED','UNKNOWN')",Integer.class,account);
        int background=c.accountConcurrency()==1?1:Math.max(0,c.accountConcurrency()-c.assistantReserved());
        if(purpose.equals("ASSISTANT"))assistantWaiting.put(account,now+2000);
        if(total>=c.accountConcurrency()||!purpose.equals("ASSISTANT")&&(bg>=background||c.accountConcurrency()==1&&assistantWaiting.getOrDefault(account,0L)>now))return null;
        if(c.rpm()!=null&&store.db().queryForObject("SELECT COUNT(*) FROM resource_permit WHERE account_id=? AND (dispatched_at_ms>? OR status='GRANTED' AND created_at_ms>?)",Integer.class,account,now-60000,now-60000)>=c.rpm())return null;
        if(purpose.equals("ASSISTANT"))assistantWaiting.remove(account);
        String token=UUID.randomUUID().toString();store.db().update("INSERT INTO resource_permit(token,account_id,purpose,binding,status,deadline_ms,heartbeat_ms,created_at_ms) VALUES(?,?,?,?,'GRANTED',?,?,?)",token,account,purpose,binding,deadline,now,now);return token;
    }
    public void bind(String token,String account,String attempt){current.set(new Context(token,account,attempt));}
    public void clear(){current.remove();}
    public void dispatch(String token){store.db().update("UPDATE resource_permit SET status='DISPATCHED',heartbeat_ms=?,dispatched_at_ms=? WHERE token=? AND status='GRANTED'",System.currentTimeMillis(),System.currentTimeMillis(),token);}
    public void backoff(String token,long millis){if(token!=null)store.db().update("UPDATE resource_permit SET status='COOLDOWN',deadline_ms=? WHERE token=?",System.currentTimeMillis()+Math.max(1000,millis),token);}
    public void heartbeat(String token){store.db().update("UPDATE resource_permit SET heartbeat_ms=? WHERE token=? AND status IN ('GRANTED','DISPATCHED')",System.currentTimeMillis(),token);}
    public void finish(String token,boolean unknown){if(token!=null)store.db().update("UPDATE resource_permit SET status=? WHERE token=? AND status IN ('GRANTED','DISPATCHED')",unknown?"UNKNOWN":"FINISHED",token);}
    public void usage(Object usage){var c=current.get();if(c!=null)store.usage(c.attempt(),usage);}
    public <T>T exchange(String account,Supplier<T> call){var c=current.get();boolean own=c==null;String token=own?acquire(account,"BACKGROUND","direct:"+UUID.randomUUID(),System.currentTimeMillis()+610000):c.token();if(token==null)throw new ProcessingException(HttpStatus.TOO_MANY_REQUESTS,"RESOURCE_WAIT","模型资源正在使用，请稍后再试。");if(!own&&!Objects.equals(canonical(c.account()),canonical(account)))throw new IllegalStateException("Account permit mismatch");dispatch(token);if(c!=null)store.dispatched(c.attempt());boolean completed=false;try{T result=call.get();completed=true;return result;}catch(ProcessingException e){if(e.code().equals("PROVIDER_HTTP_429"))synchronized(this){cooldowns.put(canonical(account),System.currentTimeMillis()+Math.max(1000,e.retryAfterMillis()));}throw e;}finally{if(own)finish(token,!completed);}}
}
