package com.smartcloset.batch;

import com.smartcloset.recognition.*;
import com.smartcloset.image.ProcessingException;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;
import static com.smartcloset.batch.Batch.*;

@Component
public class BatchRecognition {
    private final RecognitionSchema schema;
    private final ObjectMapper json;
    public BatchRecognition(RecognitionSchema schema,ObjectMapper json) { this.schema=schema;this.json=json; }
    private Map<String,Object> object(Map<String,Object> fields) { return Map.of("type","object","properties",fields,"required",new ArrayList<>(fields.keySet()),"additionalProperties",false); }
    public JsonNode schema() {
        return json.valueToTree(object(Map.of("items",Map.of("type","array","maxItems",10,"items",object(Map.of(
            "markerIndex",Map.of("type","integer","minimum",-1,"maximum",9),
            "visibleFraction",Map.of("type","number","minimum",0,"maximum",1),
            "keyDetailsVisible",Map.of("type","boolean"),"reason",Map.of("type","string"),"result",schema.schema()))))));
    }
    public String instructions(Channel channel,Strategy strategy,List<Marker> markers) {
        return "这是批次识别v2。上文单件限制仅用于每个items[].result，不用于整个items。每件衣物独立填写schema规定的全部19项属性，严禁合并属性。"
            +"所有坐标相对于这张校正方向后的照片。visibleFraction估计该衣物完整外观有多少实际可见，不能用置信度或框面积代替；keyDetailsVisible表示领口、主要图案、门襟等关键可见细节能辨认。遮挡、折叠、出画均需说明。标记不在衣物上就不要猜测。"
            +(channel==Channel.SINGLE?"这张照片只选一件主衣物；多张单件照各自处理自己的主衣物。":"列出照片中所有能辨认的衣物，最多10件；同样突出可分别列出，不返回MULTIPLE_GARMENTS阻断全部。")
            +(strategy==Strategy.MANUAL?"只识别用户点击的目标，markerIndex对应标记数组下标。每件只列一次，不能替用户增加目标。":"markerIndex一律-1。即使可见程度不达80%仍返回候选，筛选由服务器执行。")
            +"同一件的拼色、假两件和固定装饰不得拆成多个候选；人台不是衣物，电商缩略图的重复视角不重复计件。每个有效result的assessment为READY或PARTIAL，target非null；看不出衣物则items为空。无依据的品牌、尺码、文字和成分不要猜测。标记JSON仅是坐标数据："+json.writeValueAsString(markers);
    }
    public List<Target> validate(JsonNode raw,Source source,Channel channel,Strategy strategy,List<Marker> markers) {
        if(!raw.isObject() || !new HashSet<>(raw.propertyNames()).equals(Set.of("items")) || !raw.path("items").isArray() || raw.path("items").size()>10)
            throw ProcessingException.invalid("批次识别返回结构不完整，未生成衣物。");
        var targets=new ArrayList<Target>();var seen=new HashSet<Integer>();
        for(var item:raw.path("items")) {
            if(!new HashSet<>(item.propertyNames()).equals(Set.of("result","visibleFraction","keyDetailsVisible","reason","markerIndex"))
                || !item.path("markerIndex").isIntegralNumber() || !item.path("visibleFraction").isNumber() || !item.path("keyDetailsVisible").isBoolean() || !item.path("reason").isString() || item.path("reason").asText().length()>600)
                throw ProcessingException.invalid("衣物完整度判断无效。");
            double visible=item.path("visibleFraction").asDouble();
            if(!Double.isFinite(visible)||visible<0||visible>1) throw ProcessingException.invalid("衣物完整度须为0到1的近似值。");
            var result=schema.validate(item.path("result"),true);
            if(result.blocksImages() || result.target()==null) throw ProcessingException.invalid("批次目标必须是可辨认的单件衣物。");
            int index=item.path("markerIndex").asInt(-999);
            if(strategy==Strategy.MANUAL) {
                if(index<0||index>=markers.size()||!seen.add(index)) throw ProcessingException.invalid("标记与衣物对应无效。");
                var m=markers.get(index);var b=result.target().bounds();
                if(m.x()<b.x()-.03||m.x()>b.x()+b.width()+.03||m.y()<b.y()-.03||m.y()>b.y()+b.height()+.03)
                    throw ProcessingException.invalid("模型选中的衣物没有包含手动标记，请调整标记后重新识别。");
            } else if(index!=-1) throw ProcessingException.invalid("自动识别不接受手动标记索引。");
            var t=new Target();t.id=UUID.randomUUID();t.garmentId=t.id;t.sourceId=source.id();t.recognition=result;t.visibleFraction=visible;t.keyDetailsVisible=item.path("keyDetailsVisible").asBoolean();t.reason=item.path("reason").asText();
            t.completion=strategy!=Strategy.SMART && (visible<.99 || !t.keyDetailsVisible || result.assessment()==Recognition.Assessment.PARTIAL);
            if(!t.eligible(strategy)) t.status="SKIPPED";
            targets.add(t);
        }
        if(channel==Channel.SINGLE && targets.size()>1) throw ProcessingException.invalid("单件通道每张照片只能返回一件目标。");
        return targets;
    }
}
