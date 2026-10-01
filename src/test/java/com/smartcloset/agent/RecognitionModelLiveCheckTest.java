package com.smartcloset.agent;
import com.smartcloset.generation.GenerationProperties;
import com.smartcloset.recognition.*;
import java.net.URI;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="CLOSET_AGENT_LIVE_VERIFY",matches="true")
class RecognitionModelLiveCheckTest {
 @Test void realPublicSampleUsesExistingRecognitionSchema()throws Exception {
  var json=JsonMapper.builder().build();String model=System.getenv().getOrDefault("CLOSET_AGENT_MODEL","gpt-6-luna");
  var credential=new GenerationProperties.Provider(GenerationProperties.Protocol.OPENAI_IMAGES,URI.create(System.getenv("CLOSET_CHECK_BASE_URL")),System.getenv("CLOSET_CHECK_API_KEY"),model,Map.of(),java.util.List.of(),120);
  var properties=new RecognitionProperties("yumg",Map.of("yumg",new RecognitionProperties.Provider("yumg",RecognitionProperties.Protocol.OPENAI_CHAT,model,4096,"low")));
  var service=new RecognitionService(properties,new GenerationProperties(Map.of("yumg",credential)),new RecognitionSchema(json),json);
  var result=service.recognize(service.prepare(null),Files.readAllBytes(Path.of("samples/white-shirt-light.jpg")));
  assertNotNull(result.result());assertEquals(RecognitionSchema.FIELDS.size(),result.result().attributes().size());
  System.out.println("LIVE_RECOGNITION model="+model+" schema=garment-v2 fields="+result.result().attributes().size()+" assessment="+result.result().assessment()+" usage="+result.usage());
 }
}
