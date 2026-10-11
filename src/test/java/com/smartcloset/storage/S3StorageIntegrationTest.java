package com.smartcloset.storage;

import com.smartcloset.person.PrivateMedia;
import com.smartcloset.security.ClosetAccountService;
import com.smartcloset.workflow.WorkflowFiles;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.junit.jupiter.api.Assertions.*;

/** The whole application on a bucket: images are written there, never to the storage directory, and served by link. */
@SpringBootTest(properties = {"spring.config.import=optional:file:./target/no-local-secrets.yml", "closet.model-path=target/absent-s3-model.onnx"})
class S3StorageIntegrationTest {
    @TempDir static Path storage;
    static final FakeS3 s3;
    static { try { s3 = new FakeS3(); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @AfterAll static void stop() { s3.close(); }

    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        r.add("closet.storage-dir", storage::toString);
        r.add("closet.storage.type", () -> "s3");
        r.add("closet.storage.s3.endpoint", s3::endpoint);
        r.add("closet.storage.s3.region", () -> FakeS3.REGION);
        r.add("closet.storage.s3.bucket", () -> FakeS3.BUCKET);
        r.add("closet.storage.s3.access-key", () -> FakeS3.ACCESS);
        r.add("closet.storage.s3.secret-key", () -> FakeS3.SECRET);
        r.add("closet.storage.s3.prefix", () -> "it/");
        r.add("closet.storage.s3.path-style", () -> "true");
    }

    @Autowired BlobStore store;
    @Autowired WorkflowFiles files;
    @Autowired PrivateMedia media;
    @Autowired StoredMedia served;
    @Autowired ClosetAccountService accounts;

    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    static byte[] png() throws Exception {
        var image = new BufferedImage(64, 80, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics(); g.setColor(java.awt.Color.RED); g.fillRect(10, 10, 30, 50); g.dispose();
        var out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out); return out.toByteArray();
    }

    @Test void garmentImagesLiveInTheBucketAndAreServedByLink() throws Exception {
        assertInstanceOf(S3BlobStore.class, store);
        UUID job = UUID.randomUUID(); byte[] png = png();
        var original = files.originalAsset(job, png);
        var step = files.write(job, "step-1.png", "SEGMENT", png);
        files.data(job, "quick-quality.json", "{}".getBytes());
        assertTrue(s3.objects.containsKey("it/workflows/" + job + "/original.png"));
        assertTrue(s3.objects.containsKey("it/workflows/" + job + "/step-1.png"));
        assertTrue(files.dataExists(job, "quick-quality.json"));
        assertFalse(java.nio.file.Files.exists(storage.resolve("workflows/" + job)), "no image is written to the local directory");
        assertArrayEquals(png, files.readVerified(step));
        assertArrayEquals(png, files.readVerified(original));

        // <img> without Origin: a redirect the browser follows straight to the bucket.
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest("GET", "/api/workflows/" + job + "/files/step-1.png")));
        var redirect = served.shared(files.key(job, "step-1.png"));
        assertEquals(302, redirect.getStatusCode().value());
        String cache = redirect.getHeaders().getCacheControl();
        assertTrue(cache.startsWith("private, max-age="), cache);
        var location = redirect.getHeaders().getLocation();
        assertTrue(location.toString().startsWith(s3.endpoint() + "/" + FakeS3.BUCKET + "/it/workflows/" + job + "/step-1.png?X-Amz-"), location.toString());
        var fetched = HttpClient.newHttpClient().send(HttpRequest.newBuilder(location).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertArrayEquals(png, fetched.body());

        // Canvas (CORS) request: the server streams the bytes itself.
        var cors = new MockHttpServletRequest("GET", "/api/workflows/" + job + "/files/step-1.png");
        cors.addHeader("Origin", "https://closet.example");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(cors));
        var proxied = served.shared(files.key(job, "step-1.png"));
        assertEquals(200, proxied.getStatusCode().value());
        assertArrayEquals(png, (byte[]) proxied.getBody());
    }

    @Test void personalPhotosAreNeverCachedAndDeletionRemovesTheObject() throws Exception {
        String owner = "s3-" + UUID.randomUUID().toString().substring(0, 8);
        accounts.create(owner, "test-password-123", "USER");
        var m = media.put(owner, "BASELINE", png());
        String key = "it/private-person/" + m.id() + ".png";
        assertTrue(s3.objects.containsKey(key));
        assertTrue(media.read(owner, m.id()).length > 0);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest("GET", m.url())));
        var redirect = served.personal(media.activeKey(owner, m.id()));
        assertEquals(302, redirect.getStatusCode().value());
        assertEquals("private, no-store, max-age=0", redirect.getHeaders().getCacheControl());
        String other = "s3-" + UUID.randomUUID().toString().substring(0, 8);
        accounts.create(other, "test-password-123", "USER");
        assertThrows(com.smartcloset.image.ProcessingException.class, () -> media.activeKey(other, m.id()));
        media.delete(owner, m.id());
        assertFalse(s3.objects.containsKey(key));
        assertThrows(com.smartcloset.image.ProcessingException.class, () -> media.activeKey(owner, m.id()));
        assertTrue(java.nio.file.Files.isRegularFile(storage.resolve("private-person/deletion-ledger/" + m.id())), "the tombstone stays local");
    }
}
