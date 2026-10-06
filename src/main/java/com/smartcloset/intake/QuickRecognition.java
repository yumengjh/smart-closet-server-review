package com.smartcloset.intake;

import com.smartcloset.image.ProcessingException;
import com.smartcloset.recognition.RecognitionSchema;
import com.smartcloset.recognition.Recognition;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;

/** Local validation of an already returned response; never makes a model request. */
@Component
public class QuickRecognition {
    private final RecognitionSchema schema;
    private static final List<String> FLAGS=List.of("needsEnhancement","backgroundResidue","edgeDamage","exposureProblem","subjectIncomplete","personInterference","neighborInterference","needsIsolation");
    public record Parsed(Recognition.Result metadata,JsonNode quality){}
    public QuickRecognition(RecognitionSchema schema){this.schema=schema;}
    public Parsed parse(JsonNode raw){
        var recognition=raw.path("recognition").deepCopy();
        var bounds=recognition.path("target").path("bounds");boolean clipped=false;
        if(bounds instanceof ObjectNode b){
            double x=b.path("x").asDouble(Double.NaN),y=b.path("y").asDouble(Double.NaN),w=b.path("width").asDouble(Double.NaN),h=b.path("height").asDouble(Double.NaN);
            // Small rounding overshoot is clipped to the actual photograph, never extrapolated.
            if(x>=0&&y>=0&&w>0&&h>0&&x<=1&&y<=1&&w<=1&&h<=1&&x+w<=1.03&&y+h<=1.03){
                if(x+w>1){b.put("width",1-x);clipped=true;}if(y+h>1){b.put("height",1-y);clipped=true;}
            }
        }
        var metadata=schema.validate(recognition,true);
        if(clipped){var issues=new ArrayList<>(metadata.issues());issues.add(new Recognition.Issue("TARGET_CLIPPED","定位框轻微越界，已裁到原图边缘；未补写识别属性。",List.of(),"可对照原图核对主目标。"));metadata=new Recognition.Result(metadata.assessment(),metadata.attributes(),List.copyOf(issues),metadata.target());}
        var quality=raw.path("quality");
        if(!quality.isObject()||FLAGS.stream().anyMatch(f->!quality.path(f).isBoolean())||!quality.path("reason").isString()||quality.path("reason").asText().length()>1000||!Set.of("SINGLE_PHOTO","PRODUCT_SCREENSHOT","SELFIE","OUTFIT","LAYERED","UNKNOWN").contains(quality.path("sourceType").asText()))throw ProcessingException.invalid("质量判断格式无效，已保留模型结果。");
        return new Parsed(metadata,quality);
    }
}
