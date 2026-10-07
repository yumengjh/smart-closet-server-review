package com.smartcloset.agent;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import com.smartcloset.garment.GarmentRepository;
import com.smartcloset.image.ProcessingException;
import org.springframework.http.HttpStatus;

@Service
public class AgentCommands {
    public record Prepare(String kind,UUID targetId,long revision,JsonNode parameters,String idempotencyKey) {}
    public record Command(String id,String kind,JsonNode parameters,String status,boolean requiresConfirmation,long expiresAt,JsonNode result,boolean canUndo,boolean contextChanged) {}
    @org.springframework.beans.factory.annotation.Autowired private AgentMemoryDocuments documents;
    @org.springframework.beans.factory.annotation.Autowired private AgentRenderCommands renderCommands;
    @org.springframework.beans.factory.annotation.Autowired private AgentPlatformCommands platformCommands;
    private final JdbcTemplate db;private final ObjectMapper json;private final GarmentRepository garments;private final TransactionTemplate tx;
    public AgentCommands(JdbcTemplate db,ObjectMapper json,GarmentRepository garments,PlatformTransactionManager manager){this.db=db;this.json=json;this.garments=garments;tx=new TransactionTemplate(manager);}
    private GarmentRepository.View own(String owner,UUID id){if(db.queryForObject("SELECT COUNT(*) FROM garment WHERE id=? AND owner_username=?",Integer.class,id.toString(),owner)!=1)throw AgentRepository.notFound();return garments.get(id);}
    public Command get(String owner,String id){return db.query("SELECT * FROM agent_command WHERE id=? AND owner_username=?",(r,n)->new Command(id,r.getString("kind"),json.readTree(r.getString("parameters_json")),r.getString("status"),r.getBoolean("requires_confirmation"),r.getLong("expires_at"),r.getString("result_json")==null?json.nullNode():json.readTree(r.getString("result_json")),r.getString("status").equals("SUCCEEDED")&&r.getObject("after_revision")!=null,r.getString("kind").equals("MEMORY_PATCH")&&r.getString("status").equals("SUCCEEDED")),id,owner).stream().findFirst().orElseThrow(AgentRepository::notFound);}
    public Command prepare(String owner,Prepare request,String userText) {
        if(request==null||request.parameters()==null||!request.parameters().isObject()||request.idempotencyKey()==null||request.idempotencyKey().length()>100)throw AgentRepository.invalid("操作参数不完整。");
        if(request.kind().equals("MEMORY_PATCH"))return prepareMemory(owner,request,userText);
        if(request.targetId()==null)throw AgentRepository.invalid("请选择目标衣物。");
        if(!Set.of("GARMENT_BASIC","GARMENT_METADATA","GARMENT_TRASH","GARMENT_RESTORE").contains(request.kind()))throw AgentRepository.invalid("此操作尚未开放。");
        return tx.execute(t -> {
            db.queryForObject("SELECT username FROM closet_user WHERE username=? FOR UPDATE",String.class,owner);
            var previous=db.query("SELECT id FROM agent_command WHERE owner_username=? AND idempotency_key=?",(r,n)->r.getString(1),owner,request.idempotencyKey());
            if(!previous.isEmpty())return get(owner,previous.getFirst());
            var item=own(owner,request.targetId());if(item.revision()!=request.revision())throw conflict();
            var params=(tools.jackson.databind.node.ObjectNode)request.parameters().deepCopy();params.put("targetId",request.targetId().toString());params.put("targetLabel",item.name()==null?String.join(" ",item.effective().getOrDefault("category",List.of("未分类衣物"))):item.name());params.put("revision",request.revision());
            boolean confirmation=true;
            if(request.kind().equals("GARMENT_BASIC")) {
                if(params.has("name")&&(params.path("name").asText().isBlank()||params.path("name").asText().length()>100))throw AgentRepository.invalid("名称无效。");
                if(!params.has("name")&&!params.has("favorite"))throw AgentRepository.invalid("请指定修改内容。");
                if(params.has("favorite")&&!params.path("favorite").isBoolean())throw AgentRepository.invalid("收藏值无效。");
                confirmation=(params.has("name")&&!userText.contains(params.path("name").asText()))||(params.has("favorite")&&(params.path("favorite").asBoolean()?(!userText.contains("收藏")||userText.contains("取消收藏")):!userText.matches("(?s).*(取消收藏|移除收藏|不再收藏).*")));
            }
            if(request.kind().equals("GARMENT_METADATA")) {
                if(!params.path("fields").isObject()||params.path("fields").isEmpty())throw AgentRepository.invalid("请选择属性。");
                boolean explicit=true;
                for(var field:params.path("fields").properties()) {
                    var schema=com.smartcloset.recognition.RecognitionSchema.field(field.getKey());
                    if(!field.getValue().isArray())throw AgentRepository.invalid("属性值应为数组。");
                    var values=new ArrayList<String>();for(var v:field.getValue()){values.add(v.asText());explicit&=userText.contains(v.asText());}
                    com.smartcloset.recognition.RecognitionSchema.normalizeValues(schema,values);
                    if(values.isEmpty())explicit=false;
                }
                confirmation=!explicit;
            }
            var before=json.createObjectNode();before.put("revision",item.revision());before.put("status",item.status());if(item.name()!=null)before.put("name",item.name());before.put("favorite",item.favorite());before.set("overrides",json.valueToTree(item.overrides()));
            String id=UUID.randomUUID().toString();long now=System.currentTimeMillis();
            db.update("INSERT INTO agent_command(id,owner_username,kind,parameters_json,before_json,expected_revision,status,requires_confirmation,idempotency_key,created_at,expires_at) VALUES(?,?,?,?,?,?,'PREPARED',?,?,?,?)",id,owner,request.kind(),json.writeValueAsString(params),json.writeValueAsString(before),request.revision(),confirmation,request.idempotencyKey(),now,now+600000);
            return confirmation?get(owner,id):execute(owner,id);
        });
    }
    public Command execute(String owner,String id) {
        return tx.execute(t -> {
            var locked=db.queryForList("SELECT * FROM agent_command WHERE id=? AND owner_username=? FOR UPDATE",id,owner);if(locked.isEmpty())throw AgentRepository.notFound();var command=get(owner,id);
            if(command.status().equals("SUCCEEDED"))return command;
            if(!command.status().equals("PREPARED")||command.expiresAt()<System.currentTimeMillis())throw new ProcessingException(HttpStatus.CONFLICT,"COMMAND_EXPIRED","操作已取消、已使用或过期，请重新准备。");
            if(AgentPlatformCommands.KINDS.contains(command.kind()))return platformCommands.execute(owner,id);
            if(command.kind().endsWith("_GENERATION"))return renderCommands.execute(owner,id);
            var p=command.parameters();if(command.kind().equals("MEMORY_PATCH")){
                var doc=documents.get(owner,p.path("documentKind").asText());var updated=documents.save(owner,doc.kind(),new AgentMemoryDocuments.Edit(p.path("revision").asLong(),patched(doc.content(),p)));
                db.update("UPDATE agent_command SET status='SUCCEEDED',after_revision=?,result_json=? WHERE id=?",updated.revision(),json.writeValueAsString(Map.of("revision",updated.revision(),"changes",p,"canUndo",true)),id);return get(owner,id);
            }
            UUID target=UUID.fromString(p.path("targetId").asText());var current=own(owner,target);long expected=p.path("revision").asLong();if(current.revision()!=expected)throw conflict();
            GarmentRepository.View changed;
            switch(command.kind()) {
                case "GARMENT_BASIC" -> changed=garments.editBasic(owner,target,new GarmentRepository.BasicEdit(expected,p.has("name")?p.path("name").asText():null,p.has("favorite")?p.path("favorite").asBoolean():null));
                case "GARMENT_METADATA" -> {var fields=new LinkedHashMap<String,List<String>>();for(var entry:p.path("fields").properties()){var values=new ArrayList<String>();for(var v:entry.getValue())values.add(v.asText());fields.put(entry.getKey(),values);}changed=garments.edit(target,new GarmentRepository.Edit(expected,fields,null));}
                case "GARMENT_TRASH" -> changed=garments.trash(target,expected);
                case "GARMENT_RESTORE" -> changed=garments.restore(target,expected);
                default -> throw AgentRepository.invalid("此操作尚未开放。");
            }
            var result=Map.of("targetId",target,"revision",changed.revision(),"status",changed.status(),"changes",p,"canUndo",true);
            db.update("UPDATE agent_command SET status='SUCCEEDED',after_revision=?,result_json=? WHERE id=?",changed.revision(),json.writeValueAsString(result),id);
            return get(owner,id);
        });
    }
    public Command cancel(String owner,String id){get(owner,id);db.update("UPDATE agent_command SET status='CANCELLED' WHERE id=? AND owner_username=? AND status='PREPARED'",id,owner);return get(owner,id);}
    public Command undo(String owner,String id) {
        return tx.execute(t -> {
            var rows=db.queryForList("SELECT * FROM agent_command WHERE id=? AND owner_username=? FOR UPDATE",id,owner);if(rows.isEmpty())throw AgentRepository.notFound();var row=rows.getFirst();var cmd=get(owner,id);
            if(!cmd.status().equals("SUCCEEDED"))throw conflict();
            if(cmd.kind().equals("MEMORY_PATCH")){var before=json.readTree(db.queryForObject("SELECT before_json FROM agent_command WHERE id=?",String.class,id));var doc=documents.get(owner,cmd.parameters().path("documentKind").asText());long expected=((Number)row.get("AFTER_REVISION")).longValue();if(doc.revision()!=expected)throw conflict();documents.save(owner,doc.kind(),new AgentMemoryDocuments.Edit(expected,before.path("content").asText()));db.update("UPDATE agent_command SET status='UNDONE' WHERE id=?",id);return get(owner,id);}
            if(!cmd.canUndo())throw AgentRepository.invalid("此操作没有可撤销版本，请使用相应页面继续处理。");
            UUID target=UUID.fromString(cmd.parameters().path("targetId").asText());var current=own(owner,target);long after=((Number)row.get("AFTER_REVISION")).longValue();if(current.revision()!=after)throw conflict();
            JsonNode before=json.readTree(db.queryForObject("SELECT before_json FROM agent_command WHERE id=?",String.class,id));
            if(cmd.kind().equals("GARMENT_BASIC")) {
                // Restores nullable original name without converting it into a made-up name.
                if(db.update("UPDATE garment SET name=?,favorite=?,revision=revision+1,updated_at_ms=? WHERE id=? AND revision=?",before.has("name")?before.path("name").asText():null,before.path("favorite").asBoolean(),System.currentTimeMillis(),target.toString(),after)!=1)throw conflict();
            } else if(cmd.kind().equals("GARMENT_METADATA")){if(db.update("UPDATE garment SET overrides_json=?,revision=revision+1,updated_at_ms=? WHERE id=? AND revision=?",json.writeValueAsString(before.path("overrides")),System.currentTimeMillis(),target.toString(),after)!=1)throw conflict();}
            else if(cmd.kind().equals("GARMENT_TRASH"))garments.restore(target,after);
            else if(cmd.kind().equals("GARMENT_RESTORE"))garments.trash(target,after);
            db.update("UPDATE agent_command SET status='UNDONE',result_json=? WHERE id=?",json.writeValueAsString(Map.of("targetId",target,"revision",own(owner,target).revision(),"canUndo",false)),id);return get(owner,id);
        });
    }
    private Command prepareMemory(String owner,Prepare request,String userText){return tx.execute(t -> {
        db.queryForObject("SELECT username FROM closet_user WHERE username=? FOR UPDATE",String.class,owner);
        var previous=db.query("SELECT id FROM agent_command WHERE owner_username=? AND idempotency_key=?",(r,n)->r.getString(1),owner,request.idempotencyKey());if(!previous.isEmpty())return get(owner,previous.getFirst());
        var p=(tools.jackson.databind.node.ObjectNode)request.parameters().deepCopy();String evidence=p.path("evidence").asText();if(evidence.length()<2||!userText.contains(evidence))throw AgentRepository.invalid("记忆维护需依据本条用户表达，不能从图片或旧助手话语推断。");
        String kind=p.path("documentKind").asText();if(kind.equals("GENERAL")&&p.path("newContent").asText().matches("(?s).*(身高|体重|体型|身材|围度|胸围|腰围|臀围|病史|病情|手机号|身份证|地址).*"))throw AgentRepository.invalid("这段内容可能敏感，请准备敏感文档确认卡，不可自动写入通用记忆。");var doc=documents.get(owner,kind);if(doc.revision()!=request.revision())throw conflict();p.put("revision",doc.revision());p.put("targetLabel",kind.equals("GENERAL")?"通用长期记忆":"敏感长期记忆");String result=patched(doc.content(),p);
        if(result.length()>20000)throw AgentRepository.invalid("记忆文档最多 20000 字。");
        boolean confirmation=kind.equals("SENSITIVE")||p.path("operation").asText().equals("REPLACE_ALL")||p.path("oldContent").asText().length()>1000||(!doc.content().isBlank()&&p.path("oldContent").asText().length()>doc.content().length()/4);
        String id=UUID.randomUUID().toString();long now=System.currentTimeMillis();db.update("INSERT INTO agent_command(id,owner_username,kind,parameters_json,before_json,expected_revision,status,requires_confirmation,idempotency_key,created_at,expires_at) VALUES(?,?,'MEMORY_PATCH',?,?,?,'PREPARED',?,?,?,?)",id,owner,json.writeValueAsString(p),json.writeValueAsString(doc),doc.revision(),confirmation,request.idempotencyKey(),now,now+600000);return confirmation?get(owner,id):execute(owner,id);
    });}
    private String patched(String before,JsonNode p){String replacement=p.path("newContent").asText("");String old=p.path("oldContent").asText("");return switch(p.path("operation").asText()){
        case "APPEND" -> {if(replacement.isBlank())throw AgentRepository.invalid("新增记忆不能为空。");yield before.isBlank()?replacement:before+"\n\n"+replacement;}
        case "REPLACE","REMOVE" -> {if(old.isBlank()||before.indexOf(old)<0||before.indexOf(old)!=before.lastIndexOf(old))throw AgentRepository.invalid("原文不存在或出现多次，请精确核对片段。");yield before.replace(old,p.path("operation").asText().equals("REMOVE")?"":replacement).strip();}
        case "REPLACE_ALL" -> replacement;
        default -> throw AgentRepository.invalid("记忆维护操作无效。");
    };}
    private static ProcessingException conflict(){return new ProcessingException(HttpStatus.CONFLICT,"COMMAND_REVISION_CONFLICT","对象已变化，请重新核对；操作或撤销不会覆盖后续修改。");}
}
