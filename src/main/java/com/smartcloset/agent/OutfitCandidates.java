package com.smartcloset.agent;

import java.util.*;

/** Streams bounded structural candidates from the full lightweight inventory. */
final class OutfitCandidates {
    record Item(String id, Map<String,List<String>> fields) {}
    static List<List<String>> generate(List<Item> items, Set<String> required, int limit) {
        if(limit<=0)return List.of();
        var index=new LinkedHashMap<String,Item>();
        var byRole=new EnumMap<OutfitRules.Role,List<Item>>(OutfitRules.Role.class);
        for(var item:items){index.put(item.id(),item);byRole.computeIfAbsent(OutfitRules.role(item.fields()),r->new ArrayList<>()).add(item);}
        if(!index.keySet().containsAll(required))throw AgentRepository.invalid("指定衣物不在已入柜库存中。");
        var fixed=required.stream().map(index::get).toList();
        try{OutfitRules.validate(fixed.stream().map(Item::fields).toList(),false);}catch(com.smartcloset.image.ProcessingException invalid){return List.of();}
        var output=new ArrayList<List<String>>();var seen=new HashSet<String>();var bases=new ArrayList<List<Item>>();
        var extras=items.stream().filter(i -> !Set.of(OutfitRules.Role.TOP,OutfitRules.Role.BOTTOM,OutfitRules.Role.ONE_PIECE,OutfitRules.Role.OTHER).contains(OutfitRules.role(i.fields()))).toList();
        int baseLimit=extras.isEmpty()||limit<3?limit:Math.max(3,limit/2);
        var requiredOuter=fixed.stream().filter(i->OutfitRules.role(i.fields())==OutfitRules.Role.OUTER).findFirst();
        var tops=constrained(byRole.getOrDefault(OutfitRules.Role.TOP,List.of()),required);
        var bottoms=constrained(byRole.getOrDefault(OutfitRules.Role.BOTTOM,List.of()),required);
        if(requiredOuter.isPresent())tops=tops.stream().filter(i->OutfitRules.compatiblePair(i.fields(),requiredOuter.get().fields())).toList();
        boolean fixedOne=fixed.stream().anyMatch(i->OutfitRules.role(i.fields())==OutfitRules.Role.ONE_PIECE);
        boolean fixedSeparates=fixed.stream().anyMatch(i->Set.of(OutfitRules.Role.TOP,OutfitRules.Role.BOTTOM).contains(OutfitRules.role(i.fields())));
        if(fixedOne)tops=List.of();
        for(var dress:constrained(byRole.getOrDefault(OutfitRules.Role.ONE_PIECE,List.of()),required)){
            if(fixedSeparates||requiredOuter.isPresent()&&!OutfitRules.compatiblePair(dress.fields(),requiredOuter.get().fields()))continue;
            collect(List.of(dress),fixed,required,output,seen,bases);
            if(output.size()>=baseLimit)break;
        }
        // Required subjects reduce the axes before traversal; no NxM intermediate list is allocated.
        outer:for(int round=0;round<bottoms.size();round++)for(int t=0;t<tops.size();t++){
            collect(List.of(tops.get(t),bottoms.get((t+round)%bottoms.size())),fixed,required,output,seen,bases);
            if(output.size()>=baseLimit)break outer;
        }
        if(output.size()>=limit)return output;
        // Shoes first, then the other optional positions. Different subjects precede variations.
        var sortedExtras=new ArrayList<>(extras);sortedExtras.sort(Comparator.comparingInt(i->OutfitRules.role(i.fields())==OutfitRules.Role.SHOES?0:1));
        for(var extra:sortedExtras)for(var base:bases){
            if(base.contains(extra))continue;var option=new ArrayList<>(base);option.add(extra);add(option,required,output,seen);
            if(output.size()>=limit)return output;
        }
        return output;
    }
    private static List<Item> constrained(List<Item> choices,Set<String> required){var fixed=choices.stream().filter(i->required.contains(i.id())).toList();return fixed.isEmpty()?choices:fixed;}
    private static void collect(List<Item> body,List<Item> fixed,Set<String> required,List<List<String>> output,Set<String> seen,List<List<Item>> bases){
        var base=new ArrayList<>(body);for(var item:fixed)if(!base.contains(item))base.add(item);
        int before=output.size();add(base,required,output,seen);if(output.size()>before)bases.add(base);
    }
    private static void add(List<Item> items,Set<String> required,List<List<String>> output,Set<String> seen){
        if(items.size()>12)return;
        try{OutfitRules.validate(items.stream().map(Item::fields).toList(),true);}catch(com.smartcloset.image.ProcessingException invalid){return;}
        var ids=items.stream().map(Item::id).sorted().toList();if(ids.containsAll(required)&&seen.add(ids.toString()))output.add(ids);
    }
}
