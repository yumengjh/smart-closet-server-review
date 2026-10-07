package com.smartcloset.agent;

import com.smartcloset.image.ProcessingException;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;

@Repository
public class AgentRepository {
    public record Session(String id, String title, String model, long createdAt, long updatedAt) {}
    public record Message(String id, String role, String content, String status, JsonNode events, long createdAt, String requestId) {}
    public record Memory(String id, String content, String category, long createdAt, long updatedAt) {}
    public record Proposal(String id, String kind, JsonNode payload, JsonNode context, boolean consumed) {}
    @org.springframework.beans.factory.annotation.Autowired private AgentMemoryDocuments documents;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final AgentProperties config;
    public AgentRepository(JdbcTemplate db, ObjectMapper json, PlatformTransactionManager manager, AgentProperties config, Flyway workflowMigrations) {
        this.db=db; this.json=json; this.tx=new TransactionTemplate(manager); this.config=config;
    }
    public List<Session> sessions(String owner) {
        return db.query("SELECT * FROM agent_session WHERE owner_username=? ORDER BY updated_at DESC LIMIT 100",
            (r,n)->new Session(r.getString("id"),r.getString("title"),r.getString("model"),r.getLong("created_at"),r.getLong("updated_at")), owner);
    }
    public Session session(String owner, String id) {
        return db.query("SELECT * FROM agent_session WHERE id=? AND owner_username=?",
            (r,n)->new Session(r.getString("id"),r.getString("title"),r.getString("model"),r.getLong("created_at"),r.getLong("updated_at")),id,owner).stream().findFirst().orElseThrow(AgentRepository::notFound);
    }
    public Session create(String owner) {
        String id=UUID.randomUUID().toString(); long now=System.currentTimeMillis();
        db.update("INSERT INTO agent_session(id,owner_username,title,created_at,updated_at) VALUES(?,?,?, ?,?)",id,owner,"新对话",now,now);
        return session(owner,id);
    }
    public Session update(String owner, String id, String title, String model) {
        session(owner,id); checkModel(model);
        title=clean(title,100);
        db.update("UPDATE agent_session SET title=?,model=?,updated_at=? WHERE id=? AND owner_username=?",title,model,System.currentTimeMillis(),id,owner);
        return session(owner,id);
    }
    public void delete(String owner,String id) {
        tx.executeWithoutResult(t->{ assertIdle(owner,id); db.update("DELETE FROM agent_session WHERE id=? AND owner_username=?",id,owner); });
    }
    public void clear(String owner) {
        tx.executeWithoutResult(t->{
            db.queryForObject("SELECT username FROM closet_user WHERE username=? FOR UPDATE",String.class,owner);
            if (db.queryForObject("SELECT COUNT(*) FROM agent_session WHERE owner_username=? AND active_run IS NOT NULL AND active_at>?",Integer.class,owner,System.currentTimeMillis()-config.timeoutSeconds()*1000L-10000)>0)
                throw busy();
            db.update("DELETE FROM agent_session WHERE owner_username=?",owner);
        });
    }
    private void assertIdle(String owner,String id) {
        session(owner,id);
        var active=db.queryForList("SELECT active_run,active_at FROM agent_session WHERE id=? AND owner_username=? FOR UPDATE",id,owner).getFirst();
        if (active.get("ACTIVE_RUN")!=null && ((Number)active.get("ACTIVE_AT")).longValue()>System.currentTimeMillis()-config.timeoutSeconds()*1000L-10000) throw busy();
    }
    public List<Message> messages(String owner,String id) {
        session(owner,id);
        var list=db.query("SELECT * FROM agent_message WHERE session_id=? ORDER BY created_at DESC,id DESC LIMIT 100",
            (r,n)->new Message(r.getString("id"),r.getString("role"),r.getString("content"),r.getString("status"),json.readTree(r.getString("events_json")),r.getLong("created_at"),r.getString("request_id")),id);
        Collections.reverse(list);
        for (var message : list) for (var event : message.events()) {
            if ("proposal".equals(event.path("event").asText()) && event.path("data").isObject()) {
                var consumed = db.query("SELECT consumed FROM agent_proposal WHERE id=? AND owner_username=?", (r,n)->r.getBoolean(1),event.path("data").path("id").asText(),owner);
                ((tools.jackson.databind.node.ObjectNode)event.path("data")).put("consumed",consumed.isEmpty()||consumed.getFirst());
            }
        }
        for (var message : list) for (var event : message.events()) {
            if ("memory".equals(event.path("event").asText()) && event.path("data").isObject()) {
                boolean revoked=db.queryForObject("SELECT COUNT(*) FROM agent_memory WHERE id=? AND owner_username=?",Integer.class,event.path("data").path("id").asText(),owner)==0;
                ((tools.jackson.databind.node.ObjectNode)event.path("data")).put("revoked",revoked);
            }
        }
        return list;
    }
    public void begin(String owner,String id,String requestId,String text) {
        text=clean(text,4000); final String content=text;
        try { UUID.fromString(requestId); } catch(Exception e){throw invalid("请求 ID 无效。");}
        tx.executeWithoutResult(t->{
            db.queryForObject("SELECT username FROM closet_user WHERE username=? FOR UPDATE",String.class,owner);
            assertIdle(owner,id);
            if(db.queryForObject("SELECT COUNT(*) FROM agent_message WHERE id=?",Integer.class,requestId)>0)
                throw new ProcessingException(HttpStatus.CONFLICT,"AGENT_ALREADY_SUBMITTED","这条消息已经提交，请查看会话结果，不要重复扣费。");
            LocalDate day=LocalDate.now(ZoneId.of("Asia/Shanghai"));
            var used=db.query("SELECT used FROM agent_daily_quota WHERE owner_username=? AND quota_date=?",(r,n)->r.getInt(1),owner,java.sql.Date.valueOf(day));
            if(!used.isEmpty() && used.getFirst()>=config.dailyLimit()) throw new ProcessingException(HttpStatus.TOO_MANY_REQUESTS,"AGENT_QUOTA_EXCEEDED","今天的助手消息额度已用完，明天再来。");
            if(used.isEmpty()) db.update("INSERT INTO agent_daily_quota VALUES(?,?,1)",owner,java.sql.Date.valueOf(day));
            else db.update("UPDATE agent_daily_quota SET used=used+1 WHERE owner_username=? AND quota_date=?",owner,java.sql.Date.valueOf(day));
            long now=System.currentTimeMillis();
            db.update("INSERT INTO agent_message(id,session_id,role,content,status,events_json,created_at) VALUES(?,?,?,?,'COMPLETE','[]',?)",requestId,id,"user",content,now);
            db.update("INSERT INTO agent_message(id,session_id,role,content,status,events_json,created_at,request_id) VALUES(?,?,'assistant','','RUNNING','[]',?,?)",UUID.randomUUID().toString(),id,now+1,requestId);
            db.update("UPDATE agent_message SET context_epoch=(SELECT context_epoch FROM agent_session WHERE id=?) WHERE session_id=? AND (id=? OR request_id=?)",id,id,requestId,requestId);
            db.update("UPDATE agent_session SET active_run=?,active_at=?,updated_at=?,title=CASE WHEN title='新对话' THEN ? ELSE title END WHERE id=? AND owner_username=?",requestId,now,now,content.substring(0,Math.min(30,content.length())),id,owner);
        });
    }
    public void finish(String owner,String id,String requestId,String text,String status,List<Map<String,Object>> events) {
        tx.executeWithoutResult(t->{
            int changed=db.update("UPDATE agent_session SET active_run=NULL,active_at=NULL,updated_at=? WHERE id=? AND owner_username=? AND active_run=?",System.currentTimeMillis(),id,owner,requestId);
            if(changed==1) db.update("UPDATE agent_message SET content=?,status=?,events_json=?,created_at=? WHERE session_id=? AND request_id=? AND role='assistant'",text,status,json.writeValueAsString(events),System.currentTimeMillis(),id,requestId);
        });
    }
    public Map<String,Object> quota(String owner) {
        var day=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        var used=db.query("SELECT used FROM agent_daily_quota WHERE owner_username=? AND quota_date=?",(r,n)->r.getInt(1),owner,java.sql.Date.valueOf(day));
        return Map.of("limit",config.dailyLimit(),"used",used.isEmpty()?0:used.getFirst(),"resetAt",day.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toString());
    }
    public String preference(String owner) {
        return db.query("SELECT model FROM agent_preference WHERE owner_username=?",(r,n)->r.getString(1),owner).stream().findFirst().orElse(config.defaultModel());
    }
    public void preference(String owner,String model) {
        checkModel(model); if(model==null)throw invalid("请选择模型。");
        tx.executeWithoutResult(t->{db.queryForObject("SELECT username FROM closet_user WHERE username=? FOR UPDATE",String.class,owner);db.update("MERGE INTO agent_preference(owner_username,model) KEY(owner_username) VALUES(?,?)",owner,model);});
    }
    public String avatarMode(String owner) {
        return db.query("SELECT avatar_mode FROM agent_preference WHERE owner_username=?",(r,n)->r.getString(1),owner).stream().findFirst().orElse("personal");
    }
    public void avatarMode(String owner,String mode) {
        if(!Set.of("static","basic","personal").contains(mode))throw invalid("助手动效模式无效。");
        tx.executeWithoutResult(t->{db.queryForObject("SELECT username FROM closet_user WHERE username=? FOR UPDATE",String.class,owner);db.update("MERGE INTO agent_preference(owner_username,model,avatar_mode) KEY(owner_username) VALUES(?,?,?)",owner,preference(owner),mode);});
    }
    public void checkModel(String model) {
        if(model!=null && !config.allowedModels().contains(model))throw invalid("该模型未由后端开放。");
    }
    public List<Memory> memories(String owner) {
        return db.query("SELECT * FROM agent_memory WHERE owner_username=? ORDER BY updated_at DESC LIMIT 100",(r,n)->new Memory(r.getString("id"),r.getString("content"),r.getString("category"),r.getLong("created_at"),r.getLong("updated_at")),owner);
    }
    public Memory memory(String owner,String id,String content,String category) {return tx.execute(t->{db.queryForObject("SELECT username FROM closet_user WHERE username=? FOR UPDATE",String.class,owner);return memoryLocked(owner,id,content,category);});}
    private Memory memoryLocked(String owner,String id,String content,String category) {
        content=clean(content,500);
        if(!Set.of("STYLE","COLOR","OCCASION","PERSONAL").contains(category))throw invalid("记忆分类无效。");
        final String originalId=id;var before=id==null?null:memories(owner).stream().filter(m -> m.id().equals(originalId)).findFirst().orElseThrow(AgentRepository::notFound);
        documents.list(owner);
        long now=System.currentTimeMillis();
        if(id==null){
            var same=db.query("SELECT id FROM agent_memory WHERE owner_username=? AND content=?",(r,n)->r.getString(1),owner,content);
            if(!same.isEmpty())return memories(owner).stream().filter(m->m.id().equals(same.getFirst())).findFirst().orElseThrow();
            if(memories(owner).size()>=100)throw invalid("记忆已达 100 条，请先整理已有记忆。");
            id=UUID.randomUUID().toString();db.update("INSERT INTO agent_memory VALUES(?,?,?,?,?,?)",id,owner,content,category,now,now);
        } else if(db.update("UPDATE agent_memory SET content=?,category=?,updated_at=? WHERE id=? AND owner_username=?",content,category,now,id,owner)!=1)throw notFound();
        if(before!=null&&!before.category().equals(category))documents.legacyChange(owner,before.category(),before.content(),null);
        documents.legacyChange(owner,category,before==null||!before.category().equals(category)?null:before.content(),content);
        final String key=id;return memories(owner).stream().filter(m->m.id().equals(key)).findFirst().orElseThrow(AgentRepository::notFound);
    }
    public void deleteMemory(String owner,String id) {tx.executeWithoutResult(t->{db.queryForObject("SELECT username FROM closet_user WHERE username=? FOR UPDATE",String.class,owner); var before=memories(owner).stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow(AgentRepository::notFound);documents.list(owner);if(db.update("DELETE FROM agent_memory WHERE id=? AND owner_username=?",id,owner)!=1)throw notFound();documents.legacyChange(owner,before.category(),before.content(),null); });}
    public Proposal propose(String owner,String session,String kind,JsonNode payload,JsonNode context) {
        var versions=json.createObjectNode();var ids=new LinkedHashSet<String>();
        for(var c:payload.path("candidates"))for(var g:c.path("garmentIds"))ids.add(g.asText());
        for(var e:context.path("scene").path("elements"))if(!e.path("garmentId").asText().isBlank())ids.add(e.path("garmentId").asText());
        for(String garment:ids){var revisions=db.query("SELECT revision FROM garment WHERE id=? AND owner_username=?",(r,n)->r.getLong(1),garment,owner);if(revisions.isEmpty())throw notFound();versions.put(garment,revisions.getFirst());}
        if(payload.isObject()){payload=payload.deepCopy();((tools.jackson.databind.node.ObjectNode)payload).set("_garmentRevisions",versions);}
        var id=UUID.randomUUID().toString(); db.update("INSERT INTO agent_proposal(id,session_id,owner_username,kind,payload_json,context_json,created_at) VALUES(?,?,?,?,?,?,?)",id,session,owner,kind,json.writeValueAsString(payload),json.writeValueAsString(context),System.currentTimeMillis());
        return new Proposal(id,kind,payload,context,false);
    }
    public Proposal proposal(String owner,String id) {
        return db.query("SELECT * FROM agent_proposal WHERE id=? AND owner_username=?",(r,n)->new Proposal(r.getString("id"),r.getString("kind"),json.readTree(r.getString("payload_json")),json.readTree(r.getString("context_json")),r.getBoolean("consumed")),id,owner).stream().findFirst().orElseThrow(AgentRepository::notFound);
    }
    public Proposal consume(String owner,String id) {
        var p=proposal(owner,id);
        for(var entry:p.payload().path("_garmentRevisions").properties())if(db.queryForObject("SELECT COUNT(*) FROM garment WHERE id=? AND owner_username=? AND revision=?",Integer.class,entry.getKey(),owner,entry.getValue().asLong())!=1)throw new ProcessingException(HttpStatus.CONFLICT,"AGENT_PROPOSAL_CHANGED","衣物资料已变化，请重新准备建议。");
        var created=db.queryForObject("SELECT created_at FROM agent_proposal WHERE id=? AND owner_username=?",Long.class,id,owner);if(created+600000<System.currentTimeMillis())throw new ProcessingException(HttpStatus.CONFLICT,"AGENT_PROPOSAL_EXPIRED","建议已过期，请重新准备。");
        if(db.update("UPDATE agent_proposal SET consumed=TRUE WHERE id=? AND owner_username=? AND consumed=FALSE",id,owner)!=1)
            throw new ProcessingException(HttpStatus.CONFLICT,"AGENT_PROPOSAL_USED","此建议已经应用，不能重复操作。");
        return p;
    }
    static ProcessingException notFound(){return new ProcessingException(HttpStatus.NOT_FOUND,"AGENT_NOT_FOUND","没有找到该会话、记忆或建议。");}
    static ProcessingException busy(){return new ProcessingException(HttpStatus.CONFLICT,"AGENT_BUSY","助手正在回复，请稍后再试。");}
    static ProcessingException invalid(String message){return new ProcessingException(HttpStatus.BAD_REQUEST,"AGENT_INVALID_REQUEST",message);}
    static String clean(String value,int max){if(value==null||value.isBlank()||value.length()>max)throw invalid("文本不能为空，且不能超过 "+max+" 字。");return value.strip();}
}
