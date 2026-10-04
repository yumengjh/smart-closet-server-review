package com.smartcloset.agent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;

/** Evidence captured at execution time. Never part of model history. */
@Service
public class AgentTraceService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AgentRepository repo;
    private final TransactionTemplate tx;
    private final Map<String,List<String>> redactions=new ConcurrentHashMap<>();
    private static final Set<String> PRIVATE_KEYS=Set.of("apikey","callbacktoken","authorization","secret","baseurl","callbackurl","image","image_url","password","heightcm","weightkg","bust","waist","hips");
    public AgentTraceService(JdbcTemplate db,ObjectMapper json,AgentRepository repo,PlatformTransactionManager manager) {
        this.db=db;this.json=json;this.repo=repo;this.tx=new TransactionTemplate(manager);
    }
    @jakarta.annotation.PostConstruct
    public void recoverInterruptedRuns() {
        for(String request:db.query("SELECT request_id FROM agent_trace_run WHERE status='RUNNING'",(r,n)->r.getString(1))) {
            append(request,"end","INTERRUPTED","服务重启，运行中断",Map.of("message","服务重启，旧运行已中断。此前记录仍可查看，未自动重试。"));
            finish(request,"INTERRUPTED");
        }
        db.update("UPDATE agent_session SET active_run=NULL,active_at=NULL WHERE active_run IN (SELECT request_id FROM agent_message WHERE status='RUNNING' AND role='assistant')");
        db.update("UPDATE agent_message SET status='FAILED',content='服务重启，回复中断。可查看已保存的决策与执行记录。' WHERE status='RUNNING' AND role='assistant'");
    }
    public void start(String session,String request,List<String> hidden) {
        redactions.put(request,hidden.stream().filter(s->s!=null&&!s.isBlank()).distinct().toList());
        db.update("INSERT INTO agent_trace_run(request_id,session_id,status,created_at) VALUES(?,?,'RUNNING',?)",request,session,System.currentTimeMillis());
    }
    public void finish(String request,String status) {
        db.update("UPDATE agent_trace_run SET status=? WHERE request_id=?",status,request);redactions.remove(request);
    }
    public Map<String,Object> append(String request,String phase,String status,String label,Object detail) {
        JsonNode safe=sanitize(request,json.valueToTree(detail));
        return tx.execute(t->{
            Long seq=db.queryForObject("SELECT next_seq FROM agent_trace_run WHERE request_id=? FOR UPDATE",Long.class,request)+1;
            db.update("UPDATE agent_trace_run SET next_seq=? WHERE request_id=?",seq,request);
            long now=System.currentTimeMillis();
            db.update("INSERT INTO agent_trace_event VALUES(?,?,?,?,?,?,?)",request,seq,phase,status,label,json.writeValueAsString(safe),now);
            return Map.of("requestId",request,"seq",seq,"phase",phase,"status",status,"label",label,"createdAt",now);
        });
    }
    JsonNode sanitize(String request,JsonNode value) {
        if(value.isObject()) {
            var result=json.createObjectNode();boolean personal=value.path("category").isTextual()&&"PERSONAL".equals(value.path("category").asText());
            value.properties().forEach(e->result.set(e.getKey(),PRIVATE_KEYS.contains(e.getKey().toLowerCase(Locale.ROOT))||personal&&Set.of("content","evidence").contains(e.getKey())?json.valueToTree("[已隐藏]"):sanitize(request,e.getValue())));
            return result;
        }
        if(value.isArray()){var result=json.createArrayNode();value.forEach(v->result.add(sanitize(request,v)));return result;}
        if(value.isTextual()) {
            String text=value.asText();for(String hidden:redactions.getOrDefault(request,List.of()))text=text.replace(hidden,"[已隐藏]");
            text=text.replaceAll("data:image/[^\\s\"']+","[图片数据已隐藏]");
            text=text.replaceAll("(?i)(?:Bearer\\s+)[A-Za-z0-9._~+/=-]+","Bearer [已隐藏]");
            text=text.replaceAll("(?i)(身高|体重|胸围|腰围|臀围|height|weight)\\s*[:：=]?\\s*\\d+(?:\\.\\d+)?\\s*(?:cm|kg|厘米|公斤|千克)?","$1[已隐藏]");
            return json.valueToTree(text);
        }
        return value;
    }
    private void owned(String owner,String session,String request) {
        repo.session(owner,session);
        if(db.queryForObject("SELECT COUNT(*) FROM agent_trace_run WHERE request_id=? AND session_id=?",Integer.class,request,session)!=1)throw AgentRepository.notFound();
    }
    public Object list(String owner,String session,String request,long after,int limit) {
        repo.session(owner,session);
        if(db.queryForObject("SELECT COUNT(*) FROM agent_trace_run WHERE request_id=? AND session_id=?",Integer.class,request,session)==0)
            return Map.of("available",false,"items",List.of(),"hasMore",false,"message","当时未记录详细调试信息。");
        int size=Math.max(1,Math.min(100,limit));
        var items=db.query("SELECT seq,phase,status,label,created_at FROM agent_trace_event WHERE request_id=? AND seq>? ORDER BY seq LIMIT ?",
            (r,n)->Map.<String,Object>of("requestId",request,"seq",r.getLong("seq"),"phase",r.getString("phase"),"status",r.getString("status"),"label",r.getString("label"),"createdAt",r.getLong("created_at")),request,Math.max(0,after),size+1);
        boolean more=items.size()>size;if(more)items=items.subList(0,size);
        return Map.of("available",true,"status",db.queryForObject("SELECT status FROM agent_trace_run WHERE request_id=?",String.class,request),"items",items,"hasMore",more);
    }
    public Object detail(String owner,String session,String request,long seq) {
        owned(owner,session,request);
        return db.query("SELECT detail_json FROM agent_trace_event WHERE request_id=? AND seq=?",(r,n)->json.readTree(r.getString(1)),request,seq).stream().findFirst().orElseThrow(AgentRepository::notFound);
    }
    public void linkBoard(String session,String board,String request) {
        if(!board.isBlank())db.update("MERGE INTO agent_trace_board(session_id,board_id,request_id) KEY(session_id,board_id,request_id) VALUES(?,?,?)",session,board,request);
    }
    public Object boardEvent(String owner,String session,JsonNode input) {
        repo.session(owner,session);
        if(input==null||!input.isObject()||json.writeValueAsBytes(input).length>200000)throw AgentRepository.invalid("画板追踪数据无效或过大。");
        String event=input.path("eventId").asText(),board=input.path("boardId").asText();
        try{UUID.fromString(event);UUID.fromString(board);}catch(Exception e){throw AgentRepository.invalid("追踪 ID 无效。");}
        var requests=db.query("SELECT request_id FROM agent_trace_board WHERE session_id=? AND board_id=? ORDER BY request_id",(r,n)->r.getString(1),session,board);
        if(requests.isEmpty())throw AgentRepository.notFound();
        if(!input.path("workspaceId").asText().equals("agent:"+session))throw AgentRepository.invalid("画板工作区不匹配。");
        String source=input.path("source").asText();
        if(!Set.of("agent","manual-drag","manual-transform","manual-role","manual-layer","manual-edit","default-layout","undo","redo","restore","proposal").contains(source))throw AgentRepository.invalid("追踪来源无效。");
        var detail=json.createObjectNode();
        for(String key:List.of("eventId","deviceId","clientSequence","occurredAt","workspaceId","boardId","source","operationId","runId","beforeVersion","afterVersion","ackStatus","message"))if(input.has(key))detail.set(key,input.get(key));
        detail.put("evidenceSource","CLIENT_REPORTED");
        for(String key:List.of("before","after"))if(input.has(key))detail.set(key,safeScene(input.path(key)));
        return tx.execute(t->{
            db.queryForObject("SELECT id FROM agent_session WHERE id=? FOR UPDATE",String.class,session);
            if(db.queryForObject("SELECT COUNT(*) FROM agent_trace_client_event WHERE session_id=? AND event_id=?",Integer.class,session,event)>0)return Map.of("duplicate",true);
            db.update("INSERT INTO agent_trace_client_event VALUES(?,?)",session,event);
            Object summary=Map.of();for(String request:requests)summary=append(request,"canvas",source.equals("restore")?"RESTORED":"REPORTED","客户端画布记录 · "+source,detail);return summary;
        });
    }
    public JsonNode safeScene(JsonNode scene) {
        var result=json.createObjectNode();if(scene.has("version"))result.set("version",scene.get("version"));var elements=result.putArray("elements");
        if(scene.path("elements").size()>100)throw AgentRepository.invalid("画布过大。");
        for(var item:scene.path("elements")) {
            var safe=json.createObjectNode();
            for(String key:List.of("elementId","kind","garmentId","role","assetId","assetKind","garmentRevision","revision","personPartId","x","y","width","height","rotation","zIndex","flipX","cropX","cropY","cropWidth","cropHeight"))if(item.has(key))safe.set(key,item.get(key));
            elements.add(safe);
        }
        return result;
    }
}
