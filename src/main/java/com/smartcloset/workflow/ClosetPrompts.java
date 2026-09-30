package com.smartcloset.workflow;

public final class ClosetPrompts {
    private ClosetPrompts() {}
    public static final String RESTAGE = "将原图中的单件衣物整理为干净、自然的电商平铺展示图。保留衣物真实款式、颜色、图案、文字、纽扣、口袋和版型，仅摆正衣物、适度抚平褶皱并清理背景。不得增加、删除或改动衣物设计细节。";
    public static final String FAITHFUL = "严格依据原照片，仅整理背景并将单件衣物完整居中展示。必须保留原有姿态、款式、颜色、图案、文字、纽扣、口袋和全部版型细节，不得增删或重绘衣物结构。";
    public static boolean allowedPrompt(String prompt) { return RESTAGE.equals(prompt) || FAITHFUL.equals(prompt); }
}
