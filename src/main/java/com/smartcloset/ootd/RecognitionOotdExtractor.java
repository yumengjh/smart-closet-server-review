package com.smartcloset.ootd;

import com.smartcloset.recognition.Recognition;
import com.smartcloset.recognition.RecognitionService;
import org.springframework.stereotype.Component;

/** Uses the default recognition provider and model with the ootd-v1 prompt and schema (same keys, same quota account). */
@Component
public class RecognitionOotdExtractor implements OotdExtractor {
    private final RecognitionService recognition;
    public RecognitionOotdExtractor(RecognitionService recognition) { this.recognition = recognition; }

    @Override public Extraction extract(byte[] png) {
        var prepared = recognition.prepareCustom(new Recognition.Request(null, null), OotdSchema.VERSION, OotdSchema.prompt(), OotdSchema.schema());
        var out = recognition.raw(prepared, png, "整理这张整套穿搭照片。");
        return new Extraction(out.raw(), prepared.model(), prepared.promptVersion(), out.usage());
    }
}
