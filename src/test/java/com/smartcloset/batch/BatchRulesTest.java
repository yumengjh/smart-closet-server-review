package com.smartcloset.batch;
import com.smartcloset.image.*;
import com.smartcloset.recognition.*;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static com.smartcloset.batch.Batch.*;
class BatchRulesTest {
    static final ObjectMapper json=new ObjectMapper();
    static Map<String,Object> result() {
        var attrs=new LinkedHashMap<String,Object>();
        for(var field:RecognitionSchema.FIELDS) attrs.put(field.key(),Map.of("values",List.of(),"status","UNKNOWN","evidence","无照片依据"));
        return Map.of("assessment","PARTIAL","attributes",attrs,"issues",List.of(),"target",Map.of("description","衣物","worn",true,"bounds",Map.of("x",.1,"y",.1,"width",.8,"height",.8)));
    }
    static Map<String,Object> item(double visible,boolean key,int marker) {return Map.of("result",result(),"visibleFraction",visible,"keyDetailsVisible",key,"reason","遮挡判断","markerIndex",marker);}
    static Source source() {return new Source(UUID.randomUUID(),"自拍","original","preview","hash","hash","a.jpg","b.png",0,100,100);}
    @Test void smartThresholdAndKeyDetailsDifferFromCompletionModes() {
        var detector=new BatchRecognition(new RecognitionSchema(json),json);var source=source();
        var raw=json.valueToTree(Map.of("items",List.of(item(.79,true,-1),item(.8,true,-1),item(.95,false,-1))));
        var smart=detector.validate(raw,source,Channel.MULTIPLE,Strategy.SMART,List.of());
        assertEquals(List.of("SKIPPED","DETECTED","SKIPPED"),smart.stream().map(t->t.status).toList());
        var full=detector.validate(raw,source,Channel.MULTIPLE,Strategy.FULL,List.of());
        assertTrue(full.stream().allMatch(t->t.completion&&t.eligible(Strategy.FULL)));
        assertThrows(ProcessingException.class,()->detector.validate(raw,source,Channel.SINGLE,Strategy.FULL,List.of()));
    }
    @Test void manualTargetsMustMatchTheClickedLocationAndNeverUseSmartGate() {
        var detector=new BatchRecognition(new RecognitionSchema(json),json);var source=source();
        var raw=json.valueToTree(Map.of("items",List.of(item(.3,false,0))));
        assertTrue(detector.validate(raw,source,Channel.SINGLE,Strategy.MANUAL,List.of(new Marker(source.id(),.5,.5))).getFirst().completion);
        assertThrows(ProcessingException.class,()->detector.validate(raw,source,Channel.SINGLE,Strategy.MANUAL,List.of(new Marker(source.id(),0,0))));
    }
    @Test void samplesPhoneResolutionAndRejectsUnsupportedBytes() throws Exception {
        var image=new BufferedImage(6000,4000,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();g.setColor(Color.BLUE);g.fillRect(0,0,6000,4000);g.dispose();
        var out=new ByteArrayOutputStream();ImageIO.write(image,"jpeg",out);var bytes=out.toByteArray();
        assertThrows(ProcessingException.class,()->Images.decode(bytes,12000000));
        var decoded=Images.sampled(bytes,1600);assertTrue(decoded.image().getWidth()<=1600);assertTrue(new Color(decoded.image().getRGB(100,100)).getBlue()>200);
        assertThrows(ProcessingException.class,()->Images.sampled(new byte[]{1,2,3},1600));
    }
    @Test void slotsPreserveOrderAndWrongDimensionsNeverSilentlySplit() throws Exception {
        var atlas=new BufferedImage(1024,1024,BufferedImage.TYPE_INT_RGB);var g=atlas.createGraphics();g.setColor(Color.RED);g.fillRect(0,0,512,1024);g.setColor(Color.BLUE);g.fillRect(512,0,512,1024);g.dispose();
        var bytes=BatchImages.png(atlas);var pieces=BatchImages.split(bytes,2,"1024x1024");
        assertEquals(Color.RED.getRGB(),BatchImages.read(pieces.getFirst()).getRGB(200,200));assertEquals(Color.BLUE.getRGB(),BatchImages.read(pieces.getLast()).getRGB(200,200));
        assertThrows(ProcessingException.class,()->BatchImages.split(bytes,2,"2048x1024"));
    }
    @Test void singleOutputKeepsNativeSizeAndTransparencyWithoutGridValidation() throws Exception {
        var image=new BufferedImage(1254,940,BufferedImage.TYPE_INT_ARGB);
        image.setRGB(1100,800,0x80443322);
        var pieces=BatchImages.split(BatchImages.png(image),1,"1024x1024");
        assertEquals(1,pieces.size());var result=BatchImages.read(pieces.getFirst());
        assertEquals(1254,result.getWidth());assertEquals(940,result.getHeight());
        assertEquals(0x80443322,result.getRGB(1100,800));assertEquals(0,result.getRGB(0,0));
    }
    @Test void scaledMultiOutputKeepsSlotOrderButRejectsDifferentAspectRatio() throws Exception {
        var image=new BufferedImage(1254,1254,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
        g.setColor(Color.RED);g.fillRect(0,0,627,1254);g.setColor(Color.BLUE);g.fillRect(627,0,627,1254);g.dispose();
        var bytes=BatchImages.png(image);var pieces=BatchImages.split(bytes,2,"1024x1024");
        assertEquals(627,BatchImages.read(pieces.getFirst()).getWidth());
        assertEquals(Color.RED.getRGB(),BatchImages.read(pieces.getFirst()).getRGB(500,100));
        assertEquals(Color.BLUE.getRGB(),BatchImages.read(pieces.getLast()).getRGB(100,100));
        assertThrows(ProcessingException.class,()->BatchImages.split(bytes,2,"2048x1024"));
        assertThrows(ProcessingException.class,()->BatchImages.split(bytes,0,"1024x1024"));
    }
    @Test void multiRowSlotsUseActualPixelsAndIgnoreTrailingEmptyCells() throws Exception {
        for(int count:List.of(4,8,10)) {
            var grid=BatchImages.grid(count);var image=new BufferedImage(1254,1254,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();
            for(int i=0;i<count;i++) {
                int x=i%grid.columns(),y=i/grid.columns();g.setColor(new Color(20+i*20,40,80));
                g.fillRect(x*1254/grid.columns(),y*1254/grid.rows(),(x+1)*1254/grid.columns()-x*1254/grid.columns(),(y+1)*1254/grid.rows()-y*1254/grid.rows());
            }
            g.dispose();var pieces=BatchImages.split(BatchImages.png(image),count,"1024x1024");assertEquals(count,pieces.size());
            for(int i=0;i<count;i++) {var piece=BatchImages.read(pieces.get(i));assertEquals(new Color(20+i*20,40,80).getRGB(),piece.getRGB(piece.getWidth()/2,piece.getHeight()/2));}
        }
        assertEquals(new BatchImages.Grid(3,3),BatchImages.grid(8));assertEquals(new BatchImages.Grid(4,3),BatchImages.grid(10));
    }
    @Test void largeNumberedReferenceStaysBoundedAndLastCellEmpty() {
        var input=new BufferedImage(900,1200,BufferedImage.TYPE_INT_RGB);
        var reference=BatchImages.reference(Collections.nCopies(10,input),1600);
        assertEquals(1600,reference.getWidth());assertEquals(1200,reference.getHeight());
        assertEquals(Color.WHITE.getRGB(),reference.getRGB(1500,1100));
        assertThrows(ProcessingException.class,()->BatchImages.grid(11));
    }
    @Test void nativeSquareCellsCanReplaceRequestedSquareCanvasWithoutChangingSlots() throws Exception {
        for(int count:List.of(2,3)){
            var image=new BufferedImage(count*724,724,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();
            for(int i=0;i<count;i++){g.setColor(new Color(40+i*60,60,100));g.fillRect(i*724,0,724,724);}g.dispose();
            var pieces=BatchImages.split(BatchImages.png(image),count,"1024x1024");assertEquals(count,pieces.size());
            for(int i=0;i<count;i++){var piece=BatchImages.read(pieces.get(i));assertEquals(724,piece.getWidth());assertEquals(724,piece.getHeight());assertEquals(new Color(40+i*60,60,100).getRGB(),piece.getRGB(300,300));}
        }
        assertThrows(ProcessingException.class,()->BatchImages.split(BatchImages.png(new BufferedImage(1500,1000,BufferedImage.TYPE_INT_RGB)),2,"1024x1024"));
    }
    @Test void requestedSchemaMatchesSemanticStatesAndReportsTheRejectedField() {
        var validator=new RecognitionSchema(json);var spec=validator.schema().path("properties").path("attributes").path("properties");
        assertFalse(spec.path("styles").path("properties").path("status").path("enum").toString().contains("OBSERVED"));
        assertFalse(spec.path("category").path("properties").path("status").path("enum").toString().contains("INFERRED"));
        assertEquals(1,spec.path("category").path("properties").path("values").path("maxItems").asInt());
        var invalid=json.valueToTree(result());((tools.jackson.databind.node.ObjectNode)invalid.path("attributes").path("styles")).put("status","OBSERVED").set("values",json.valueToTree(List.of("休闲")));
        assertTrue(assertThrows(ProcessingException.class,()->validator.validate(invalid,true)).getMessage().contains("风格建议"));
    }
    @Test void actualUsageCostsAndUnknownAreDifferent() {
        assertNull(ImageCosts.usd("other",json.valueToTree(Map.of("output_tokens",10))));
        assertNull(ImageCosts.usd("gpt-image-2.5-flare",json.valueToTree(Map.of("output_tokens",10))));
        assertEquals(.038,ImageCosts.usd("gpt-image-2.5-flare",json.valueToTree(Map.of("input_tokens_details",Map.of("image_tokens",1000,"text_tokens",0),"output_tokens",1000))),.0000001);
        assertEquals(.0006,ImageCosts.usd("gpt-6-luna",json.valueToTree(Map.of("prompt_tokens",1000,"prompt_tokens_details",Map.of("cached_tokens",0),"completion_tokens",1000))),.0000001);
    }
}
