package com.smartcloset.intake;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import com.smartcloset.recognition.RecognitionSchema;
import com.smartcloset.image.ProcessingException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class QuickRecognitionTest {
 final JsonMapper json=JsonMapper.builder().build();
 final QuickRecognition parser=new QuickRecognition(new RecognitionSchema(json));
 ObjectNode response(double height){
  var attributes=new LinkedHashMap<String,Object>();for(var f:RecognitionSchema.FIELDS)attributes.put(f.key(),Map.of("values",List.of(),"status","UNKNOWN","evidence","照片没有提供可见证据。"));
  attributes.put("category",Map.of("values",List.of("长裤"),"status","OBSERVED","evidence","原图可见长裤。"));
  var quality=new LinkedHashMap<String,Object>();for(String f:List.of("needsEnhancement","backgroundResidue","edgeDamage","exposureProblem","subjectIncomplete","personInterference","neighborInterference","needsIsolation"))quality.put(f,false);quality.put("reason","基础图可用于展示。");quality.put("sourceType","SINGLE_PHOTO");
  return json.valueToTree(Map.of("recognition",Map.of("assessment","READY","attributes",attributes,"issues",List.of(),"target",Map.of("description","黑色长裤","worn",false,"bounds",Map.of("x",.11,"y",.07,"width",.66,"height",height))),"quality",quality));
 }
 @Test void clipsSmallBoundaryOvershootWithoutChangingVisibleAttributesOrRawEvidence(){var raw=response(.95);var parsed=parser.parse(raw);assertEquals(.93,parsed.metadata().target().bounds().height(),1e-9);assertEquals(.95,raw.path("recognition").path("target").path("bounds").path("height").asDouble());assertEquals(List.of("长裤"),parsed.metadata().attributes().get("category").values());assertEquals("TARGET_CLIPPED",parsed.metadata().issues().getFirst().code());}
 @Test void largeOvershootAndIncompleteQualityRemainInvalid(){assertThrows(ProcessingException.class,()->parser.parse(response(.99)));var raw=response(.90);((ObjectNode)raw.path("quality")).remove("personInterference");assertThrows(ProcessingException.class,()->parser.parse(raw));}
}
