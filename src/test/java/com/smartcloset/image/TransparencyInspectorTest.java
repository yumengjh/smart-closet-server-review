package com.smartcloset.image;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.smartcloset.image.TransparencyInspector.Verdict.*;

class TransparencyInspectorTest {
    final TransparencyInspector inspector = new TransparencyInspector();
    static BufferedImage valid() {
        var image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        fill(image,20,20,60,60,0xff336699); return image;
    }
    static void fill(BufferedImage i,int x,int y,int w,int h,int argb) {
        for(int yy=y;yy<y+h;yy++) for(int xx=x;xx<x+w;xx++)i.setRGB(xx,yy,argb);
    }
    @Test void opaqueRgbRgbaAndPaintedCheckerboardNeedSegmentation() {
        for(int type:new int[]{BufferedImage.TYPE_INT_RGB,BufferedImage.TYPE_INT_ARGB}) {
            var image=new BufferedImage(100,100,type);
            for(int y=0;y<100;y++)for(int x=0;x<100;x++)image.setRGB(x,y,((x/10+y/10)%2==0)?0xffffffff:0xffcccccc);
            assertEquals(OPAQUE,inspector.inspect(image).verdict());
            image.setRGB(0,0,0);assertEquals(OPAQUE,inspector.inspect(image).verdict());
        }
    }
    @Test void preservesSoftEdgesAndInternalHolesWithoutChangingPixels() {
        var image=valid();fill(image,19,20,1,60,0x806699aa);fill(image,40,40,10,10,0);
        int[] before=image.getRGB(0,0,100,100,null,0,100);var report=inspector.inspect(image);
        assertEquals(USABLE,report.verdict());assertEquals(1,report.foregroundComponents());
        assertTrue(report.exteriorTransparentRatio()<report.transparentRatio());
        assertArrayEquals(before,image.getRGB(0,0,100,100,null,0,100));
    }
    @Test void aTransparentHoleInsideAnOpaqueCanvasIsNotAnExternalBackground() {
        var image=valid();fill(image,0,0,100,100,0xff556677);fill(image,35,35,30,30,0);
        var report=inspector.inspect(image);assertEquals(SUSPICIOUS,report.verdict());
        assertEquals(0,report.exteriorTransparentRatio());assertTrue(report.reasons().contains("INSUFFICIENT_EXTERIOR_TRANSPARENCY"));
    }
    @Test void emptyAndNearlyInvisibleImagesAreRejected() {
        var image=new BufferedImage(100,100,BufferedImage.TYPE_INT_ARGB);assertEquals(EMPTY,inspector.inspect(image).verdict());
        fill(image,20,20,60,60,0x40336699);assertEquals(EMPTY,inspector.inspect(image).verdict());
    }
    @Test void touchingBorderAndDetachedForegroundAreReportedNotDeleted() {
        var image=valid();image.setRGB(0,50,0xff336699);
        assertTrue(inspector.inspect(image).reasons().contains("FOREGROUND_TOUCHES_IMAGE_EDGE"));
        image=valid();fill(image,82,30,15,30,0xff336699);
        var report=inspector.inspect(image);assertEquals(SUSPICIOUS,report.verdict());
        assertEquals(2,report.foregroundComponents());assertTrue(report.reasons().contains("FRAGMENTED_FOREGROUND"));
        assertEquals(0xff336699,image.getRGB(85,35));
    }
    @Test void extensiveSemitransparencyRequiresReviewEvenWithAnOpaqueCore() {
        var image=valid();fill(image,10,10,80,80,0x80336699);fill(image,35,35,30,30,0xff336699);
        assertTrue(inspector.inspect(image).reasons().contains("LARGE_SEMITRANSPARENT_AREA"));
    }
    @Test void transparentRatioBoundaryUsesUnroundedValues() {
        var image=valid();fill(image,0,0,100,100,0xff556677);fill(image,0,0,7,7,0);
        assertEquals(OPAQUE,inspector.inspect(image).verdict());image.setRGB(7,0,0);
        var report=inspector.inspect(image);assertEquals(.005,report.transparentRatio());assertEquals(SUSPICIOUS,report.verdict());
    }
    @Test void foregroundEightConnectivityDoesNotRequireSolidEdges() {
        var image=new BufferedImage(100,100,BufferedImage.TYPE_INT_ARGB);
        fill(image,20,20,30,30,0xff336699);fill(image,50,50,30,30,0xff336699);
        assertEquals(USABLE,inspector.inspect(image).verdict());assertEquals(1,inspector.inspect(image).foregroundComponents());
    }
}
