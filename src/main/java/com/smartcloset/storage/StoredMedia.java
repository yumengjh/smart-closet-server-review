package com.smartcloset.storage;

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
 *
 * <p>Shared images also come in smaller copies ({@code ?w=320}, see {@link ImageVariants}) that lists and previews use;
 * those always come from the server. Local files and copies carry an ETag, so an expired cache costs a 304, not the image.
 */
@Component
public class StoredMedia {
    private static final long SHARED_MAX_AGE = 3600;
    /** Local files and copies revalidate by ETag, so they can stay fresh longer than a presigned redirect. */
    private static final long LOCAL_MAX_AGE = 86400;
    private final BlobStore store;
    private final StorageSettings settings;
    private final ImageVariants variants;

    public StoredMedia(BlobStore store, StorageSettings settings, ImageVariants variants) {
        this.store = store; this.settings = settings; this.variants = variants;
    }

    public boolean local() { return store instanceof LocalBlobStore; }

    /** Garment and processing images: privately cacheable. */
    public ResponseEntity<?> shared(String key) throws IOException { return serve(key, false); }
    /** A copy at most {@code width} wide when one is asked for, else the original. */
    public ResponseEntity<?> shared(String key, Integer width) throws IOException {
        if (width == null) return serve(key, false);
        if (width < 1) throw com.smartcloset.image.ProcessingException.invalid("图片宽度无效。");
        ImageVariants.Variant variant;
        try { variant = variants.variant(key, width, acceptsWebp()); }
        catch (com.smartcloset.image.ProcessingException e) { return serve(key, false); } // Undecodable: the original still shows.
        if (variant == null) throw missing();
        return file(variant.file(), MediaType.parseMediaType(variant.contentType()), variant.etag(), true);
    }
    /** Own photos, person baselines and try-on renders: never cached. */
    public ResponseEntity<?> personal(String key) throws IOException { return serve(key, true); }

    private ResponseEntity<?> serve(String key, boolean personal) throws IOException {
        var type = MediaType.parseMediaType(BlobStore.contentType(key));
        if (store instanceof LocalBlobStore local && !personal) {
            var path = local.path(key);
            String version = local.version(key);
            if (version == null) throw missing();
            return file(path, type, "\"" + version + "\"", false);
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

    private static ResponseEntity<?> file(java.nio.file.Path path, MediaType type, String etag, boolean negotiated) {
        var headers = new HttpHeaders();
        headers.setETag(etag);
        headers.setCacheControl("private, max-age=" + LOCAL_MAX_AGE);
        // The copy's format depends on Accept, so caches must keep one per format.
        if (negotiated) headers.setVary(java.util.List.of(HttpHeaders.ACCEPT));
        // Spring answers a matching If-None-Match with 304 from the ETag set here.
        return ResponseEntity.ok().headers(headers).contentType(type).header("X-Content-Type-Options", "nosniff").body(new FileSystemResource(path));
    }

    private static boolean acceptsWebp() {
        String accept = header(HttpHeaders.ACCEPT);
        return accept != null && accept.contains("image/webp");
    }

    private static String header(String name) {
        var attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest().getHeader(name) : null;
    }

    private static ResponseEntity.BodyBuilder ok(MediaType type, boolean personal) {
        var builder = ResponseEntity.ok().contentType(type).header("X-Content-Type-Options", "nosniff");
        return personal ? builder.header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0").header("Pragma", "no-cache")
                : builder.header(HttpHeaders.CACHE_CONTROL, "private, max-age=" + SHARED_MAX_AGE);
    }

    private static boolean corsRequest() { return header(HttpHeaders.ORIGIN) != null; }

    private static com.smartcloset.image.ProcessingException missing() {
        return new com.smartcloset.image.ProcessingException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "没有找到这张图片。");
    }

}
