package com.smartcloset.api;

import com.smartcloset.generation.GenerationProperties;
import com.smartcloset.image.*;
import com.smartcloset.recognition.RecognitionProperties;
import com.smartcloset.security.ClosetOwnership;
import com.smartcloset.workflow.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalStickerOptionsTest {
    @Test void newIntakeForcesSegmentationWhileLegacyUploadKeepsAuto() throws Exception {
        var workflows=mock(WorkflowService.class); var job=mock(Workflow.Job.class);
        when(job.id()).thenReturn(UUID.randomUUID()); when(workflows.submit(any(),any(),any())).thenReturn(job);
        var controller=new ClosetProcessingController(workflows,mock(GenerationProperties.class),mock(RecognitionProperties.class),mock(ClosetOwnership.class));
        var file=new MockMultipartFile("file","photo.png","image/png",new byte[]{1});
        for(var policy:new TransparencyService.Policy[]{TransparencyService.Policy.FORCE,null})
            controller.create(UUID.randomUUID(),file,new ClosetProcessingController.UploadOptions("本地贴纸",true,false,true,0d,null,null,null,null,policy));
        var request=ArgumentCaptor.forClass(Workflow.Request.class); verify(workflows,times(2)).submit(any(),request.capture(),any());
        assertEquals(TransparencyService.Policy.FORCE,request.getAllValues().get(0).steps().getFirst().policy());
        assertEquals(TransparencyService.Policy.AUTO,request.getAllValues().get(1).steps().getFirst().policy());
        assertTrue(request.getAllValues().stream().allMatch(r->r.steps().stream().noneMatch(s->s.type()==Workflow.Type.GENERATE||s.type()==Workflow.Type.RECOGNIZE)));
        assertThrows(ProcessingException.class,()->controller.create(UUID.randomUUID(),file,new ClosetProcessingController.UploadOptions("本地贴纸",true,false,true,0d,null,null,null,null,TransparencyService.Policy.REVIEWED)));
    }
}
