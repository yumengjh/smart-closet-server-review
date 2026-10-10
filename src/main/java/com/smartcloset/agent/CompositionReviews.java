package com.smartcloset.agent;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.*;
import com.smartcloset.garment.GarmentRepository;
import com.smartcloset.outfit.OutfitRepository;
import com.smartcloset.workflow.WorkflowFiles;
import com.smartcloset.image.ProcessingException;
import org.springframework.http.HttpStatus;

@Service
public class CompositionReviews {
    public static final String VERSION="composition-v1";
    public record Request(UUID outfitId,Long revision,JsonNode scene,List<UUID> selectedGarmentIds,Map<String,String> wearRoles,Boolean multiLayer) {}
    public record Worn(UUID garmentId,long revision,String label,String role,UUID referenceAssetId,String position) {}
    public record Issue(String code,String message,String action) {}
    public record Slot(String role,String label,String state,List<Worn> items) {}
    public record Review(List<Worn> items,List<Slot> slots,List<Issue> blockers,List<Issue> warnings,String ruleVersion,String fingerprint,UUID outfitId,Long revision) {public boolean passed(){return blockers.isEmpty();}}
    private final JdbcTemplate db;private final GarmentRepository garments;private final OutfitRepository outfits;private final ObjectMapper json;
    public CompositionReviews(JdbcTemplate db,GarmentRepository garments,OutfitRepository outfits,ObjectMapper json){this.db=db;this.garments=garments;this.outfits=outfits;this.json=json;}
    public Review review(String owner,Request input) {
        if(input==null)throw AgentRepository.invalid("请选择要检查的搭配。");
        JsonNode scene=input.scene();
        if(input.outfitId()!=null){var saved=outfits.get(input.outfitId(),owner);if(input.revision()==null||saved.revision()!=input.revision())throw new ProcessingException(HttpStatus.CONFLICT,"OUTFIT_REVISION_CONFLICT","搭配已经变化，请保存并重新检查。");scene=json.valueToTree(saved.scene());}
        if(scene==null||!scene.path("elements").isArray()||scene.path("elements").size()>100)throw AgentRepository.invalid("画板内容无效。");
        var all=new LinkedHashSet<UUID>();for(var element:scene.path("elements"))if(!element.path("kind").asText().equals("PERSON_PART")) {try{all.add(UUID.fromString(element.path("garmentId").asText()));}catch(Exception e){throw AgentRepository.invalid("衣物编号无效。");}}
        var selected=input.selectedGarmentIds()==null?all:new LinkedHashSet<>(input.selectedGarmentIds());
        if(!all.containsAll(selected))throw AgentRepository.invalid("本次选中的衣物不在来源搭配中。");
        var blockers=new ArrayList<Issue>();var warnings=new ArrayList<Issue>();var worn=new ArrayList<Worn>();var fields=new ArrayList<Map<String,List<String>>>();
        for(UUID id:selected) {
            if(db.queryForObject("SELECT COUNT(*) FROM garment WHERE id=? AND owner_username=?",Integer.class,id.toString(),owner)!=1)throw AgentRepository.notFound();var garment=garments.get(id);
            if(!garment.status().equals("ACTIVE"))blockers.add(new Issue("GARMENT_INACTIVE","衣物已回收或未入柜："+id,"更换或排除衣物"));
            var f=new LinkedHashMap<>(garment.effective());f.put("_layeringConfirmed",List.of("fit","thickness","wearingModes").stream().allMatch(k -> garment.overrides().containsKey(k)&&!garment.overrides().get(k).isEmpty())?List.of("yes"):List.of());
            if(Boolean.TRUE.equals(input.multiLayer()))f.put("_multiLayer",List.of("yes"));
            OutfitRules.markWornAlone(f,garment.name());
            String requested=input.wearRoles()==null?null:input.wearRoles().get(id.toString());
            var actual=OutfitRules.actualRole(f);
            if(requested!=null&&requested.startsWith("ACCESSORY:")&&actual==OutfitRules.Role.ACCESSORY){
                String position=requested.substring(10);
                if(!Set.of("EAR","NECK","WRIST","HAND_LEFT","HAND_RIGHT","TIE").contains(position))throw AgentRepository.invalid("配饰位置无效。");
                String inferred=OutfitRules.accessoryPosition(f);
                if(!inferred.equals("UNKNOWN")&&!inferred.equals(position))blockers.add(new Issue("ACCESSORY_POSITION_CONFLICT","配饰品类与指定位置不符。","核对佩戴位置"));
                else f.put("_accessoryPosition",List.of(position));
            }else if(requested!=null&&!requested.equals(actual.name())&&!requested.equals(OutfitRules.role(f).name())) {
                if(requested.equals("INNER")&&actual==OutfitRules.Role.TOP&&Boolean.TRUE.equals(input.multiLayer()))f.put("_wearRole",List.of("INNER"));
                else blockers.add(new Issue("ROLE_CONFLICT","指定穿法与真实品类不符，不能改变显示角色来绕过检查。","核对穿法或品类"));
            }
            UUID reference=garment.images().stream().filter(i -> !i.aiGenerated()&&i.kind().equals("BASE")).map(GarmentRepository.ImageOption::id).findFirst().orElse(garment.sourceAssetId());
            if(reference==null)blockers.add(new Issue("REFERENCE_MISSING","衣物没有可用的实物参考。","选择可用图片"));
            String label=garment.name()!=null?garment.name():String.join(" ",f.getOrDefault("category",List.of("未分类")));
            worn.add(new Worn(id,garment.revision(),label,OutfitRules.role(f).name(),reference,actual==OutfitRules.Role.ACCESSORY?OutfitRules.accessoryPosition(f):OutfitRules.role(f).name()));fields.add(f);
        }
        try{OutfitRules.validate(fields,true);}catch(ProcessingException e){blockers.add(new Issue("STRUCTURE_INVALID",e.getMessage(),"添加主体、移除冲突物品或核对资料"));}
        if(worn.stream().noneMatch(w -> w.role().equals("SHOES")))warnings.add(new Issue("NO_SHOES","未选择鞋，继续生成时人物不带鞋，也不会保留基准人物原鞋。","可继续或添加鞋"));
        var counts=new HashMap<String,List<Worn>>();for(var w:worn)counts.computeIfAbsent(w.role(),k->new ArrayList<>()).add(w);
        boolean onePiece=counts.containsKey("ONE_PIECE");var slots=new ArrayList<Slot>();
        var labels=Map.ofEntries(Map.entry("TOP","上身主体"),Map.entry("BOTTOM","下身主体"),Map.entry("ONE_PIECE","连体主体"),Map.entry("OVERALL","背带裙 / 背带裤"),Map.entry("HEADWEAR","头饰"),Map.entry("HANDHELD","手持物"),Map.entry("INNER","内层 / 打底"),Map.entry("OUTER","外搭"),Map.entry("SHOES","鞋"),Map.entry("HAT","帽子"),Map.entry("GLASSES","眼镜"),Map.entry("ACCESSORY","首饰 / 配饰"),Map.entry("BAG","包"),Map.entry("SCARF","围巾"),Map.entry("BELT","腰带"),Map.entry("SOCKS","袜子 / 裤袜"),Map.entry("GLOVES","手套"),Map.entry("OTHER","待核对物品"));
        for(var role:OutfitRules.Role.values()) {
            var items=counts.getOrDefault(role.name(),List.of());String state=items.isEmpty()?"MISSING":"PRESENT";
            if(role==OutfitRules.Role.OTHER&&!items.isEmpty())state="UNKNOWN";
            else if(role==OutfitRules.Role.ACCESSORY&&items.stream().anyMatch(w->w.position().equals("UNKNOWN")))state="UNKNOWN";
            else if(role==OutfitRules.Role.ACCESSORY&&items.stream().map(Worn::position).distinct().count()!=items.size())state="CONFLICT";
            else if(role!=OutfitRules.Role.ACCESSORY&&items.size()>1)state="CONFLICT";
            else if(onePiece&&Set.of(OutfitRules.Role.TOP,OutfitRules.Role.BOTTOM,OutfitRules.Role.OVERALL).contains(role)&&!items.isEmpty())state="CONFLICT";
            else if(role==OutfitRules.Role.OVERALL&&!items.isEmpty()&&counts.containsKey("BOTTOM"))state="CONFLICT";
            slots.add(new Slot(role.name(),labels.getOrDefault(role.name(),role.name()),state,items));
        }
        String fingerprint=WorkflowFiles.hash(json.writeValueAsBytes(Map.of("items",worn,"ruleVersion",VERSION,"multiLayer",Boolean.TRUE.equals(input.multiLayer()),"outfitRevision",input.revision()==null?-1:input.revision())));
        return new Review(worn,slots,blockers,warnings,VERSION,fingerprint,input.outfitId(),input.revision());
    }
}
