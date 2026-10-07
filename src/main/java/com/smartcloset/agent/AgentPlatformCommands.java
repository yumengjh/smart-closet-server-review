package com.smartcloset.agent;

import java.util.*;
import java.io.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import com.smartcloset.garment.GarmentRepository;
import com.smartcloset.person.*;
import com.smartcloset.workflow.*;
import com.smartcloset.recognition.*;
import com.smartcloset.generation.GenerationService;
import com.smartcloset.processing.*;
import com.smartcloset.image.ProcessingException;
import org.springframework.http.HttpStatus;

/** Concrete, always-confirmed platform operations. No arbitrary SQL, files or URLs. */
@Service
public class AgentPlatformCommands {
    public static final Set<String> KINDS=Set.of("GARMENT_BATCH","GARMENT_WORKFLOW","TASK_CANCEL","PROFILE_EDIT","BASELINE_DELETE","OOTD_DELETE");
    @org.springframework.beans.factory.annotation.Autowired private com.smartcloset.security.ClosetModelPolicy modelPolicy;
    private final JdbcTemplate db;private final ObjectMapper json;private final GarmentRepository garments;private final WorkflowService workflows;private final RecognitionService recognition;private final GenerationService generation;private final ProcessingExecutor executor;private final ProcessingStore store;private final WearerProfiles profiles;private final PersonalRenders renders;private final TransactionTemplate tx;
    public AgentPlatformCommands(JdbcTemplate db,ObjectMapper json,GarmentRepository garments,WorkflowService workflows,RecognitionService recognition,GenerationService generation,ProcessingExecutor executor,ProcessingStore store,WearerProfiles profiles,PersonalRenders renders,PlatformTransactionManager manager){this.db=db;this.json=json;this.garments=garments;this.workflows=workflows;this.recognition=recognition;this.generation=generation;this.executor=executor;this.store=store;this.profiles=profiles;this.renders=renders;tx=new TransactionTemplate(manager);}
    private void lock(String owner){db.queryForObject("SELECT username FROM closet_user WHERE username=? FOR UPDATE",String.class,owner);}
    private GarmentRepository.View garment(String owner,String id){if(db.queryForObject("SELECT COUNT(*) FROM garment WHERE id=? AND owner_username=?",Integer.class,id,owner)!=1)throw AgentRepository.notFound();return garments.get(UUID.fromString(id));}
    private AgentCommands.Command get(String owner,String id){return db.query("SELECT * FROM agent_command WHERE id=? AND owner_username=?",(r,n)->new AgentCommands.Command(id,r.getString("kind"),json.readTree(r.getString("parameters_json")),r.getString("status"),true,r.getLong("expires_at"),r.getString("result_json")==null?json.nullNode():json.readTree(r.getString("result_json")),false,false),id,owner).stream().findFirst().orElseThrow(AgentRepository::notFound);}
    public AgentCommands.Command prepare(String owner,String kind,String key,JsonNode input){return tx.execute(t->{lock(owner);
        var prior=db.query("SELECT id FROM agent_command WHERE owner_username=? AND idempotency_key=?",(r,n)->r.getString(1),owner,key);if(!prior.isEmpty())return get(owner,prior.getFirst());
        if(!KINDS.contains(kind)||input==null||!input.isObject())throw AgentRepository.invalid("平台操作参数无效。");
        var p=(tools.jackson.databind.node.ObjectNode)input.deepCopy();
        switch(kind){
            case "GARMENT_BATCH" -> {
                if(!Set.of("BASIC","METADATA","TRASH","RESTORE").contains(p.path("operation").asText())||!p.path("targets").isArray()||p.path("targets").isEmpty()||p.path("targets").size()>100)throw AgentRepository.invalid("批量操作需选择 1 到 100 件及具体变化。");
                var ids=new HashSet<String>();var labels=new ArrayList<String>();
                for(var target:p.path("targets")){String id=target.path("id").asText();if(!ids.add(id))throw AgentRepository.invalid("批量目标不能重复。");var g=garment(owner,id);if(g.revision()!=target.path("revision").asLong(-1))throw changed();labels.add(g.name()==null?String.join(" ",g.effective().getOrDefault("category",List.of("未分类"))):g.name());}
                validateChanges(p);p.set("targetLabels",json.valueToTree(labels));p.put("targetLabel","批量修改 "+ids.size()+" 件衣物");
            }
            case "GARMENT_WORKFLOW" -> {
                var g=garment(owner,p.path("targetId").asText());if(g.revision()!=p.path("revision").asLong(-1)||g.status().equals("TRASHED"))throw changed();
                String type=p.path("type").asText();if(!Set.of("RECOGNIZE","GENERATE").contains(type))throw AgentRepository.invalid("仅支持明确启动衣物识别或图片增强。");
                if(type.equals("RECOGNIZE")){var prepared=recognition.prepare(json.treeToValue(p.path("request"),Recognition.Request.class));modelPolicy.checkRecognition(prepared.provider(),prepared.model());p.set("prepared",json.valueToTree(prepared));}
                else {var prepared=generation.prepare(json.treeToValue(p.path("request"),GenerationService.Request.class));modelPolicy.checkGeneration(prepared.provider(),prepared.model());p.set("prepared",json.valueToTree(prepared));}
                p.put("quotaUnits",1);p.put("targetLabel",(type.equals("RECOGNIZE")?"识别":"增强")+"衣物："+(g.name()==null?String.join(" ",g.effective().getOrDefault("category",List.of())):g.name()));
            }
            case "TASK_CANCEL" -> {
                String taskId=p.path("taskId").asText();
                if(db.queryForObject("SELECT COUNT(*) FROM image_render_job WHERE id=? AND owner_username=?",Integer.class,taskId,owner)==1){var job=renders.job(owner,taskId);if(!Set.of("QUEUED","WAITING_RESOURCE","RUNNING","UNKNOWN").contains(job.status()))throw changed();p.put("renderJob",true);p.put("taskKind",job.purpose());}
                else{var task=store.get(taskId);if(!Objects.equals(task.owner(),owner))throw AgentRepository.notFound();if(!Set.of("QUEUED","WAITING_QUOTA","RUNNING").contains(task.status()))throw changed();p.put("renderJob",false);p.put("taskKind",task.kind());}
                p.put("targetLabel","取消任务 "+taskId.substring(0,8));
            }
            case "PROFILE_EDIT" -> {var profile=profiles.get(owner);if(profile.revision()!=p.path("revision").asLong(-1))throw changed();p.put("targetLabel","修改账号个人资料");profilePatch(profile,p);}
            case "BASELINE_DELETE" -> {var b=renders.baseline(owner,p.path("targetId").asText());if(b.revision()!=p.path("revision").asLong(-1)||b.status().equals("DELETED"))throw changed();p.put("targetLabel","删除基准人物；已有 OOTD 保留");}
            case "OOTD_DELETE" -> {var j=renders.job(owner,p.path("targetId").asText());if(!j.purpose().equals("OOTD")||j.status().equals("DELETED"))throw changed();p.put("targetLabel","永久删除 OOTD；原照、人物和搭配保留");}
        }
        String id=UUID.randomUUID().toString();long now=System.currentTimeMillis();db.update("INSERT INTO agent_command(id,owner_username,kind,parameters_json,status,requires_confirmation,idempotency_key,created_at,expires_at) VALUES(?,?,?,?,'PREPARED',TRUE,?,?,?)",id,owner,kind,json.writeValueAsString(p),key,now,now+600000);return get(owner,id);
    });}
    private void validateChanges(JsonNode p){
        if(p.path("operation").asText().equals("BASIC")){if(!p.has("favorite")&&!p.has("name"))throw AgentRepository.invalid("请指定名称或收藏变化。");if(p.has("favorite")&&!p.path("favorite").isBoolean())throw AgentRepository.invalid("收藏格式无效。");if(p.has("name"))AgentRepository.clean(p.path("name").asText(),100);}
        if(p.path("operation").asText().equals("METADATA")){if(!p.path("fields").isObject()||p.path("fields").isEmpty())throw AgentRepository.invalid("请指定属性变化。");for(var e:p.path("fields").properties()){if(!e.getValue().isArray())throw AgentRepository.invalid("属性应为数组。");var values=new ArrayList<String>();e.getValue().forEach(v->values.add(v.asText()));RecognitionSchema.normalizeValues(RecognitionSchema.field(e.getKey()),values);}}
    }
    private WearerProfiles.Profile profilePatch(WearerProfiles.Profile before,JsonNode p){
        var node=(tools.jackson.databind.node.ObjectNode)json.valueToTree(before);var changes=p.path("fields");if(!changes.isObject()||changes.isEmpty())throw AgentRepository.invalid("请选择具体个人资料变化。");
        for(var e:changes.properties()){if(!Set.of("sex","heightCm","weightKg","bodyShape","measurements","preferences").contains(e.getKey()))throw AgentRepository.invalid("不支持此个人资料字段。");if(Set.of("measurements","preferences").contains(e.getKey())&&e.getValue().isObject()&&!e.getValue().isEmpty()){
            var merged=(tools.jackson.databind.node.ObjectNode)node.path(e.getKey()).deepCopy();
            for(var part:e.getValue().properties()){if(part.getValue().isNull())merged.remove(part.getKey());else merged.set(part.getKey(),part.getValue());}node.set(e.getKey(),merged);
        }else node.set(e.getKey(),e.getValue());}var result=json.treeToValue(node,WearerProfiles.Profile.class);WearerProfiles.validate(result);return result;
    }
    public AgentCommands.Command execute(String owner,String id){return tx.execute(t->{lock(owner);var c=get(owner,id);if(c.status().equals("SUCCEEDED"))return c;if(!c.status().equals("PREPARED")||c.expiresAt()<System.currentTimeMillis())throw changed();var p=c.parameters();Object result;
        switch(c.kind()){
            case "GARMENT_BATCH" -> {
                // Validate every target before the first write; the short transaction is all-or-nothing.
                for(var target:p.path("targets"))if(garment(owner,target.path("id").asText()).revision()!=target.path("revision").asLong(-1))throw changed();
                var results=new ArrayList<Object>();for(var target:p.path("targets")){UUID garmentId=UUID.fromString(target.path("id").asText());long revision=target.path("revision").asLong();var updated=switch(p.path("operation").asText()){
                    case "BASIC" -> garments.editBasic(owner,garmentId,new GarmentRepository.BasicEdit(revision,p.has("name")?p.path("name").asText():null,p.has("favorite")?p.path("favorite").asBoolean():null));
                    case "METADATA" -> garments.edit(garmentId,new GarmentRepository.Edit(revision,json.readValue(json.writeValueAsBytes(p.path("fields")),new tools.jackson.core.type.TypeReference<Map<String,List<String>>>(){}),null));
                    case "TRASH" -> garments.trash(garmentId,revision);
                    case "RESTORE" -> garments.restore(garmentId,revision);
                    default -> throw AgentRepository.invalid("批量操作无效。");};results.add(Map.of("id",updated.id(),"revision",updated.revision(),"status",updated.status()));}result=results;
            }
            case "GARMENT_WORKFLOW" -> {
                var g=garment(owner,p.path("targetId").asText());if(g.revision()!=p.path("revision").asLong(-1)||g.status().equals("TRASHED"))throw changed();Workflow.Step step;
                if(p.path("type").asText().equals("RECOGNIZE")){var request=json.treeToValue(p.path("request"),Recognition.Request.class);if(!json.readTree(json.writeValueAsBytes(recognition.prepare(request))).equals(p.path("prepared")))throw changed();step=Workflow.Step.recognize(request);}
                else {var request=json.treeToValue(p.path("request"),GenerationService.Request.class);if(!json.readTree(json.writeValueAsBytes(generation.prepare(request))).equals(p.path("prepared")))throw changed();step=new Workflow.Step(Workflow.Type.GENERATE,Workflow.Mode.IMAGE_TO_IMAGE,request,null);}
                try{var job=workflows.rerun(g.id(),UUID.fromString(id),new Workflow.RerunRequest(List.of(step)));result=Map.of("jobId",job.id(),"status",job.status());}catch(IOException e){throw AgentRepository.invalid("实物参考不可用。");}
            }
            case "TASK_CANCEL" -> {
                String taskId=p.path("taskId").asText();
                if(p.path("renderJob").asBoolean()){var job=renders.job(owner,taskId);if(!Set.of("QUEUED","WAITING_RESOURCE","RUNNING","UNKNOWN").contains(job.status()))throw changed();renders.cancel(owner,taskId);result=renders.job(owner,taskId);}
                else{var task=store.get(taskId);if(!Objects.equals(task.owner(),owner))throw AgentRepository.notFound();if(!Set.of("QUEUED","WAITING_QUOTA","RUNNING").contains(task.status()))throw changed();executor.cancel(task.id());result=Map.of("taskId",task.id(),"status",store.get(task.id()).status());}
            }
            case "PROFILE_EDIT" -> {var before=profiles.get(owner);if(before.revision()!=p.path("revision").asLong(-1))throw changed();var after=profiles.update(owner,profilePatch(before,p));result=Map.of("revision",after.revision());}
            case "BASELINE_DELETE" -> {renders.deleteBaseline(owner,p.path("targetId").asText(),p.path("revision").asLong());result=Map.of("deleted",true);}
            case "OOTD_DELETE" -> {var j=renders.job(owner,p.path("targetId").asText());if(!j.purpose().equals("OOTD"))throw changed();result=renders.delete(owner,j.id());}
            default -> throw AgentRepository.invalid("操作无效。");
        }
        db.update("UPDATE agent_command SET status='SUCCEEDED',result_json=? WHERE id=?",json.writeValueAsString(result),id);return get(owner,id);
    });}
    private static ProcessingException changed(){return new ProcessingException(HttpStatus.CONFLICT,"PLATFORM_COMMAND_CHANGED","操作对象、参数或版本已变化，请重新核对具体操作。");}
}
