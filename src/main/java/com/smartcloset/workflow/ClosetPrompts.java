package com.smartcloset.workflow;

public final class ClosetPrompts {
    private ClosetPrompts() {}
    public static final String RESTAGE = "将原图中主视的一件衣物整理为干净、自然的平铺展示图。自拍或穿着照只提取这件衣物，去掉人物、其他衣物、配饰与背景。保留真实款式、颜色、图案、文字、里衬、领口、袖口、门襟、纽扣、口袋和版型，仅摆正衣物、适度减轻随机褶皱；保留结构褶皱，不增删设计细节，不凭空补写被遮挡的文字与图案。";
    public static final String FAITHFUL = "严格依据原照片，仅提取主视的一件衣物并整理背景、居中展示。自拍或穿着照去掉人物、其他衣物和配饰。保留可见姿态、款式、颜色、图案、文字、里衬、领口、袖口、门襟、纽扣、口袋和版型，不增删或重绘衣物结构，不凭空补写被遮挡的文字与图案。";
    public static boolean allowedPrompt(String prompt) { return RESTAGE.equals(prompt) || FAITHFUL.equals(prompt); }
}
