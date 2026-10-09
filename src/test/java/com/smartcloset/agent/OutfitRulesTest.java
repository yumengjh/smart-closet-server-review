package com.smartcloset.agent;

import com.smartcloset.image.ProcessingException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OutfitRulesTest {
    private Map<String,List<String>> garment(String category) { return Map.of("category",List.of(category)); }
    private List<Map<String,List<String>>> clothes(String... categories) { return Arrays.stream(categories).map(this::garment).toList(); }
    @Test void dailyOutfitsAcceptSeparateAndOnePieceBodies() {
        for(var set:List.of(clothes("衬衫","西装裤"),clothes("T恤","半身裙","运动鞋"),clothes("吊带裙","斜挎包"),clothes("连体裤")))
            assertDoesNotThrow(()->OutfitRules.validate(set,true));
    }
    @Test void rejectsConflictingSlotsAndIncompleteBodies() {
        for(var set:List.of(clothes("衬衫","长裤","夹克","大衣"),clothes("连衣裙","衬衫"),clothes("连衣裙","半身裙"),
            clothes("T恤","衬衫","牛仔裤"),clothes("衬衫","长裤","短裤"),clothes("衬衫","长裤","开衫","外套"),
            clothes("夹克","运动鞋"),clothes("衬衫"),clothes("长裤"),clothes("未知款式","牛仔裤")))
            assertThrows(ProcessingException.class,()->OutfitRules.validate(set,true),set.toString());
    }
    @Test void ambiguousMetadataCannotBeSilentlyTreatedAsBodyOrAccessory() {
        assertThrows(ProcessingException.class,()->OutfitRules.validate(List.of(Map.of("category",List.of()),garment("牛仔裤")),true));
        assertThrows(ProcessingException.class,()->OutfitRules.validate(clothes("裙装","夹克"),true));
        assertEquals(false,OutfitRules.profile(Map.of()).get("categoryKnown"));
    }
    @Test void editsCanRemoveBodyPiecesButCannotIntroduceConflicts() {
        assertDoesNotThrow(()->OutfitRules.validate(clothes("夹克"),false));
        assertDoesNotThrow(()->OutfitRules.validate(List.of(),false));
        assertThrows(ProcessingException.class,()->OutfitRules.validate(clothes("连衣裙","衬衫"),false));
    }
    @Test void rejectsUnverifiedLayeringEvenWithApparentlyCompatibleCategoryAndReason() {
        for(var set:List.of(clothes("衬衫","长裤","夹克"),clothes("T恤","长裤","开衫"),clothes("吊带裙","夹克"))) {
            assertTrue(assertThrows(ProcessingException.class,()->OutfitRules.validate(set,true)).getMessage().contains("叠穿兼容证据"));
            assertThrows(ProcessingException.class,()->OutfitRules.validate(set,false));
        }
        assertEquals(false,OutfitRules.profile(garment("夹克")).get("layeringVerified"));
    }
    @Test void categoryMatchingDoesNotConfuseCompoundNames() {
        for(var category:List.of("针织开衫","cardigan","西装外套","牛仔外套"))assertEquals(OutfitRules.Role.OUTER,OutfitRules.role(garment(category)),category);
        for(var category:List.of("西装裤","牛仔裤","半身裙","skirt"))assertEquals(OutfitRules.Role.BOTTOM,OutfitRules.role(garment(category)),category);
        for(var category:List.of("连帽卫衣","带帽衬衫","hoodie"))assertEquals(OutfitRules.Role.TOP,OutfitRules.role(garment(category)),category);
        for(var category:List.of("背心裙","衬衫连衣裙","连体裤","dress"))assertEquals(OutfitRules.Role.ONE_PIECE,OutfitRules.role(garment(category)),category);
    }
    @Test void jewelryPositionsAreKnownAndDoNotCollide(){
        assertDoesNotThrow(()->OutfitRules.validate(clothes("衬衫","长裤","项链","耳环"),true));
        assertThrows(ProcessingException.class,()->OutfitRules.validate(clothes("衬衫","长裤","项链","项链"),true));
        assertThrows(ProcessingException.class,()->OutfitRules.validate(clothes("连衣裙","戒指"),true));
        var ring=new HashMap<String,List<String>>(garment("戒指"));ring.put("_accessoryPosition",List.of("HAND_LEFT"));assertDoesNotThrow(()->OutfitRules.validate(List.of(garment("连衣裙"),ring),true));
    }
    /** D43: the skirt/dress list from the full-body enhancement review, plus tights, hair accessories and hand-held objects. */
    @Test void fullBodyCategoriesMapToTheRightPositions() {
        var expected=new LinkedHashMap<String,OutfitRules.Role>();
        for(var c:List.of("A字半身裙","百褶裙","牛仔半身裙","包臀裙","鱼尾裙","蛋糕裙","伞裙","迷你裙","长款半身裙","JK制服裙","网纱裙","洛丽塔SK","SK","裙裤","打底裤"))expected.put(c,OutfitRules.Role.BOTTOM);
        for(var c:List.of("碎花连衣裙","吊带连衣裙","针织连衣裙","衬衫连衣裙","泡泡袖连衣裙","抹胸连衣裙","蕾丝连衣裙","小黑裙","礼服裙","洛丽塔OP","OP","水手服套装","西装套装","旗袍"))expected.put(c,OutfitRules.Role.ONE_PIECE);
        for(var c:List.of("背带裙","背带裤","洛丽塔JSK","JSK","吊带裤","overalls"))expected.put(c,OutfitRules.Role.OVERALL);
        for(var c:List.of("裤袜","连裤袜","丝袜","打底袜","过膝袜","长筒袜","短袜","袜子","连体袜","tights"))expected.put(c,OutfitRules.Role.SOCKS);
        for(var c:List.of("发箍","蝴蝶结发夹","发卡","发带","头饰","头花","KC","头纱","抓夹"))expected.put(c,OutfitRules.Role.HEADWEAR);
        for(var c:List.of("小熊玩偶","毛绒公仔","雨伞","阳伞","相机","拍立得","花束","团扇"))expected.put(c,OutfitRules.Role.HANDHELD);
        // Compound names that must not be taken for the new positions.
        expected.put("娃娃领衬衫",OutfitRules.Role.TOP); expected.put("娃娃鞋",OutfitRules.Role.SHOES); expected.put("玛丽珍鞋",OutfitRules.Role.SHOES);
        expected.put("袜靴",OutfitRules.Role.SHOES); expected.put("手拿包",OutfitRules.Role.BAG); expected.put("毛绒外套",OutfitRules.Role.OUTER); expected.put("毛绒帽",OutfitRules.Role.HAT);
        expected.forEach((category,role)->assertEquals(role,OutfitRules.role(garment(category)),category));
    }
    @Test void pinaforesNeedATopAndReplaceTheBottom() {
        for(var set:List.of(clothes("衬衫","背带裙"),clothes("T恤","背带裤","帆布鞋"),clothes("衬衫","洛丽塔JSK","过膝袜","发箍","玛丽珍鞋")))
            assertDoesNotThrow(()->OutfitRules.validate(set,true),set.toString());
        for(var set:List.of(clothes("背带裙"),clothes("衬衫","背带裙","半身裙"),clothes("背带裙","连衣裙"),clothes("衬衫","背带裙","背带裤"),
            clothes("衬衫","长裤","发箍","发夹"),clothes("衬衫","长裤","小熊玩偶","雨伞"),clothes("T恤","连裤袜")))
            assertThrows(ProcessingException.class,()->OutfitRules.validate(set,true),set.toString());
    }
}
