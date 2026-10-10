package com.smartcloset.agent;

import java.util.*;
import java.util.regex.Pattern;

/** Conservative everyday outfits; these checks apply to Agent suggestions, not the manual collage editor. */
public final class OutfitRules {
    private OutfitRules() {}
    /**
     * OVERALL: pinafore-style piece worn over a top (背带裙, JSK, 背带裤); HEADWEAR: hair accessories (发箍, 发夹, KC);
     * HANDHELD: things carried in the hand that are not bags (玩偶, 伞, 相机). SOCKS covers tights (裤袜, 连裤袜) too.
     */
    public enum Role { TOP, INNER, OUTER, BOTTOM, ONE_PIECE, OVERALL, SHOES, HAT, HEADWEAR, BAG, HANDHELD, GLASSES, SCARF, BELT, SOCKS, GLOVES, ACCESSORY, OTHER }
    /** Latin shorthand (OP, SK, JSK, KC) only counts when not glued to other Latin letters, so "JSK" is never read as "SK". */
    private static final String L = "(?<![A-Za-z])", R = "(?![A-Za-z])";
    private static boolean matches(String text, String pattern) {
        return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(text).find();
    }
    /**
     * Marks an apron-style pinafore (围裙式背带裙/背带裤, by name or category) as worn alone: it covers chest and back, so
     * it forms an outfit by itself and never goes over a shirt (2026-10-10 feedback). Callers that know the garment name
     * call this before {@link #role}.
     */
    public static void markWornAlone(Map<String,List<String>> fields, String name) {
        String text=(name==null?"":name)+" "+String.join(" ",fields.getOrDefault("category",List.of()));
        if(text.contains("围裙")&&actualRole(fields)==Role.OVERALL)fields.put("_wearRole",List.of("ALONE"));
    }
    public static Role role(Map<String,List<String>> fields) {
        Role actual=actualRole(fields);var wear=fields.getOrDefault("_wearRole",List.of());
        if(actual==Role.TOP&&wear.contains("INNER"))return Role.INNER;
        // An apron-style pinafore (围裙式背带裙) covers the upper body and is worn alone, like a dress (2026-10-10 feedback).
        if(actual==Role.OVERALL&&wear.contains("ALONE"))return Role.ONE_PIECE;
        return actual;
    }
    public static Role actualRole(Map<String,List<String>> fields) {
        var categories=fields.getOrDefault("category",List.of());if(categories.size()>1){var roles=new HashSet<Role>();for(String c:categories)roles.add(actualRole(Map.of("category",List.of(c))));return roles.size()==1?roles.iterator().next():Role.OTHER;}
        String name=String.join(" ",categories).trim();
        if(name.equals("裙装"))return Role.OTHER;
        // Order matters (D43): tights before trousers ("连裤袜" contains 裤), hand-held objects after skirts/collars/shoes/bags ("伞裙", "娃娃领", "手拿包").
        if(matches(name,"袜(?![靴鞋])|腿套|\\b(socks|tights|stockings|pantyhose)\\b"))return Role.SOCKS;
        if(matches(name,"发箍|发夹|发卡|发带|发饰|头花|头饰|头纱|头箍|抓夹|发圈|"+L+"KC"+R+"|\\b(headband|hair ?clip|hairpin|scrunchie)\\b"))return Role.HEADWEAR;
        if(matches(name,"背带裙|背带裤|吊带裤|"+L+"JSK"+R+"|\\b(pinafore|overalls|jumper ?skirt|dungarees)\\b"))return Role.OVERALL;
        if(matches(name,"连衣裙|背心裙|马甲裙|吊带裙|连体|连身|小黑裙|礼服|婚纱|旗袍|晚装|套装|"+L+"OP"+R+"|\\b(dress|jumpsuit|romper|gown)\\b"))return Role.ONE_PIECE;
        // A waistcoat is a layer worn over a top, not a top: 马甲 and material vests (牛仔背心, 针织背心) are OUTER. A bare
        // "背心" stays a tank top (TOP), its everyday meaning.
        if(matches(name,"马甲|(牛仔|西装|西服|针织|毛衣|毛线|羊毛|羽绒|皮|工装)背心|\\b(waistcoat|gilet)\\b"))return Role.OUTER;
        if(matches(name,"外套|夹克|西装外套|西装上衣|西服上衣|^西装$|大衣|风衣|羽绒|冲锋衣|罩衫|开衫|\\b(coat|jacket|blazer|cardigan|parka)\\b"))return Role.OUTER;
        if(matches(name,"长裤|短裤|牛仔裤|半身裙|下装|裤|裙|"+L+"SK"+R+"|\\b(pants|trousers|jeans|shorts|skirt)\\b"))return Role.BOTTOM;
        if(matches(name,"T恤|衬衫|卫衣|毛衣|针织|背心|上装|Polo|\\b(t-shirt|shirt|sweater|hoodie|sweatshirt|top|vest|blouse)\\b"))return Role.TOP;
        if(matches(name,"鞋|靴|\\b(shoes|boots|sneakers|sandals)\\b"))return Role.SHOES;
        if(matches(name,"帽|\\b(hat|cap|beret)\\b"))return Role.HAT;
        if(matches(name,"背包|手提包|斜挎包|包袋|包|\\b(bag|backpack|handbag)\\b"))return Role.BAG;
        if(matches(name,"玩偶|公仔|毛绒|玩具|娃娃|雨伞|阳伞|遮阳伞|折叠伞|^伞$|扇子|团扇|折扇|相机|拍立得|花束|手持|\\b(plush|teddy|umbrella|parasol|camera|bouquet|fan)\\b"))return Role.HANDHELD;
        if(matches(name,"眼镜|glasses"))return Role.GLASSES;
        if(matches(name,"围巾|scarf"))return Role.SCARF;
        if(matches(name,"腰带|belt"))return Role.BELT;
        if(matches(name,"手套|gloves"))return Role.GLOVES;
        if(matches(name,"耳环|耳饰|项链|手链|手镯|手表|戒指|首饰|配饰|领带|\\b(scarf|belt|socks|jewelry|glasses|gloves|tie|accessory)\\b"))return Role.ACCESSORY;
        return Role.OTHER;
    }
    public static String accessoryPosition(Map<String,List<String>> fields) {
        String selected=fields.getOrDefault("_accessoryPosition",List.of()).stream().findFirst().orElse("");
        if(Set.of("EAR","NECK","WRIST","HAND_LEFT","HAND_RIGHT","TIE").contains(selected))return selected;
        String category=String.join(" ",fields.getOrDefault("category",List.of()));
        if(matches(category,"耳环|耳饰|earrings"))return "EAR";
        if(matches(category,"项链|necklace"))return "NECK";
        if(matches(category,"手链|手镯|手表|bracelet|watch"))return "WRIST";
        if(matches(category,"领带|tie"))return "TIE";
        return "UNKNOWN";
    }
    public static Map<String,Object> profile(Map<String,List<String>> fields) {
        Role role=role(fields);
        return Map.of("role",role,"layer",role==Role.OTHER?"UNKNOWN":role==Role.OUTER?"OUTER":Set.of(Role.TOP,Role.BOTTOM,Role.ONE_PIECE,Role.OVERALL).contains(role)?"BASE":"ACCESSORY",
            "layeringVerified",false,"wearingDataConfirmed",confirmed(fields),"layeringPolicy","EVIDENCE_FILTERED_CANDIDATES","categoryKnown",role!=Role.OTHER,"basis","由有效品类推导；不代表已知厚度、版型或可叠穿性");
    }
    public static boolean confirmed(Map<String,List<String>> fields) { return fields.getOrDefault("_layeringConfirmed",List.of()).contains("yes"); }
    public static boolean compatible(List<Map<String,List<String>>> garments) {
        var inner=garments.stream().filter(f->Set.of(Role.TOP,Role.ONE_PIECE).contains(role(f))).findFirst().orElse(Map.of());
        var outer=garments.stream().filter(f->role(f)==Role.OUTER).findFirst().orElse(Map.of());return compatiblePair(inner,outer);
    }
    public static boolean compatiblePair(Map<String,List<String>> inner,Map<String,List<String>> outer) {
        var thickness=List.of("薄","常规","厚");
        int inside=thickness.indexOf(inner.getOrDefault("thickness",List.of()).stream().findFirst().orElse(""));
        int outside=thickness.indexOf(outer.getOrDefault("thickness",List.of()).stream().findFirst().orElse(""));
        return confirmed(inner)&&confirmed(outer)&&inner.getOrDefault("wearingModes",List.of()).contains("可作内搭")
            &&outer.getOrDefault("wearingModes",List.of()).contains("可作外搭")
            &&inner.getOrDefault("fit",List.of()).stream().anyMatch(Set.of("修身","合体")::contains)
            &&outer.getOrDefault("fit",List.of()).contains("宽松")&&inside>=0&&inside<=1&&outside>=inside;
    }
    public static void validate(List<Map<String,List<String>>> garments, boolean requireComplete) {
        var counts=new EnumMap<Role,Integer>(Role.class);
        for(var garment:garments)counts.merge(role(garment),1,Integer::sum);
        if(counts.containsKey(Role.OTHER))throw AgentRepository.invalid("衣物品类缺失或无法确定穿着角色，请先完善品类，再生成搭配；不能猜测它是内搭还是外套。");
        for(Role role:List.of(Role.OUTER,Role.TOP,Role.BOTTOM,Role.ONE_PIECE,Role.OVERALL,Role.SHOES,Role.HAT,Role.HEADWEAR,Role.BAG,Role.HANDHELD,Role.GLASSES,Role.SCARF,Role.BELT,Role.SOCKS,Role.GLOVES,Role.INNER)) {
            if(counts.getOrDefault(role,0)>1)throw AgentRepository.invalid(switch(role) {
                case OUTER -> "一套日常搭配最多一件外搭，请移除多余外套或开衫后重新组合。";
                case TOP -> "一套基础搭配最多一件上装，不能把多个上装当作可任意叠穿。";
                case BOTTOM -> "一套基础搭配最多一件下装。";
                case ONE_PIECE -> "一套基础搭配最多一件连衣裙或连体衣。";
                case OVERALL -> "一套基础搭配最多一件背带裙或背带裤。";
                case HEADWEAR -> "一套基础搭配最多一件头饰。";
                case HANDHELD -> "一套基础搭配手里最多拿一件物品。";
                default -> "一套基础搭配不能重复鞋、帽子或包的穿着位置。";
            });
        }
        var accessorySlots=new HashSet<String>();
        for(var garment:garments)if(role(garment)==Role.ACCESSORY){
            String position=accessoryPosition(garment);
            if(position.equals("UNKNOWN"))throw AgentRepository.invalid("配饰佩戴位置未确认，请指定耳、颈、手腕、左手、右手或领带位置。");
            if(!accessorySlots.add(position))throw AgentRepository.invalid("同一配饰位置存在重复，请移除多余物品或明确不同位置。");
        }
        if(counts.containsKey(Role.INNER)){
            if(!counts.containsKey(Role.TOP)||counts.containsKey(Role.ONE_PIECE)||!garments.stream().allMatch(f -> f.getOrDefault("_multiLayer",List.of()).contains("yes")))throw AgentRepository.invalid("多层穿着需要明确开启，使用一件打底、一件主体及最多一件外搭。");
            var inner=garments.stream().filter(f -> role(f)==Role.INNER).findFirst().orElseThrow();var base=garments.stream().filter(f -> role(f)==Role.TOP).findFirst().orElseThrow();
            if(!compatiblePair(inner,base))throw AgentRepository.invalid("打底和主体缺少已核对的叠穿资料。");
        }
        if(counts.containsKey(Role.OVERALL)&&(counts.containsKey(Role.BOTTOM)||counts.containsKey(Role.ONE_PIECE)))
            throw AgentRepository.invalid("背带裙或背带裤已包含下装，不能再搭配其他下装或连衣裙。");
        if(requireComplete&&counts.containsKey(Role.OVERALL)&&!counts.containsKey(Role.TOP))
            throw AgentRepository.invalid("背带裙或背带裤需要搭配一件上装作为内搭。");
        if(counts.containsKey(Role.ONE_PIECE)&&(counts.containsKey(Role.TOP)||counts.containsKey(Role.BOTTOM)))
            throw AgentRepository.invalid("连衣裙或连体衣已构成主体，默认不能再混搭衬衫等上装或裤裙；请改用一件外搭，或单独组合上装和下装。");
        if(counts.containsKey(Role.OUTER)&&(counts.containsKey(Role.TOP)||counts.containsKey(Role.ONE_PIECE))&&!compatible(garments))
            throw AgentRepository.invalid("当前衣物资料缺少厚度、版型及具体叠穿兼容证据，不能自动将外套与主体衣物一起推荐。请移除外搭，改为单层主体搭配；用户指定必须保留时说明无法可靠组合，不猜测可叠穿。手动编辑仍可添加外套。");
        if(requireComplete&&!counts.containsKey(Role.ONE_PIECE)&&!(counts.containsKey(Role.TOP)&&(counts.containsKey(Role.BOTTOM)||counts.containsKey(Role.OVERALL))))
            throw AgentRepository.invalid("搭配缺少穿着主体：请使用一件上装加一件下装（或背带裙/背带裤），或一件连衣裙/连体衣；外套与配饰不能代替主体。库存不足时说明限制，不凑方案。");
    }
}
