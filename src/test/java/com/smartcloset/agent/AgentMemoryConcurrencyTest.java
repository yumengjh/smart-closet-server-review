package com.smartcloset.agent;

import com.smartcloset.security.ClosetAccountService;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.config.import=optional:file:./target/no-local-secrets.yml","closet.model-path=target/absent-memory-model.onnx"})
class AgentMemoryConcurrencyTest {
    @TempDir static Path storage;
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("closet.storage-dir",()->storage.toString());}
    @Autowired ClosetAccountService accounts;
    @MockitoSpyBean AgentMemoryDocuments memories;

    @Test void grantAndSensitiveBodyCannotStraddleConcurrentEdit() throws Exception {
        String owner="memory-race-"+UUID.randomUUID().toString().substring(0,8);
        accounts.create(owner,"local-memory-test-password","USER");
        var initial=memories.get(owner,"SENSITIVE");
        var saved=memories.save(owner,"SENSITIVE",new AgentMemoryDocuments.Edit(initial.revision(),"身高 170"));
        memories.authorize(owner,"OUTFIT",saved.revision());
        var reading=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean();
        doAnswer(call->{
            if(once.compareAndSet(false,true)){reading.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));}
            return call.callRealMethod();
        }).when(memories).get(owner,"SENSITIVE");
        try(var workers=Executors.newFixedThreadPool(2)){
            var snapshot=workers.submit(()->memories.modelInput(owner,Set.of("OUTFIT")));
            assertTrue(reading.await(5,TimeUnit.SECONDS));
            var changing=new CountDownLatch(1);
            var edit=workers.submit(()->{changing.countDown();return memories.save(owner,"SENSITIVE",new AgentMemoryDocuments.Edit(saved.revision(),"身高 185"));});
            assertTrue(changing.await(5,TimeUnit.SECONDS));
            try{assertThrows(TimeoutException.class,()->edit.get(200,TimeUnit.MILLISECONDS));}
            finally{release.countDown();}
            var old=snapshot.get(5,TimeUnit.SECONDS);
            assertTrue(old.get("sensitiveFragments").toString().contains("170"));
            assertFalse(old.get("sensitiveFragments").toString().contains("185"));
            edit.get(5,TimeUnit.SECONDS);
            assertTrue(memories.permissions(owner).isEmpty());
            assertEquals(java.util.Map.of(),memories.modelInput(owner,Set.of("OUTFIT")).get("sensitiveFragments"));
        }finally{release.countDown();reset(memories);}
    }
}
