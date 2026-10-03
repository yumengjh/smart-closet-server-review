package com.smartcloset.agent;

import com.smartcloset.image.ProcessingException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OutfitRulesTest {
    private Map<String,List<String>> garment(String category) { return Map.of("category",List.of(category)); }
    private List<Map<String,List<String>>> clothes(String... categories) { return Arrays.stream(categories).map(this::garment).toList(); }
    @Test void dailyOutfitsAcceptSeparateAndOnePieceBodies() {
        for(var set:List.of(clothes("衬衫","西装裤"),clothes("T恤","半身裙","针织开衫","运动鞋"),clothes("吊带裙","夹克","斜挎包"),clothes("连体裤")))
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
    @Test void categoryMatchingDoesNotConfuseCompoundNames() {
        for(var category:List.of("针织开衫","cardigan","西装外套","牛仔外套"))assertEquals(OutfitRules.Role.OUTER,OutfitRules.role(garment(category)),category);
        for(var category:List.of("西装裤","牛仔裤","半身裙","skirt"))assertEquals(OutfitRules.Role.BOTTOM,OutfitRules.role(garment(category)),category);
        for(var category:List.of("连帽卫衣","带帽衬衫","hoodie"))assertEquals(OutfitRules.Role.TOP,OutfitRules.role(garment(category)),category);
        for(var category:List.of("背心裙","衬衫连衣裙","连体裤","dress"))assertEquals(OutfitRules.Role.ONE_PIECE,OutfitRules.role(garment(category)),category);
    }
}
