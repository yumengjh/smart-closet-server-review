package com.smartcloset.garment;

import java.util.*;

/** Only unambiguous local aliases. Original labels are never overwritten. */
public final class ClosetClassification {
    public static final String VERSION="closet-families-v1";
    private static final Map<String,Set<String>> ALIASES=Map.ofEntries(
        Map.entry("DRESS",Set.of("连衣裙","背心裙","吊带裙","dress")),
        Map.entry("SKIRT",Set.of("半身裙","短裙","长裙","skirt")),
        Map.entry("SHIRT",Set.of("衬衫","衬衣","shirt")),
        Map.entry("PANTS",Set.of("裤子","长裤","短裤","牛仔裤","西裤","休闲裤","运动裤","阔腿裤","裤","pants","trousers","jeans","shorts")),
        Map.entry("TOP",Set.of("T恤","t恤","t-shirt","卫衣","毛衣","针织衫","背心","Polo","polo","上装","上衣","hoodie","sweater","top")),
        Map.entry("OUTER",Set.of("外套","夹克","大衣","风衣","羽绒服","冲锋衣","开衫","西装外套","coat","jacket","blazer","cardigan","parka")),
        Map.entry("ONE_PIECE",Set.of("连体衣","连体裤","连身衣","jumpsuit","romper")),
        Map.entry("SHOES",Set.of("鞋","鞋子","靴子","运动鞋","凉鞋","皮鞋","高跟鞋","平底鞋","shoes","boots","sneakers","sandals")),
        Map.entry("ACCESSORY",Set.of("帽子","帽","围巾","腰带","袜子","袜","首饰","眼镜","耳饰","耳环","项链","手链","戒指","手套","领带","包","背包","手提包","斜挎包","配饰","hat","bag","glasses","jewelry","scarf","belt","socks")));
    public static String family(List<String> values) {
        var families=new HashSet<String>();
        for(String value:values) {
            String family=ALIASES.entrySet().stream().filter(e -> e.getValue().stream().anyMatch(v -> v.equalsIgnoreCase(value.strip()))).map(Map.Entry::getKey).findFirst().orElse("UNKNOWN");
            families.add(family);
        }
        return families.size()==1?families.iterator().next():"UNKNOWN";
    }
    public static boolean matches(List<String> values,String requested) {
        String q=requested.strip();String actual=family(values);
        if(Set.of("裙子","裙","skirts","裙类").contains(q.toLowerCase(Locale.ROOT)))return Set.of("DRESS","SKIRT").contains(actual);
        if(values.stream().anyMatch(v -> v.equalsIgnoreCase(q)))return true;
        if(ALIASES.containsKey(q.toUpperCase(Locale.ROOT)))return actual.equals(q.toUpperCase(Locale.ROOT));
        String expected=family(List.of(q));return !expected.equals("UNKNOWN")&&actual.equals(expected);
    }
    private ClosetClassification() {}
}
