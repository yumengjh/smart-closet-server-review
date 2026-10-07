package com.smartcloset.agent;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OutfitCandidatesTest {
    private OutfitCandidates.Item item(String id,String category) {return new OutfitCandidates.Item(id,Map.of("category",List.of(category)));}
    @Test void filtersBeforeModelSelectionAndKeepsRequiredGarments() {
        var inventory=List.of(item("shirt","衬衫"),item("pants","长裤"),item("dress","连衣裙"),item("outer","夹克"),item("shoe","运动鞋"));
        var choices=OutfitCandidates.generate(inventory,Set.of(),40);
        assertTrue(choices.contains(List.of("pants","shirt")));assertTrue(choices.contains(List.of("dress")));
        assertFalse(choices.stream().anyMatch(ids->ids.contains("outer")));
        assertFalse(choices.stream().anyMatch(ids->ids.contains("dress")&&ids.contains("shirt")));
        assertTrue(OutfitCandidates.generate(inventory,Set.of("shirt"),40).stream().allMatch(ids->ids.contains("shirt")));
        assertTrue(OutfitCandidates.generate(inventory,Set.of("outer"),40).isEmpty());
        assertEquals(2,OutfitCandidates.generate(inventory,Set.of(),2).size());
        assertThrows(RuntimeException.class,()->OutfitCandidates.generate(inventory,Set.of("foreign"),40));
    }
    @Test void confirmedConservativeLayeringCanBeReturnedButRecognitionSuggestionsCannot() {
        var inner=new HashMap<String,List<String>>(Map.of("category",List.of("衬衫"),"fit",List.of("合体"),"thickness",List.of("薄"),"wearingModes",List.of("可作内搭")));
        var outer=new HashMap<String,List<String>>(Map.of("category",List.of("夹克"),"fit",List.of("宽松"),"thickness",List.of("常规"),"wearingModes",List.of("可作外搭")));
        var pieces=List.of(new OutfitCandidates.Item("i",inner),new OutfitCandidates.Item("o",outer),item("b","长裤"));
        assertTrue(OutfitCandidates.generate(pieces,Set.of("o"),40).isEmpty());
        inner.put("_layeringConfirmed",List.of("yes"));outer.put("_layeringConfirmed",List.of("yes"));
        assertEquals(List.of(List.of("b","i","o")),OutfitCandidates.generate(pieces,Set.of("o"),40));
        outer.put("fit",List.of("修身"));assertTrue(OutfitCandidates.generate(pieces,Set.of("o"),40).isEmpty());
    }
    @Test void largeInventoryIsBoundedAndOldRequiredSubjectsAreNotDropped(){
        var inventory=new ArrayList<OutfitCandidates.Item>();for(int i=0;i<4000;i++){inventory.add(item("t"+i,"衬衫"));inventory.add(item("b"+i,"长裤"));}inventory.add(item("shoe","运动鞋"));
        var choices=assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),()->OutfitCandidates.generate(inventory,Set.of("t3999"),40));assertTrue(choices.size()<=40);assertTrue(choices.stream().allMatch(ids->ids.contains("t3999")));assertTrue(choices.stream().anyMatch(ids->ids.contains("shoe")));
    }
}
