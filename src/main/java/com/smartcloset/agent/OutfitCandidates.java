package com.smartcloset.agent;

import java.util.*;

/** Finite, deterministic structural candidates. No model or provider calls. */
final class OutfitCandidates {
    record Item(String id, Map<String,List<String>> fields) {}
    static List<List<String>> generate(List<Item> items,Set<String> required,int limit) {
        var byRole=new EnumMap<OutfitRules.Role,List<Item>>(OutfitRules.Role.class);
        for(var item:items)byRole.computeIfAbsent(OutfitRules.role(item.fields()),r->new ArrayList<>()).add(item);
        if(!items.stream().map(Item::id).collect(java.util.stream.Collectors.toSet()).containsAll(required))throw AgentRepository.invalid("指定衣物不在已入柜库存中。");
        var bodies=new ArrayList<List<Item>>();
        bodies.addAll(byRole.getOrDefault(OutfitRules.Role.ONE_PIECE,List.of()).stream().map(List::of).toList());
        var tops=byRole.getOrDefault(OutfitRules.Role.TOP,List.of());var bottoms=byRole.getOrDefault(OutfitRules.Role.BOTTOM,List.of());
        // Rotate bottoms so early suggestions do not all share a single top or bottom.
        for(int round=0;round<bottoms.size();round++)for(int t=0;t<tops.size();t++)bodies.add(List.of(tops.get(t),bottoms.get((t+round)%bottoms.size())));
        var output=new ArrayList<List<String>>();var seen=new HashSet<String>();
        for(var body:bodies) {
            var base=new ArrayList<>(body);
            for(var item:items)if(required.contains(item.id())&&!base.contains(item))base.add(item);
            add(base,required,output,seen);
            for(var extra:items)if(Set.of(OutfitRules.Role.SHOES,OutfitRules.Role.BAG,OutfitRules.Role.OUTER).contains(OutfitRules.role(extra.fields()))&&!base.contains(extra)) {
                var option=new ArrayList<>(base);option.add(extra);add(option,required,output,seen);
                if(output.size()>=limit)return output.subList(0,limit);
            }
            if(output.size()>=limit)return output.subList(0,limit);
        }
        return output;
    }
    private static void add(List<Item> items,Set<String> required,List<List<String>> output,Set<String> seen) {
        if(items.size()>12)return;
        try {OutfitRules.validate(items.stream().map(Item::fields).toList(),true);}catch(com.smartcloset.image.ProcessingException invalid){return;}
        var ids=items.stream().map(Item::id).sorted().toList();
        if(ids.containsAll(required)&&seen.add(ids.toString()))output.add(ids);
    }
}
