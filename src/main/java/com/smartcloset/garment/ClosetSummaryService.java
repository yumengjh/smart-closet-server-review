package com.smartcloset.garment;

import java.util.*;
import org.springframework.stereotype.Service;
import com.smartcloset.workflow.WorkflowFiles;

@Service
public class ClosetSummaryService {
    private final GarmentRepository garments;
    public ClosetSummaryService(GarmentRepository garments){this.garments=garments;}
    public Map<String,Object> summary(String owner,String category,String season) {return summary(owner,"",category,"",season,null);}
    public Map<String,Object> summary(String owner,String query,String category,String color,String season,Boolean favorite) {
        var all=garments.projection(owner);var active=all.stream().filter(g -> g.status().equals("ACTIVE")).toList();
        var selected=active.stream().filter(g -> GarmentRepository.matches(g,query,category,color,season)&&(favorite==null||g.favorite()==favorite)).toList();
        var categories=new TreeMap<String,Long>();var seasons=new TreeMap<String,Long>();
        for(var g:active){categories.merge(g.family(),1L,Long::sum);for(String s:new HashSet<>(g.fields().getOrDefault("seasons",List.of())))if(Set.of("春","夏","秋","冬").contains(s))seasons.merge(s,1L,Long::sum);}
        var states=new TreeMap<String,Long>();for(var g:all)states.merge(g.status(),1L,Long::sum);
        var result=new LinkedHashMap<String,Object>();result.put("count",selected.size());result.put("totalActive",active.size());result.put("statuses",states);
        result.put("categoryCounts",categories);result.put("seasonCounts",seasons);
        result.put("skirtCount",categories.getOrDefault("DRESS",0L)+categories.getOrDefault("SKIRT",0L));
        result.put("unknownCategoryCount",active.stream().filter(g -> g.family().equals("UNKNOWN")).count());
        result.put("unknownSeasonCount",active.stream().filter(g -> g.fields().getOrDefault("seasons",List.of()).isEmpty()||g.fields().getOrDefault("seasons",List.of()).stream().anyMatch(s -> !Set.of("春","夏","秋","冬").contains(s))).count());
        result.put("filter",Map.of("query",Objects.toString(query,""),"category",Objects.toString(category,""),"color",Objects.toString(color,""),"season",Objects.toString(season,""),"favorite",favorite==null?"ANY":favorite.toString()));
        result.put("queriedAt",System.currentTimeMillis());result.put("wardrobeVersion",WorkflowFiles.hash(all.stream().map(g -> g.id()+":"+g.revision()+":"+g.status()).sorted().collect(java.util.stream.Collectors.joining(";")).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        result.put("mappingVersion",ClosetClassification.VERSION);result.put("scope","已入柜 ACTIVE 物品记录；包含鞋包配饰，多季节标签交叉计数；适穿建议不当作统计事实。");return result;
    }
}
