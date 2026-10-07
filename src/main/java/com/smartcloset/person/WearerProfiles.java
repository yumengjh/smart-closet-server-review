package com.smartcloset.person;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import com.smartcloset.image.ProcessingException;
import org.springframework.http.HttpStatus;

@Service
public class WearerProfiles {
    public record Profile(long revision,String sex,Double heightCm,Double weightKg,String bodyShape,Map<String,Double> measurements,Map<String,String> preferences,String defaultBaselineId) {}
    public record Snapshot(String sex,double heightCm,double weightKg,String bodyShape,Map<String,Double> measurements,Map<String,String> preferences,Map<String,String> sources,long profileRevision,String defaultsVersion) {}
    private final JdbcTemplate db;private final ObjectMapper json;
    public WearerProfiles(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
    public Profile get(String owner){var rows=db.query("SELECT * FROM wearer_profile WHERE owner_username=?",(r,n)->new Profile(r.getLong("revision"),r.getString("sex"),(Double)r.getObject("height_cm"),(Double)r.getObject("weight_kg"),r.getString("body_shape"),json.readValue(r.getString("measurements_json"),new tools.jackson.core.type.TypeReference<Map<String,Double>>() {}),json.readValue(r.getString("preferences_json"),new tools.jackson.core.type.TypeReference<Map<String,String>>() {}),r.getString("default_baseline_id")),owner);return rows.isEmpty()?new Profile(0,null,null,null,null,Map.of(),Map.of(),null):rows.getFirst();}
    public Profile update(String owner,Profile p){
        validate(p);
        synchronized(this){
            if(db.queryForObject("SELECT COUNT(*) FROM wearer_profile WHERE owner_username=?",Integer.class,owner)==0){if(p.revision()!=0)throw conflict();db.update("INSERT INTO wearer_profile(owner_username,revision,updated_at_ms) VALUES(?,0,?)",owner,System.currentTimeMillis());}
            if(db.update("UPDATE wearer_profile SET sex=?,height_cm=?,weight_kg=?,body_shape=?,measurements_json=?,preferences_json=?,revision=revision+1,updated_at_ms=? WHERE owner_username=? AND revision=?",p.sex(),p.heightCm(),p.weightKg(),p.bodyShape(),json.writeValueAsString(p.measurements()),json.writeValueAsString(p.preferences()),System.currentTimeMillis(),owner,p.revision())!=1)throw conflict();
        }return get(owner);
    }
    public static void validate(Profile p){
        if(p==null||p.revision()<0)throw ProcessingException.invalid("个人资料缺少 revision。");
        if(p.sex()!=null&&!Set.of("MALE","FEMALE").contains(p.sex()))throw ProcessingException.invalid("性别请选择男或女。");
        positive(p.heightCm(),"身高");positive(p.weightKg(),"体重");if(p.heightCm()!=null&&p.heightCm()>300||p.weightKg()!=null&&p.weightKg()>600)throw ProcessingException.invalid("请核对身高 cm 和体重 kg 单位。");
        if(p.bodyShape()!=null&&p.bodyShape().length()>500)throw ProcessingException.invalid("身材描述最多 500 字。");
        if(p.measurements()==null||p.preferences()==null||p.measurements().size()>10||p.preferences().size()>12)throw ProcessingException.invalid("个人资料格式无效。");
        for(var e:p.measurements().entrySet()){if(!Set.of("chest","waist","hips","shoulder","inseam").contains(e.getKey()))throw ProcessingException.invalid("围度类型无效。");if(e.getValue()==null)throw ProcessingException.invalid("未填写的围度请移除该字段。");positive(e.getValue(),"围度");if(e.getValue()>300)throw ProcessingException.invalid("围度请使用 cm，并核对数值。");}
        for(var e:p.preferences().entrySet())if(!Set.of("fit","style","dislikes","thermal").contains(e.getKey())||e.getValue()==null||e.getValue().length()>500)throw ProcessingException.invalid("偏好格式无效。");
    }
    public static Snapshot snapshot(Profile p) {
        if(p.sex()==null||!Set.of("MALE","FEMALE").contains(p.sex()))throw new ProcessingException(HttpStatus.CONFLICT,"SEX_REQUIRED","请先在个人资料选择男或女，再生成基准人物。");
        positive(p.heightCm(),"身高");positive(p.weightKg(),"体重");
        double height=p.heightCm()==null?(p.sex().equals("MALE")?170:160):p.heightCm();
        double weight=p.weightKg()==null?Math.round(22*Math.pow(height/100,2)*10)/10d:p.weightKg();
        String shape=p.bodyShape()==null||p.bodyShape().isBlank()?"标准身材":p.bodyShape();
        var sources=Map.of("sex","USER_REPORTED","heightCm",p.heightCm()==null?"TEMPLATE_DEFAULT":"USER_REPORTED","weightKg",p.weightKg()==null?"DERIVED_DEFAULT":"USER_REPORTED","bodyShape",p.bodyShape()==null||p.bodyShape().isBlank()?"TEMPLATE_DEFAULT":"USER_REPORTED");
        return new Snapshot(p.sex(),height,weight,shape,p.measurements(),p.preferences(),sources,p.revision(),"baseline-defaults-bmi22-v1");
    }
    private static void positive(Double v,String label){if(v!=null&&(!Double.isFinite(v)||v<=0))throw ProcessingException.invalid(label+"必须为正数，请核对单位。");}
    private static ProcessingException conflict(){return new ProcessingException(HttpStatus.CONFLICT,"PROFILE_REVISION_CONFLICT","资料已经变化，你的输入仍保留，请重新核对。");}
}
