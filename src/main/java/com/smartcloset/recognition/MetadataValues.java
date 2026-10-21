package com.smartcloset.recognition;

import java.util.*;

/** Conservative vocabulary cleanup; unfamiliar descriptions remain intact. */
public final class MetadataValues {
    private MetadataValues() {}
    private static final Map<String, String> COLOR_ALIASES = Map.ofEntries(
        Map.entry("浅蓝", "浅蓝色"), Map.entry("淡蓝", "浅蓝色"), Map.entry("淡蓝色", "浅蓝色"),
        Map.entry("灰蓝", "灰蓝色"), Map.entry("深蓝", "深蓝色"), Map.entry("天蓝", "天蓝色"),
        Map.entry("藏蓝", "藏蓝色"), Map.entry("宝蓝", "宝蓝色"),
        Map.entry("浅绿", "浅绿色"), Map.entry("淡绿", "浅绿色"), Map.entry("淡绿色", "浅绿色"),
        Map.entry("灰绿", "灰绿色"), Map.entry("深绿", "深绿色")
    );
    private static final List<String> COLOR_FAMILIES = List.of("黑色", "白色", "灰色", "米色", "棕色", "红色", "橙色", "黄色", "绿色", "蓝色", "紫色", "粉色");

    public static String canonical(String field, String value) {
        String clean = value.strip();
        if (field.equals("primaryColor") || field.equals("secondaryColors")) {
            if (COLOR_ALIASES.containsKey(clean)) return COLOR_ALIASES.get(clean);
            if (COLOR_FAMILIES.contains(clean + "色")) return clean + "色";
        }
        if (field.equals("seasons") && Set.of("春季", "夏季", "秋季", "冬季").contains(clean)) return clean.substring(0, 1);
        if (field.equals("genderFit")) {
            if (Set.of("女装", "女式", "女性", "女").contains(clean)) return "女款";
            if (Set.of("男装", "男式", "男性", "男").contains(clean)) return "男款";
            if (Set.of("男女同款", "通用", "男女通用", "不分男女", "中性款").contains(clean)) return "中性";
        }
        return clean;
    }
    public static List<String> canonical(String field, List<String> values) {
        return values.stream().map(value -> canonical(field, value)).distinct().toList();
    }
    public static String colorFamily(String value) {
        String color = canonical("primaryColor", value);
        if (color.equals("藏蓝色")) return "蓝色";
        for (String family : COLOR_FAMILIES) {
            if (color.equals(family) || color.matches("[浅淡深亮暗灰粉天宝湖海冰雾墨]*" + family)) return family;
        }
        return color;
    }
    public static boolean matchesColor(List<String> values, String filter) {
        String color = canonical("primaryColor", filter);
        return values.stream().anyMatch(value -> canonical("primaryColor", value).equals(color)
            || COLOR_FAMILIES.contains(color) && colorFamily(value).equals(color));
    }
}
