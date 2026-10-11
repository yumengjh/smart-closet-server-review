package com.smartcloset.storage;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Answers an image request after the controller has checked ownership. Local disk streams the file as before. A
 * bucket answers with a 302 to a presigned link, so the bytes come from the bucket instead of the server; a CORS
 * request (the outfit canvas loads with crossOrigin=use-credentials and reads pixels) is proxied instead, because a
 * credentialed cross-origin redirect turns the Origin into "null", which no bucket CORS rule can answer.
 */
@Component
public class StoredMedia {
    private static final long SHARED_MAX_AGE = 3600;
    private final BlobStore store;
    private final StorageSettings settings;

    public StoredMedia(BlobStore store, StorageSettings settings) { this.store = store; this.settings = settings; }

    public boolean local() { return store instanceof LocalBlobStore; }

    /** Garment and processing images: privately cacheable. */
    public ResponseEntity<?> shared(String key) throws IOException { return serve(key, false); }
    /** Own photos, person baselines and try-on renders: never cached. */
    public ResponseEntity<?> personal(String key) throws IOException { return serve(key, true); }

    private ResponseEntity<?> serve(String key, boolean personal) throws IOException {
        var type = MediaType.parseMediaType(BlobStore.contentType(key));
        if (store instanceof LocalBlobStore local && !personal) {
            var path = local.path(key);
            if (!java.nio.file.Files.isRegularFile(path)) throw missing();
            return ok(type, false).body(new FileSystemResource(path));
        }
        if (settings.redirect() && !corsRequest()) {
            var link = store.link(key, personal ? StorageSettings.PRIVATE_WINDOW : settings.urlWindow());
            if (link.isPresent()) {
                var redirect = ResponseEntity.status(HttpStatus.FOUND).location(link.get().uri());
                if (personal) return redirect.header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0").header("Pragma", "no-cache").build();
                // Cache the redirect no longer than the link works, keeping a minute of margin.
                long left = (link.get().expiresAtMs() - System.currentTimeMillis()) / 1000 - 60;
                return redirect.header(HttpHeaders.CACHE_CONTROL, "private, max-age=" + Math.max(0, Math.min(SHARED_MAX_AGE, left))).build();
            }
        }
        byte[] bytes = store.get(key);
        if (bytes == null) throw missing();
        return ok(type, personal).body(bytes);
    }

    private static ResponseEntity.BodyBuilder ok(MediaType type, boolean personal) {
        var builder = ResponseEntity.ok().contentType(type).header("X-Content-Type-Options", "nosniff");
        return personal ? builder.header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0").header("Pragma", "no-cache")
                : builder.header(HttpHeaders.CACHE_CONTROL, "private, max-age=" + SHARED_MAX_AGE);
    }

    private static boolean corsRequest() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) return false;
        HttpServletRequest request = servlet.getRequest();
        return request.getHeader(HttpHeaders.ORIGIN) != null;
    }

    private static com.smartcloset.image.ProcessingException missing() {
        return new com.smartcloset.image.ProcessingException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "没有找到这张图片。");
    }

}
