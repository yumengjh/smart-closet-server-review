package com.smartcloset.image;

import com.smartcloset.ClosetProperties;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real-model regression using the two locally observed round-corner inputs. */
@EnabledIfSystemProperty(named="closet.samples",matches="true")
class LocalStickerRegressionTest {
    @TempDir Path storage;
    @Test void roundedTransparentPhotosActuallyRunSegmentationWhenForced() throws Exception {
        var properties=new ClosetProperties(storage,Path.of("models/u2net.onnx"),12000000,1000,2);
        var model=new U2NetEngine(properties);
        try {
            var segmentation=new SegmentationService(properties,List.of(model),new ObjectMapper());
            var service=new TransparencyService(properties,new TransparencyInspector(),segmentation);
            Path report=Path.of(".local/intake-verification");Files.createDirectories(report);
            var canvas=new BufferedImage(960,1100,BufferedImage.TYPE_INT_RGB);var g=canvas.createGraphics();
            try {
                String[] samples={"520c8ab6-680a-40c0-9ab7-099a38104813","13346629-1fb9-46ff-bdd8-46e099fa2285"};
                int row=0;
                for(String sample:samples){
                    byte[] original=Files.readAllBytes(Path.of("data/workflows",sample,"input.png"));
                    var reused=service.ensure(original,TransparencyService.Policy.AUTO,SegmentationEngine.Method.U2NET);
                    assertEquals(TransparencyService.Action.REUSED_ALPHA,reused.decision().action());
                    var forced=service.ensure(original,TransparencyService.Policy.FORCE,SegmentationEngine.Method.U2NET);
                    assertNotNull(forced.segmentationId(),"FORCE must execute the model despite existing corner transparency");
                    assertNotEquals(TransparencyService.Action.REUSED_ALPHA,forced.decision().action());
                    assertTrue(forced.decision().after().transparentRatio()>forced.decision().before().transparentRatio()+.1,"interior photo background should be removed");
                    Files.write(report.resolve(sample+"-cutout.png"),forced.png());
                    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(report.resolve(sample+"-decision.json").toFile(),forced.decision());
                    draw(g,ImageIO.read(new ByteArrayInputStream(original)),0,row,"Input / transparent corners");
                    draw(g,ImageIO.read(new ByteArrayInputStream(forced.png())),1,row,"Forced local cutout / "+forced.decision().after().verdict());
                    row++;
                }
            } finally {g.dispose();}
            ImageIO.write(canvas,"png",report.resolve("local-background-comparison.png").toFile());
        } finally {model.close();}
    }
    private void draw(Graphics2D g,BufferedImage image,int column,int row,String label){
        int x=column*480,y=row*550;
        for(int dy=0;dy<550;dy+=16)for(int dx=0;dx<480;dx+=16){g.setColor((dx/16+dy/16)%2==0?new Color(230,230,230):Color.WHITE);g.fillRect(x+dx,y+dy,16,16);}
        g.setColor(Color.DARK_GRAY);g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,16));g.drawString(label,x+12,y+25);
        double scale=Math.min(450d/image.getWidth(),500d/image.getHeight());int w=(int)(image.getWidth()*scale),h=(int)(image.getHeight()*scale);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(image,x+(480-w)/2,y+40+(500-h)/2,w,h,null);
    }
}
