package com.smartcloset.garment;

import java.util.*;
import org.springframework.stereotype.Service;
import com.smartcloset.workflow.WorkflowFiles;

@Service
public class ClosetSummaryService {
    /** At most this many matching garments are listed by name (issue #8); the count is always exact. */
    public static final int LIST_LIMIT=60;
    private final GarmentRepository garments;
    private final ImageStyles imageStyles;
    public ClosetSummaryService(GarmentRepository garments,ImageStyles imageStyles){this.garments=garments;this.imageStyles=imageStyles;}
    public Map<String,Object> summary(String owner,String category,String season) {return summary(owner,"",category,"",season,null);}
    public Map<String,Object> summary(String owner,String query,String category,String color,String season,Boolean favorite) {return summary(owner,query,category,color,season,favorite,"");}
    /**
     * {@code imageStyle} (PHOTO/ILLUSTRATION/SKETCH/OTHER/UNCLASSIFIED, empty = any) filters by how the display picture is
     * drawn — "卡通画风" — which is not the same as a cartoon print on the garment (that is {@code query}). Issue #8: the
     * assistant could only page through search results and never gave an exact number or list.
     */
    public Map<String,Object> summary(String owner,String query,String category,String color,String season,Boolean favorite,String imageStyle) {
        var all=garments.projection(owner);var active=all.stream().filter(g -> g.status().equals("ACTIVE")).toList();
        var drawn=imageStyles.current(owner);
        String wantedStyle=Objects.toString(imageStyle,"").strip().toUpperCase(Locale.ROOT);
        if(!wantedStyle.isEmpty()&&!Set.of("PHOTO","ILLUSTRATION","SKETCH","OTHER","UNCLASSIFIED").contains(wantedStyle)) throw com.smartcloset.image.ProcessingException.invalid("imageStyle 只能是 PHOTO、ILLUSTRATION、SKETCH、OTHER 或 UNCLASSIFIED。");
        java.util.function.Function<GarmentRepository.Projection,String> styleOf=g -> {var e=drawn.get(g.id().toString());return e==null?"UNCLASSIFIED":e.style().name();};
        var selected=active.stream().filter(g -> GarmentRepository.matches(g,query,category,color,season)&&(favorite==null||g.favorite()==favorite)&&(wantedStyle.isEmpty()||styleOf.apply(g).equals(wantedStyle))).toList();
        var categories=new TreeMap<String,Long>();var seasons=new TreeMap<String,Long>();
        for(var g:active){categories.merge(g.family(),1L,Long::sum);for(String s:new HashSet<>(g.fields().getOrDefault("seasons",List.of())))if(Set.of("春","夏","秋","冬").contains(s))seasons.merge(s,1L,Long::sum);}
        var states=new TreeMap<String,Long>();for(var g:all)states.merge(g.status(),1L,Long::sum);
        var result=new LinkedHashMap<String,Object>();result.put("count",selected.size());result.put("totalActive",active.size());result.put("statuses",states);
        result.put("categoryCounts",categories);result.put("seasonCounts",seasons);
        result.put("skirtCount",categories.getOrDefault("DRESS",0L)+categories.getOrDefault("SKIRT",0L));
        result.put("unknownCategoryCount",active.stream().filter(g -> g.family().equals("UNKNOWN")).count());
        result.put("unknownSeasonCount",active.stream().filter(g -> g.fields().getOrDefault("seasons",List.of()).isEmpty()||g.fields().getOrDefault("seasons",List.of()).stream().anyMatch(s -> !Set.of("春","夏","秋","冬").contains(s))).count());
        var styles=new TreeMap<String,Long>();for(var g:active)styles.merge(styleOf.apply(g),1L,Long::sum);
        result.put("imageStyleCounts",styles);
        result.put("imageStyleLabels",Map.of("PHOTO","写实实物","ILLUSTRATION","卡通插画","SKETCH","简笔线稿","OTHER","其他风格","UNCLASSIFIED","尚未判断"));
        // The matching garments themselves, so "哪几件" is answered from the same exact set as the count.
        result.put("items",selected.stream().limit(LIST_LIMIT).map(g -> {var m=new LinkedHashMap<String,Object>();m.put("id",g.id().toString());m.put("name",Objects.toString(g.name(),""));
            m.put("category",g.fields().getOrDefault("category",List.of()));m.put("primaryColor",g.fields().getOrDefault("primaryColor",List.of()));m.put("imageStyle",styleOf.apply(g));return m;}).toList());
        result.put("itemsTruncated",selected.size()>LIST_LIMIT);
        result.put("filter",Map.of("query",Objects.toString(query,""),"category",Objects.toString(category,""),"color",Objects.toString(color,""),"season",Objects.toString(season,""),"favorite",favorite==null?"ANY":favorite.toString(),"imageStyle",wantedStyle.isEmpty()?"ANY":wantedStyle));
        result.put("queriedAt",System.currentTimeMillis());result.put("wardrobeVersion",WorkflowFiles.hash(all.stream().map(g -> g.id()+":"+g.revision()+":"+g.status()).sorted().collect(java.util.stream.Collectors.joining(";")).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        result.put("mappingVersion",ClosetClassification.VERSION);result.put("scope","已入柜 ACTIVE 物品记录；包含鞋包配饰，多季节标签交叉计数；适穿建议不当作统计事实。count 是全部匹配的准确数量，不分页；items 是这些衣物本身（最多 "+LIST_LIMIT+" 件）。imageStyle 是展示图的画风（卡通插画/写实/简笔），衣服上的卡通图案要用 query。");return result;
    }
}
