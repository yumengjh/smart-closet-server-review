package com.smartcloset.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Default configuration: the management module must leave no beans and no routes behind. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=optional:file:./target/no-local-secrets.yml", "closet.model-path=target/absent.onnx"})
class ManagementDisabledTest {
    @TempDir static Path storage;
    @DynamicPropertySource static void config(DynamicPropertyRegistry p) { p.add("closet.storage-dir", () -> storage.toString()); }
    @LocalServerPort int port;
    @Autowired ApplicationContext context;

    @Test void noManagementBeansAreRegistered() {
        var leaked = Arrays.stream(context.getBeanDefinitionNames())
                .filter(name -> {
                    var type = context.getType(name);
                    return type != null && type.getName().startsWith("com.smartcloset.management");
                }).toList();
        assertTrue(leaked.isEmpty(), leaked.toString());
    }

    @Test void managementPathFallsBackToTheEndUserChain() throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/manage/v1/users"))
                .header("X-Closet-Key-Id", "primary").build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("AUTHENTICATION_REQUIRED"), response.body());
    }
}
