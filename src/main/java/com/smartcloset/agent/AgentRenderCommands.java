package com.smartcloset.agent;
import java.util.*;
import java.io.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import com.smartcloset.person.*;
import com.smartcloset.generation.GenerationService;
import com.smartcloset.image.ProcessingException;
import org.springframework.http.HttpStatus;
@Service
public class AgentRenderCommands {
    private final JdbcTemplate db;private final ObjectMapper json;private final PersonalRenders renders;private final WearerProfiles profiles;private final PrivateMedia media;private final RenderCapabilities capabilities;private final GenerationService generation;private final TransactionTemplate tx;
    public AgentRenderCommands(JdbcTemplate db,ObjectMapper json,PersonalRenders renders,WearerProfiles profiles,PrivateMedia media,RenderCapabilities capabilities,GenerationService generation,PlatformTransactionManager manager){this.db=db;this.json=json;this.renders=renders;this.profiles=profiles;this.media=media;this.capabilities=capabilities;this.generation=generation;tx=new TransactionTemplate(manager);}
    public Object options(String owner,String purpose){if(!Set.of("BASELINE","OOTD").contains(purpose))throw AgentRepository.invalid("生成用途无效。");var result=new LinkedHashMap<String,Object>();result.put("models",capabilities.list().stream().filter(c -> c.verified()&&c.purpose().equals(purpose)).toList());result.put("quotaUnits",1);if(purpose.equals("BASELINE")){result.put("profile",WearerProfiles.snapshot(profiles.get(owner)));result.put("photos",media.list(owner,"PHOTO"));}else result.put("baselines",renders.baselines(owner).stream().filter(b -> b.status().equals("CONFIRMED")).map(b -> Map.of("id",b.id(),"revision",b.revision(),"currentDefault",b.currentDefault())).toList());return result;}
    private AgentCommands.Command command(String owner,String id){return db.query("SELECT * FROM agent_command WHERE id=? AND owner_username=?",(r,n)->new AgentCommands.Command(id,r.getString("kind"),json.readTree(r.getString("parameters_json")),r.getString("status"),true,r.getLong("expires_at"),r.getString("result_json")==null?json.nullNode():json.readTree(r.getString("result_json")),false,false),id,owner).stream().findFirst().orElseThrow(AgentRepository::notFound);}
    public AgentCommands.Command prepare(String owner,String key,JsonNode input)throws IOException {
        if(!Set.of("BASELINE","OOTD").contains(input.path("purpose").asText()))throw AgentRepository.invalid("生成用途无效。");
        var previous=db.query("SELECT id FROM agent_command WHERE owner_username=? AND idempotency_key=?",(r,n)->r.getString(1),owner,key);if(!previous.isEmpty())return command(owner,previous.getFirst());
        var params=json.createObjectNode();String purpose=input.path("purpose").asText(),provider=input.path("provider").asText(),model=input.path("model").asText();params.put("purpose",purpose);params.put("provider",provider);params.put("model",model);params.put("quotaUnits",1);
        if(purpose.equals("BASELINE")) {
            var profile=profiles.get(owner);params.put("profileRevision",profile.revision());params.set("profile",json.valueToTree(WearerProfiles.snapshot(profile)));params.put("targetLabel","生成一张个人基准人物");
            String photo=input.path("photoId").asText("");if(!photo.isBlank()){var m=media.get(owner,UUID.fromString(photo));if(!m.state().equals("ACTIVE")||!m.purpose().equals("PHOTO"))throw AgentRepository.invalid("请选择本人有效全身照。");params.put("photoId",photo);params.put("photoEpoch",m.deletionEpoch());params.put("photoUrl",m.url());}
            var c=capabilities.require(provider,model,purpose,photo.isBlank()?0:1);params.put("capabilityVersion",c.version());params.put("providerRevision",generation.revision(provider));params.set("generationParameters",json.valueToTree(generation.prepare(new GenerationService.Request(provider,model,"准备参数校验，不提交生成",c.outputOptions())).parameters()));
        } else {
            var request=json.treeToValue(input,PersonalRenders.PreparationRequest.class);var preparation=renders.prepare(owner,request);params.put("preparationId",preparation.id());params.put("targetLabel","生成一张 OOTD 穿搭图");params.set("review",json.valueToTree(preparation.review()));params.set("profile",preparation.profile());
        }
        String id=UUID.randomUUID().toString();long now=System.currentTimeMillis();db.update("INSERT INTO agent_command(id,owner_username,kind,parameters_json,status,requires_confirmation,idempotency_key,created_at,expires_at) VALUES(?,?,?,?,'PREPARED',TRUE,?,?,?)",id,owner,purpose+"_GENERATION",json.writeValueAsString(params),key,now,now+600000);return command(owner,id);
    }
    public AgentCommands.Command execute(String owner,String id){return tx.execute(t -> {
        db.queryForObject("SELECT username FROM closet_user WHERE username=? FOR UPDATE",String.class,owner);var c=command(owner,id);if(c.status().equals("SUCCEEDED"))return c;if(!c.status().equals("PREPARED")||c.expiresAt()<System.currentTimeMillis())throw new ProcessingException(HttpStatus.CONFLICT,"COMMAND_EXPIRED","生成确认已过期或取消，请重新准备。");var p=c.parameters();PersonalRenders.Job result;
        if(c.kind().equals("BASELINE_GENERATION")) {
            UUID photo=p.has("photoId")?UUID.fromString(p.path("photoId").asText()):null;if(photo!=null&&!media.available(owner,photo,p.path("photoEpoch").asLong()))throw AgentRepository.invalid("原照已经删除，请重新准备。");var cap=capabilities.require(p.path("provider").asText(),p.path("model").asText(),"BASELINE",photo==null?0:1);if(cap.version()!=p.path("capabilityVersion").asLong()||!generation.revision(cap.provider()).equals(p.path("providerRevision").asText()))throw AgentRepository.invalid("模型能力已经变化，请重新准备。");var current=json.readTree(json.writeValueAsBytes(generation.prepare(new GenerationService.Request(cap.provider(),cap.model(),"准备参数校验，不提交生成",cap.outputOptions())).parameters()));if(!current.equals(p.path("generationParameters")))throw AgentRepository.invalid("默认生成参数已经变化，请重新准备。");result=renders.generateBaseline(owner,id,new PersonalRenders.BaselineRequest(p.path("profileRevision").asLong(),cap.provider(),cap.model(),photo));
        } else result=renders.submitOotd(owner,id,p.path("preparationId").asText());
        db.update("UPDATE agent_command SET status='SUCCEEDED',result_json=? WHERE id=?",json.writeValueAsString(result),id);return command(owner,id);
    });}
}
