package com.smartcloset.storage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class S3BlobStoreTest {
    private final FakeS3 s3 = new FakeS3();
    S3BlobStoreTest() throws Exception {}
    @AfterEach void close() { s3.close(); }

    @Test void storesReadsListsAndDeletesWithSignedRequests() throws Exception {
        var store = new S3BlobStore(s3.settings("closet/"));
        store.probe();
        byte[] png = "png bytes".getBytes(StandardCharsets.UTF_8);
        store.put("workflows/a/step-1.png", png, "image/png");
        store.put("workflows/a/中文 名+1.json", "{}".getBytes(StandardCharsets.UTF_8), "application/json");
        store.put("private-person/x.png", png, "image/png");
        assertTrue(s3.objects.containsKey("closet/workflows/a/step-1.png"), "prefix is applied to the object key");
        assertEquals("image/png", s3.objects.get("closet/workflows/a/step-1.png").contentType());
        assertArrayEquals(png, store.get("workflows/a/step-1.png"));
        assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), store.get("workflows/a/中文 名+1.json"));
        assertNull(store.get("workflows/a/missing.png"));
        assertFalse(store.exists("workflows/a/missing.png"));
        var head = store.head("workflows/a/step-1.png");
        assertEquals(png.length, head.size());
        assertEquals(SigV4.hex(SigV4.sha256(png)), head.sha256());
        // Pages of two in the fake: continuation tokens must be followed, the prefix stripped.
        var keys = store.list("workflows/").stream().map(BlobStore.Entry::key).toList();
        assertEquals(java.util.List.of("workflows/a/step-1.png", "workflows/a/中文 名+1.json"), keys);
        assertEquals(3, store.list("").size());
        store.delete("workflows/a/step-1.png");
        store.delete("workflows/a/step-1.png");
        assertNull(store.get("workflows/a/step-1.png"));
    }

    @Test void retriesServerErrorsAndExplainsRefusals() throws Exception {
        var store = new S3BlobStore(s3.settings(""));
        s3.failNext.set(2);
        store.put("a/b.png", new byte[]{1}, "image/png");
        assertArrayEquals(new byte[]{1}, store.get("a/b.png"));
        var wrong = new S3BlobStore(StorageSettings.of(Map.of("type", "s3", "s3.endpoint", s3.endpoint(), "s3.region", FakeS3.REGION,
                "s3.bucket", FakeS3.BUCKET, "s3.access-key", FakeS3.ACCESS, "s3.secret-key", "wrong", "s3.path-style", "true")::get));
        var e = assertThrows(java.io.IOException.class, wrong::probe);
        assertTrue(e.getMessage().contains("403") && e.getMessage().contains("SignatureDoesNotMatch"), e.getMessage());
    }

    @Test void presignedLinksWorkAndStayIdenticalWithinTheWindow() throws Exception {
        var clock = new MutableClock(Instant.now());
        var store = new S3BlobStore(s3.settings("p/"), clock);
        store.put("workflows/j/step-0.png", new byte[]{7, 8}, "image/png");
        var first = store.link("workflows/j/step-0.png", Duration.ofHours(1)).orElseThrow();
        clock.now = clock.now.plusSeconds(1);
        var second = store.link("workflows/j/step-0.png", Duration.ofHours(1)).orElseThrow();
        if (clock.now.getEpochSecond() / 3600 == (clock.now.getEpochSecond() - 1) / 3600) assertEquals(first.uri(), second.uri());
        assertTrue(first.expiresAtMs() - System.currentTimeMillis() >= 3600_000 - 2000, "valid for at least one window after issue");
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(first.uri()).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertArrayEquals(new byte[]{7, 8}, response.body());
        var tampered = URI.create(first.uri().toString().replace("step-0", "step-1"));
        assertEquals(403, HttpClient.newHttpClient().send(HttpRequest.newBuilder(tampered).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
    }

    @Test void settingsRejectIncompleteOrWrongConfiguration() {
        var e = assertThrows(IllegalArgumentException.class, () -> StorageSettings.of(Map.of("type", "s3")::get));
        assertTrue(e.getMessage().contains("CLOSET_STORAGE_S3_BUCKET"));
        assertThrows(IllegalArgumentException.class, () -> StorageSettings.of(Map.of("type", "oss")::get));
        assertThrows(IllegalArgumentException.class, () -> StorageSettings.of(Map.of("type", "s3", "s3.endpoint", "https://b.cos.ap-guangzhou.myqcloud.com/bucket")::get));
        var local = StorageSettings.of(Map.<String, String>of()::get);
        assertFalse(local.s3());
        var cos = StorageSettings.fromEnv(Map.of("CLOSET_STORAGE_TYPE", "s3", "CLOSET_STORAGE_S3_ENDPOINT", "https://cos.ap-guangzhou.myqcloud.com",
                "CLOSET_STORAGE_S3_REGION", "ap-guangzhou", "CLOSET_STORAGE_S3_BUCKET", "closet-1250000000", "CLOSET_STORAGE_S3_ACCESS_KEY", "id",
                "CLOSET_STORAGE_S3_SECRET_KEY", "top-secret", "CLOSET_STORAGE_S3_PREFIX", "/closet", "CLOSET_STORAGE_S3_URL_TTL", "30m"));
        assertEquals("closet/", cos.prefix());
        assertEquals(Duration.ofMinutes(30), cos.urlWindow());
        assertFalse(cos.toString().contains("top-secret"));
        var link = new S3BlobStore(cos).link("workflows/a/b.png", Duration.ofHours(1)).orElseThrow().uri().toString();
        assertTrue(link.startsWith("https://closet-1250000000.cos.ap-guangzhou.myqcloud.com/closet/workflows/a/b.png?X-Amz-Algorithm="), link);
    }

    static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
