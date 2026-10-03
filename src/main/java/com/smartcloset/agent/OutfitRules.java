package com.smartcloset.agent;

import java.util.*;
import java.util.regex.Pattern;

/** Conservative everyday outfits; these checks apply to Agent suggestions, not the manual collage editor. */
final class OutfitRules {
    private OutfitRules() {}
    enum Role { TOP, OUTER, BOTTOM, ONE_PIECE, SHOES, HAT, BAG, ACCESSORY, OTHER }
    private static boolean matches(String text, String pattern) {
        return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(text).find();
    }
    static Role role(Map<String,List<String>> fields) {
        String name=String.join(" ",fields.getOrDefault("category",List.of())).trim();
        if(name.equals("裙装"))return Role.OTHER;
        if(matches(name,"连衣裙|背心裙|吊带裙|连体|连身|\\b(dress|jumpsuit|romper)\\b"))return Role.ONE_PIECE;
        if(matches(name,"外套|夹克|西装外套|西装上衣|西服上衣|^西装$|大衣|风衣|羽绒|冲锋衣|罩衫|开衫|\\b(coat|jacket|blazer|cardigan|parka)\\b"))return Role.OUTER;
        if(matches(name,"长裤|短裤|牛仔裤|半身裙|下装|裤|裙|\\b(pants|trousers|jeans|shorts|skirt)\\b"))return Role.BOTTOM;
        if(matches(name,"T恤|衬衫|卫衣|毛衣|针织|背心|上装|马甲|Polo|\\b(t-shirt|shirt|sweater|hoodie|sweatshirt|top|vest)\\b"))return Role.TOP;
        if(matches(name,"鞋|靴|\\b(shoes|boots|sneakers|sandals)\\b"))return Role.SHOES;
        if(matches(name,"帽|\\b(hat|cap|beret)\\b"))return Role.HAT;
        if(matches(name,"背包|手提包|斜挎包|包袋|包|\\b(bag|backpack|handbag)\\b"))return Role.BAG;
        if(matches(name,"围巾|腰带|袜|首饰|眼镜|配饰|手套|领带|\\b(scarf|belt|socks|jewelry|glasses|gloves|tie|accessory)\\b"))return Role.ACCESSORY;
        return Role.OTHER;
    }
    static Map<String,Object> profile(Map<String,List<String>> fields) {
        Role role=role(fields);
        return Map.of("role",role,"layer",role==Role.OTHER?"UNKNOWN":role==Role.OUTER?"OUTER":Set.of(Role.TOP,Role.BOTTOM,Role.ONE_PIECE).contains(role)?"BASE":"ACCESSORY",
            "layeringVerified",false,"wearingDataConfirmed",confirmed(fields),"layeringPolicy","EVIDENCE_FILTERED_CANDIDATES","categoryKnown",role!=Role.OTHER,"basis","由有效品类推导；不代表已知厚度、版型或可叠穿性");
    }
    static boolean confirmed(Map<String,List<String>> fields) { return fields.getOrDefault("_layeringConfirmed",List.of()).contains("yes"); }
    static boolean compatible(List<Map<String,List<String>>> garments) {
        var inner=garments.stream().filter(f->Set.of(Role.TOP,Role.ONE_PIECE).contains(role(f))).findFirst().orElse(Map.of());
        var outer=garments.stream().filter(f->role(f)==Role.OUTER).findFirst().orElse(Map.of());
        var thickness=List.of("薄","常规","厚");
        int inside=thickness.indexOf(inner.getOrDefault("thickness",List.of()).stream().findFirst().orElse(""));
        int outside=thickness.indexOf(outer.getOrDefault("thickness",List.of()).stream().findFirst().orElse(""));
        return confirmed(inner)&&confirmed(outer)&&inner.getOrDefault("wearingModes",List.of()).contains("可作内搭")
            &&outer.getOrDefault("wearingModes",List.of()).contains("可作外搭")
            &&inner.getOrDefault("fit",List.of()).stream().anyMatch(Set.of("修身","合体")::contains)
            &&outer.getOrDefault("fit",List.of()).contains("宽松")&&inside>=0&&inside<=1&&outside>=inside;
    }
    static void validate(List<Map<String,List<String>>> garments, boolean requireComplete) {
        var counts=new EnumMap<Role,Integer>(Role.class);
        for(var garment:garments)counts.merge(role(garment),1,Integer::sum);
        if(counts.containsKey(Role.OTHER))throw AgentRepository.invalid("衣物品类缺失或无法确定穿着角色，请先完善品类，再生成搭配；不能猜测它是内搭还是外套。");
        for(Role role:List.of(Role.OUTER,Role.TOP,Role.BOTTOM,Role.ONE_PIECE,Role.SHOES,Role.HAT,Role.BAG)) {
            if(counts.getOrDefault(role,0)>1)throw AgentRepository.invalid(switch(role) {
                case OUTER -> "一套日常搭配最多一件外搭，请移除多余外套或开衫后重新组合。";
                case TOP -> "一套基础搭配最多一件上装，不能把多个上装当作可任意叠穿。";
                case BOTTOM -> "一套基础搭配最多一件下装。";
                case ONE_PIECE -> "一套基础搭配最多一件连衣裙或连体衣。";
                default -> "一套基础搭配不能重复鞋、帽子或包的穿着位置。";
            });
        }
        if(counts.containsKey(Role.ONE_PIECE)&&(counts.containsKey(Role.TOP)||counts.containsKey(Role.BOTTOM)))
            throw AgentRepository.invalid("连衣裙或连体衣已构成主体，默认不能再混搭衬衫等上装或裤裙；请改用一件外搭，或单独组合上装和下装。");
        if(counts.containsKey(Role.OUTER)&&(counts.containsKey(Role.TOP)||counts.containsKey(Role.ONE_PIECE))&&!compatible(garments))
            throw AgentRepository.invalid("当前衣物资料缺少厚度、版型及具体叠穿兼容证据，不能自动将外套与主体衣物一起推荐。请移除外搭，改为单层主体搭配；用户指定必须保留时说明无法可靠组合，不猜测可叠穿。手动编辑仍可添加外套。");
        if(requireComplete&&!counts.containsKey(Role.ONE_PIECE)&&!(counts.containsKey(Role.TOP)&&counts.containsKey(Role.BOTTOM)))
            throw AgentRepository.invalid("搭配缺少穿着主体：请使用一件上装加一件下装，或一件连衣裙/连体衣；外套与配饰不能代替主体。库存不足时说明限制，不凑方案。");
    }
}
