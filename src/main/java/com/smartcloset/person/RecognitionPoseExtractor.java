package com.smartcloset.person;

import com.smartcloset.recognition.Recognition;
import com.smartcloset.recognition.RecognitionService;
import org.springframework.stereotype.Component;

/** Default recognition provider and model with the pose-v1 prompt and schema (same keys and limits as garment recognition). */
@Component
public class RecognitionPoseExtractor implements PoseExtractor {
    private final RecognitionService recognition;
    public RecognitionPoseExtractor(RecognitionService recognition) { this.recognition = recognition; }

    @Override public Extraction extract(byte[] png) {
        var prepared = recognition.prepareCustom(new Recognition.Request(null, null), PoseCards.SCHEMA_VERSION, PoseCards.prompt(), PoseCards.schema());
        var out = recognition.raw(prepared, png, "提取这张图片中主要人物的姿势。");
        return new Extraction(out.raw(), prepared.model());
    }
}
