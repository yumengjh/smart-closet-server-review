package com.smartcloset.person;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import com.smartcloset.generation.GenerationService;
import com.smartcloset.image.ProcessingException;
import org.springframework.http.HttpStatus;
@Service
public class RenderCapabilities {
    public record Capability(String provider,String model,String purpose,int maxReferences,boolean verified,long version,Long verifiedAt,String providerRevision,String notes,Map<String,Object> outputOptions) {
        public Capability(String provider,String model,String purpose,int maxReferences,boolean verified,long version,Long verifiedAt,String providerRevision,String notes){this(provider,model,purpose,maxReferences,verified,version,verifiedAt,providerRevision,notes,Map.of());}
        public int outputCount(){return 1;}
    }
    @org.springframework.beans.factory.annotation.Autowired private tools.jackson.databind.ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired private com.smartcloset.security.ClosetModelPolicy modelPolicy;
    private final JdbcTemplate db;private final GenerationService generation;
    public RenderCapabilities(JdbcTemplate db,GenerationService generation){this.db=db;this.generation=generation;}
    public List<Capability> list(){return db.query("SELECT * FROM render_model_capability ORDER BY provider,model,purpose",(r,n)->new Capability(r.getString("provider"),r.getString("model"),r.getString("purpose"),r.getInt("max_references"),r.getBoolean("verified"),r.getLong("version"),(Long)r.getObject("verified_at_ms"),r.getString("provider_revision"),r.getString("notes"),json.readValue(r.getString("output_options_json"),new tools.jackson.core.type.TypeReference<Map<String,Object>>(){})));}
    public Capability require(String provider,String model,String purpose,int count){
        modelPolicy.checkGeneration(provider,model);
        var c=list().stream().filter(v -> v.provider().equals(provider)&&v.model().equals(model)&&v.purpose().equals(purpose)&&v.verified()).findFirst().orElseThrow(()->new ProcessingException(HttpStatus.SERVICE_UNAVAILABLE,"RENDER_MODEL_UNVERIFIED","此用途尚无已验证模型；资料和免费穿搭检查仍可使用。"));
        if(count>c.maxReferences())throw new ProcessingException(HttpStatus.CONFLICT,"REFERENCE_LIMIT","模型参考数量不足，请更换已验证方案；不会丢弃物品或自动拼图。");
        if(!Objects.equals(c.providerRevision(),generation.revision(provider)))throw new ProcessingException(HttpStatus.CONFLICT,"RENDER_CAPABILITY_CHANGED","模型网关已经变化，需要重新验证此用途。");return c;
    }
    public Capability update(Capability c){
        if(c==null||!Set.of("BASELINE","OOTD").contains(c.purpose())||c.maxReferences()<1||c.maxReferences()>20||c.notes()==null||c.notes().length()>1000)throw ProcessingException.invalid("能力记录格式无效。");
        String revision=generation.revision(c.provider());var outputOptions=c.outputOptions()==null?Map.<String,Object>of():c.outputOptions();generation.prepare(new GenerationService.Request(c.provider(),c.model(),"能力配置格式校验，不提交请求",outputOptions));
        var previous=list().stream().filter(v -> v.provider().equals(c.provider())&&v.model().equals(c.model())&&v.purpose().equals(c.purpose())).findFirst();
        if(previous.isPresent()&&previous.get().version()!=c.version())throw new ProcessingException(HttpStatus.CONFLICT,"CAPABILITY_REVISION_CONFLICT","能力记录已变化。");
        if(c.verified()&&c.notes().isBlank())throw ProcessingException.invalid("启用已验证用途需记录人工验证证据，不可仅依据模型名称。");
        db.update("MERGE INTO render_model_capability KEY(provider,model,purpose) VALUES(?,?,?,?,?,?,?,?,?,?)",c.provider(),c.model(),c.purpose(),c.maxReferences(),c.verified(),previous.map(v->v.version()+1).orElse(1L),c.verified()?System.currentTimeMillis():null,revision,c.notes(),json.writeValueAsString(outputOptions));
        return list().stream().filter(v -> v.provider().equals(c.provider())&&v.model().equals(c.model())&&v.purpose().equals(c.purpose())).findFirst().orElseThrow();
    }
}
