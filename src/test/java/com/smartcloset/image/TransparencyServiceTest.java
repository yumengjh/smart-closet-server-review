package com.smartcloset.image;

import com.smartcloset.ClosetProperties;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.smartcloset.image.TransparencyService.*;
import static com.smartcloset.image.TransparencyInspectorTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransparencyServiceTest {
    @TempDir Path root;
    SegmentationService segmentation=mock(SegmentationService.class);
    TransparencyService service;
    @BeforeEach void setup(){service=new TransparencyService(new ClosetProperties(root,Path.of("absent"),12000000,1000,2),new TransparencyInspector(),segmentation);}
    static byte[] png(BufferedImage image)throws IOException{var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();}
    void output(BufferedImage image)throws Exception{
        var id=UUID.randomUUID();var result=mock(SegmentationResult.class);when(result.id()).thenReturn(id.toString());
        var file=root.resolve("cutout.png");Files.write(file,png(image));when(segmentation.uploadBytes(any(),any(),isNull(),eq(0d))).thenReturn(result);when(segmentation.file(id,"cutout.png")).thenReturn(file);
    }
    @Test void autoReusesValidAlphaByteForByteWithoutSegmentation()throws Exception{
        byte[] bytes=png(valid());var result=service.ensure(bytes,Policy.AUTO,SegmentationEngine.Method.U2NET);
        assertSame(bytes,result.png());assertEquals(Action.REUSED_ALPHA,result.decision().action());assertNull(result.segmentationId());verifyNoInteractions(segmentation);
    }
    @Test void opaqueAutoSegmentsOnceAndChecksTheProducedMask()throws Exception{
        output(valid());var input=new BufferedImage(100,100,BufferedImage.TYPE_INT_RGB);
        var result=service.ensure(png(input),Policy.AUTO,SegmentationEngine.Method.GRABCUT);
        assertEquals(Action.SEGMENTED,result.decision().action());assertNotNull(result.segmentationId());
        verify(segmentation,times(1)).uploadBytes(any(),eq(SegmentationEngine.Method.GRABCUT),isNull(),eq(0d));
    }
    @Test void forceReprocessesEvenValidAlpha()throws Exception{
        output(valid());var result=service.ensure(png(valid()),Policy.FORCE,SegmentationEngine.Method.GRABCUT);
        assertEquals(Action.SEGMENTED,result.decision().action());verify(segmentation).uploadBytes(any(),any(),isNull(),eq(0d));
    }
    @Test void preserveAndSuspiciousAutoNeverInvokeSegmentation()throws Exception{
        var opaque=new BufferedImage(100,100,BufferedImage.TYPE_INT_RGB);
        assertTrue(service.ensure(png(opaque),Policy.PRESERVE,null).needsReview());
        var suspicious=valid();suspicious.setRGB(0,50,0xff445566);
        assertTrue(service.ensure(png(suspicious),Policy.AUTO,SegmentationEngine.Method.U2NET).needsReview());verifyNoInteractions(segmentation);
    }
    @Test void invalidOutputDoesNotLoopAndEmptyInputNeverInvokesSegmentation()throws Exception{
        var empty=new BufferedImage(100,100,BufferedImage.TYPE_INT_ARGB);
        for(var policy:Policy.values())assertTrue(service.ensure(png(empty),policy,SegmentationEngine.Method.U2NET).rejected());
        verifyNoInteractions(segmentation);
        var suspicious=valid();suspicious.setRGB(0,50,0xff445566);output(suspicious);
        var result=service.ensure(png(new BufferedImage(100,100,BufferedImage.TYPE_INT_RGB)),Policy.AUTO,SegmentationEngine.Method.GRABCUT);
        assertTrue(result.needsReview());assertNotNull(result.segmentationId());verify(segmentation,times(1)).uploadBytes(any(),any(),isNull(),eq(0d));
    }
    @Test void reviewedAlphaAcceptsStructuralWarningButNeverOpaqueOrEmpty() throws Exception {
        var suspicious=valid();suspicious.setRGB(0,50,0xff445566);
        var result=service.ensure(png(suspicious),Policy.REVIEWED,null);
        assertEquals(Action.APPROVED_ALPHA,result.decision().action());assertFalse(result.needsReview());
        assertThrows(ProcessingException.class,()->service.ensure(png(new BufferedImage(100,100,BufferedImage.TYPE_INT_RGB)),Policy.REVIEWED,null));
        assertTrue(service.ensure(png(new BufferedImage(100,100,BufferedImage.TYPE_INT_ARGB)),Policy.REVIEWED,null).rejected());
        verifyNoInteractions(segmentation);
    }
    @Test void decodingAndRotationKeepAlphaWhileLegacyRgbDecodeStillFlattens()throws Exception{
        var image=valid();image.setRGB(19,20,0x806699aa);byte[] bytes=png(image);
        var decoded=Images.decodePreservingAlpha(bytes,12000000).image();assertEquals(0x806699aa,decoded.getRGB(19,20));
        var quarter=Images.rotate(decoded,90,12000000);assertEquals(0,quarter.getRGB(0,0));assertEquals(0x806699aa,quarter.getRGB(79,19));
        var arbitrary=Images.rotate(decoded,33,12000000);assertEquals(0,arbitrary.getRGB(0,0));assertTrue(arbitrary.getColorModel().hasAlpha());
        assertEquals(255,Images.decode(bytes,12000000).image().getRGB(0,0)>>>24);
    }
}
