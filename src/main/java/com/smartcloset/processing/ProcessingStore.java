package com.smartcloset.processing;

import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.smartcloset.image.ProcessingException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

@Repository
public class ProcessingStore {
    public record Task(String id,String businessKey,String owner,String kind,String businessId,int stage,String pool,String account,int priority,String status,long nextRunAt,long createdAt,String waitReason,String error) {}
    public record Config(int cpu,int recognition,int generation,int accountConcurrency,int assistantReserved,Integer rpm,Set<String> paused) {}
    private final JdbcTemplate db;
    public JdbcTemplate db(){return db;}
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final com.smartcloset.security.QuotaPolicy quotas;
    public ProcessingStore(JdbcTemplate db,ObjectMapper json,PlatformTransactionManager manager,Flyway workflowMigrations,com.smartcloset.security.QuotaPolicy quotas) {this.db=db;this.json=json;this.quotas=quotas;tx=new TransactionTemplate(manager);}
    public Config config() {
        var rows=db.query("SELECT value_json FROM processing_config WHERE id='limits'",(r,n)->r.getString(1));
        return rows.isEmpty()?new Config(1,1,1,2,1,null,Set.of()):json.readValue(rows.getFirst(),Config.class);
    }
    public synchronized Map<String,Object> setConfig(Config c,long revision,String owner) {
        if(c==null||c.cpu()<1||c.cpu()>16||c.recognition()<1||c.recognition()>32||c.generation()<1||c.generation()>16||c.accountConcurrency()<1||c.accountConcurrency()>64||c.assistantReserved()<0||c.assistantReserved()>c.accountConcurrency()||c.rpm()!=null&&c.rpm()<1||c.paused()==null||!Set.of("CPU","RECOGNITION","GENERATION").containsAll(c.paused())) throw ProcessingException.invalid("资源容量或暂停配置无效。");
        var rows=db.query("SELECT revision FROM processing_config WHERE id='limits'",(r,n)->r.getLong(1));
        if(rows.isEmpty()) {if(revision!=0) throw conflict();db.update("INSERT INTO processing_config VALUES('limits',?,1,?,?)",json.writeValueAsString(c),owner,System.currentTimeMillis());}
        else if(db.update("UPDATE processing_config SET value_json=?,revision=revision+1,updated_by=?,updated_at_ms=? WHERE id='limits' AND revision=?",json.writeValueAsString(c),owner,System.currentTimeMillis(),revision)!=1) throw conflict();
        return configView();
    }
    private ProcessingException conflict(){return new ProcessingException(HttpStatus.CONFLICT,"CONFIG_CONFLICT","配置已变化，请刷新后再保存。");}
    public Map<String,Object> configView(){var rows=db.query("SELECT revision FROM processing_config WHERE id='limits'",(r,n)->r.getLong(1));return Map.of("limits",config(),"revision",rows.isEmpty()?0L:rows.getFirst());}
    private Task map(java.sql.ResultSet r,int n)throws java.sql.SQLException{return new Task(r.getString("id"),r.getString("business_key"),r.getString("owner_username"),r.getString("kind"),r.getString("business_id"),r.getInt("stage"),r.getString("pool"),r.getString("account_id"),r.getInt("priority"),r.getString("status"),r.getLong("next_run_at_ms"),r.getLong("created_at_ms"),r.getString("wait_reason"),r.getString("error_message"));}
    public synchronized void enqueue(String kind,String id,int stage,String owner,String pool,String account,int priority) {enqueue(kind,id,stage,owner,pool,account,priority,null);}
    public synchronized void enqueue(String kind,String id,int stage,String owner,String pool,String account,int priority,String providedPayload) {
        String key=kind+":"+id+":"+stage; if(db.queryForObject("SELECT COUNT(*) FROM execution_task WHERE business_key=?",Integer.class,key)>0)return;
        long now=System.currentTimeMillis();db.update("INSERT INTO execution_task(id,business_key,owner_username,kind,business_id,stage,pool,account_id,priority,status,next_run_at_ms,created_at_ms,updated_at_ms) VALUES(?,?,?,?,?,?,?,?,?,'QUEUED',?,?,?)",UUID.randomUUID().toString(),key,owner,kind,id,stage,pool,account,priority,now,now,now);
        if(providedPayload!=null){db.update("UPDATE execution_task SET payload_json=? WHERE business_key=?",providedPayload,key);return;}
        if(!Set.of("BATCH","WORKFLOW","QUICK").contains(kind))return;
        String table=kind.equals("BATCH")?"batch_call":"workflow_job";String column=kind.equals("BATCH")?"payload_json":"request_json";var frozen=db.query("SELECT "+column+" FROM "+table+" WHERE id=?",(r,n)->r.getString(1),id);if(!frozen.isEmpty())db.update("UPDATE execution_task SET payload_json=?,dependency_key=? WHERE business_key=?",frozen.getFirst(),stage==0?null:kind+":"+id+":"+(stage-1),key);
    }
    public List<Task> due(){return db.query("SELECT * FROM (SELECT e.*,ROW_NUMBER() OVER (PARTITION BY owner_username,pool,account_id ORDER BY priority DESC,created_at_ms,id) AS due_rank FROM execution_task e WHERE status IN ('QUEUED','WAITING_QUOTA') AND next_run_at_ms<=?) WHERE due_rank<=8 ORDER BY created_at_ms,id",this::map,System.currentTimeMillis());}
    public List<Task> list(String owner,int page,int size){if(page<1||size<1||size>100)throw ProcessingException.invalid("分页无效。");return owner==null?db.query("SELECT * FROM execution_task ORDER BY created_at_ms DESC LIMIT ? OFFSET ?",this::map,size,(page-1)*size):db.query("SELECT * FROM execution_task WHERE owner_username=? ORDER BY created_at_ms DESC LIMIT ? OFFSET ?",this::map,owner,size,(page-1)*size);}
    public Task get(String id){var rows=db.query("SELECT * FROM execution_task WHERE id=?",this::map,id);if(rows.isEmpty())throw new ProcessingException(HttpStatus.NOT_FOUND,"TASK_NOT_FOUND","任务不存在。");return rows.getFirst();}
    public String claim(Task t){String token=UUID.randomUUID().toString();return db.update("UPDATE execution_task SET status='RUNNING',claim_token=?,version=version+1,updated_at_ms=?,wait_reason=NULL WHERE id=? AND status IN ('QUEUED','WAITING_QUOTA')",token,System.currentTimeMillis(),t.id())==1?token:null;}
    public void outcome(Task t,String token,String status,String message){String result=null;if(t.kind().equals("BATCH")){var rows=db.query("SELECT result_json FROM batch_call WHERE id=?",(r,n)->r.getString(1),t.businessId());if(!rows.isEmpty())result=rows.getFirst();}else if(Set.of("WORKFLOW","QUICK").contains(t.kind())){var rows=db.query("SELECT result_json FROM workflow_step WHERE job_id=? AND step_no=?",(r,n)->r.getString(1),t.businessId(),t.stage());if(!rows.isEmpty())result=rows.getFirst();}db.update("UPDATE execution_task SET result_json=? WHERE id=? AND claim_token=?",result,t.id(),token);db.update("UPDATE execution_task SET status=?,error_message=?,claim_token=NULL,updated_at_ms=?,version=version+1 WHERE id=? AND claim_token=?",status,shortText(message),System.currentTimeMillis(),t.id(),token);}
    public void defer(Task t,String status,long next,String reason){db.update("UPDATE execution_task SET status=?,next_run_at_ms=?,wait_reason=?,claim_token=NULL,updated_at_ms=? WHERE id=? AND status IN ('RUNNING','QUEUED','WAITING_QUOTA')",status,next,reason,System.currentTimeMillis(),t.id());}
    public String attempt(Task t,int requestedUnits){return tx.execute(s->{int units=requestedUnits;
        // A rejected request retry retains the original logical-task charge, never charges twice.
        if(units>0&&db.queryForObject("SELECT COUNT(*) FROM execution_attempt WHERE task_id=? AND dispatched=1 AND quota_units>0",Integer.class,t.id())>0) units=0;
        var day=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        if(units>0&&t.owner()!=null){db.query("SELECT username FROM closet_user WHERE username=? FOR UPDATE",(r,n)->r.getString(1),t.owner());var q=db.query("SELECT used FROM ai_daily_quota WHERE owner_username=? AND quota_date=? FOR UPDATE",(r,n)->r.getInt(1),t.owner(),java.sql.Date.valueOf(day));int used=q.isEmpty()?0:q.getFirst();if(used+units>quotas.imageDailyLimit(t.owner()))return null;if(q.isEmpty())db.update("INSERT INTO ai_daily_quota VALUES(?,?,?)",t.owner(),java.sql.Date.valueOf(day),units);else db.update("UPDATE ai_daily_quota SET used=used+? WHERE owner_username=? AND quota_date=?",units,t.owner(),java.sql.Date.valueOf(day));}
        String id=UUID.randomUUID().toString();int no=db.queryForObject("SELECT COALESCE(MAX(attempt_no),0)+1 FROM execution_attempt WHERE task_id=?",Integer.class,t.id());db.update("INSERT INTO execution_attempt(id,task_id,attempt_no,status,quota_date,quota_units,started_at_ms) VALUES(?,?,?,'GRANTED',?,?,?)",id,t.id(),no,java.sql.Date.valueOf(day),units,System.currentTimeMillis());return id;
    });}
    public void dispatched(String attempt){if(attempt!=null)db.update("UPDATE execution_attempt SET dispatched=1,status='DISPATCHED' WHERE id=?",attempt);}
    public void finishAttempt(String id,String status,String error){if(id==null)return;tx.executeWithoutResult(s->{var rows=db.queryForList("SELECT a.*,t.owner_username FROM execution_attempt a JOIN execution_task t ON a.task_id=t.id WHERE a.id=? FOR UPDATE",id);if(rows.isEmpty())return;var r=rows.getFirst();if(r.get("FINISHED_AT_MS")!=null)return;int units=((Number)r.get("QUOTA_UNITS")).intValue();if(((Number)r.get("DISPATCHED")).intValue()==0&&units>0)db.update("UPDATE ai_daily_quota SET used=GREATEST(0,used-?) WHERE owner_username=? AND quota_date=?",units,r.get("OWNER_USERNAME"),r.get("QUOTA_DATE"));db.update("UPDATE execution_attempt SET status=?,finished_at_ms=?,error_message=? WHERE id=?",status,System.currentTimeMillis(),shortText(error),id);});}
    public void usage(String id,Object usage){if(id!=null&&usage!=null)db.update("UPDATE execution_attempt SET usage_json=? WHERE id=?",json.writeValueAsString(usage),id);}
    public void cancel(String id){db.update("UPDATE execution_task SET status=CASE WHEN status='RUNNING' THEN 'CANCEL_REQUESTED' ELSE 'CANCELLED' END,updated_at_ms=? WHERE id=? AND status IN ('QUEUED','WAITING_QUOTA','RUNNING')",System.currentTimeMillis(),id);}
    public long tomorrow(){return LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();}
    public void recover(){
        var rows=db.query("SELECT a.id,a.task_id,a.dispatched FROM execution_attempt a WHERE a.finished_at_ms IS NULL",(r,n)->List.of(r.getString(1),r.getString(2),r.getInt(3)));
        for(var r:rows){boolean sent=(int)r.get(2)==1;finishAttempt((String)r.get(0),sent?"UNKNOWN":"INTERRUPTED","进程中断");db.update("UPDATE execution_task SET status=?,claim_token=NULL,wait_reason=?,next_run_at_ms=? WHERE id=? AND status IN ('RUNNING','CANCEL_REQUESTED')",sent?"UNKNOWN":"QUEUED",sent?"已发送，结果不确定，不自动重发":"本地或未发送任务恢复",System.currentTimeMillis(),r.get(1));}
        db.update("UPDATE execution_task SET status='QUEUED',claim_token=NULL WHERE status='RUNNING'");
        db.update("UPDATE execution_task e SET status='SUCCEEDED' WHERE e.status='UNKNOWN' AND e.kind IN ('WORKFLOW','QUICK') AND EXISTS(SELECT 1 FROM workflow_step s WHERE s.job_id=e.business_id AND s.step_no=e.stage AND s.status='SUCCEEDED')");
        db.update("UPDATE execution_task SET status='CANCELLED',claim_token=NULL WHERE status='CANCEL_REQUESTED'");
        db.update("UPDATE resource_permit SET status=CASE WHEN status='DISPATCHED' THEN 'UNKNOWN' ELSE 'FINISHED' END WHERE status IN ('GRANTED','DISPATCHED')");
    }
    public static String shortText(String s){return s==null?null:s.substring(0,Math.min(1000,s.length()));}
}
