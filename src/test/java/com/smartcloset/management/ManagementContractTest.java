package com.smartcloset.management;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

/** docs/management-api.yaml is the single contract: implemented routes must match it exactly. */
@SpringBootTest(properties = {"spring.config.import=optional:file:./target/no-local-secrets.yml", "closet.model-path=target/absent.onnx",
        "closet.management.enabled=true", "closet.management.auth.keys[0].id=primary",
        "closet.management.auth.keys[0].secret=management-test-secret-0123456789abcdef"})
class ManagementContractTest {
    @TempDir static Path storage;
    @DynamicPropertySource static void config(DynamicPropertyRegistry p) { p.add("closet.storage-dir", () -> storage.toString()); }
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    @Test @SuppressWarnings("unchecked")
    void implementedRoutesMatchTheOpenApiContract() throws Exception {
        Map<String, Object> spec;
        try (var input = new FileInputStream("docs/management-api.yaml")) { spec = new Yaml().load(input); }
        String base = (String) ((Map<String, Object>) ((java.util.List<Object>) spec.get("servers")).getFirst()).get("url");
        Set<String> documented = new TreeSet<>();
        ((Map<String, Map<String, Object>>) spec.get("paths")).forEach((path, operations) -> operations.keySet().stream()
                .filter(m -> Set.of("get", "post", "put", "patch", "delete").contains(m))
                .forEach(m -> documented.add(m.toUpperCase() + " " + base + path)));
        Set<String> implemented = new TreeSet<>();
        mappings.getHandlerMethods().forEach((info, method) -> {
            if (!method.getBeanType().getPackageName().startsWith("com.smartcloset.management")) return;
            for (String path : info.getPatternValues()) for (var verb : info.getMethodsCondition().getMethods())
                implemented.add(verb.name() + " " + path.replaceAll("\\{([^}:]+)(:[^}]*)?}", "{$1}"));
        });
        assertEquals(documented, implemented);
    }
}
