package com.smartcloset.storage;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Against a real S3-compatible service, e.g. a test COS bucket or MinIO/moto with signature checks on:
 * CLOSET_TEST_S3_ENDPOINT, _REGION, _BUCKET, _ACCESS_KEY, _SECRET_KEY, optional _PATH_STYLE. Skipped otherwise.
 * Writes under a random prefix and removes what it wrote.
 */
@EnabledIfEnvironmentVariable(named = "CLOSET_TEST_S3_ENDPOINT", matches = ".+")
class S3CompatibilityTest {
    @Test void roundTripOnARealService() throws Exception {
        String prefix = "closet-test-" + UUID.randomUUID() + "/";
        var settings = StorageSettings.of(k -> switch (k) {
            case "type" -> "s3";
            case "s3.prefix" -> prefix;
            default -> System.getenv("CLOSET_TEST_S3_" + k.substring(3).toUpperCase().replace('-', '_'));
        });
        var store = new S3BlobStore(settings);
        store.probe();
        byte[] png = new byte[300_000]; new java.util.Random(1).nextBytes(png);
        String key = "workflows/" + UUID.randomUUID() + "/裤子 step+1.png";
        try {
            store.put(key, png, "image/png");
            store.put("private-person/a.png", "x".getBytes(StandardCharsets.UTF_8), "image/png");
            assertArrayEquals(png, store.get(key));
            assertEquals(SigV4.hex(SigV4.sha256(png)), store.head(key).sha256());
            assertEquals(2, store.list("").size());
            assertEquals(1, store.list("private-person/").size());
            var link = store.link(key, Duration.ofMinutes(5)).orElseThrow();
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(link.uri()).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            assertArrayEquals(png, response.body());
        } finally {
            for (var e : store.list("")) store.delete(e.key());
        }
        assertTrue(store.list("").isEmpty());
    }
}
