package com.smartcloset.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/**
 * Any S3-compatible bucket (Tencent COS, MinIO, AWS S3, OSS in S3 mode): PUT, GET, HEAD, DELETE, ListObjectsV2 and
 * presigned GET, signed with SigV4 and sent by java.net.http. Every object carries x-amz-meta-sha256 so the
 * migration can tell an object already uploaded from one that differs.
 */
public class S3BlobStore implements BlobStore {
    /** Size and the sha256 recorded at upload (null when written by something else). */
    public record Head(long size, String sha256) {}

    private static final int ATTEMPTS = 3;
    private final StorageSettings settings;
    private final SigV4 signer;
    private final HttpClient http;
    private final Clock clock;

    public S3BlobStore(StorageSettings settings) { this(settings, Clock.systemUTC()); }
    S3BlobStore(StorageSettings settings, Clock clock) {
        settings.requireS3();
        this.settings = settings; this.clock = clock;
        this.signer = new SigV4(settings.accessKey(), settings.secretKey(), settings.region());
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public StorageSettings settings() { return settings; }

    @Override public void put(String key, byte[] bytes, String contentType) throws IOException {
        String sha = SigV4.hex(SigV4.sha256(bytes));
        var headers = new TreeMap<String, String>();
        headers.put("content-type", contentType);
        headers.put("x-amz-meta-sha256", sha);
        var response = send("PUT", objectPath(key), Map.of(), headers, bytes, sha);
        if (response.statusCode() / 100 != 2) throw failure("上传", key, response);
    }

    @Override public byte[] get(String key) throws IOException {
        var response = send("GET", objectPath(key), Map.of(), new TreeMap<>(), null, SigV4.EMPTY_SHA256);
        if (response.statusCode() == 404) return null;
        if (response.statusCode() / 100 != 2) throw failure("读取", key, response);
        return response.body();
    }

    @Override public boolean exists(String key) throws IOException { return head(key) != null; }

    /** Null when the object does not exist. */
    public Head head(String key) throws IOException {
        var response = send("HEAD", objectPath(key), Map.of(), new TreeMap<>(), null, SigV4.EMPTY_SHA256);
        if (response.statusCode() == 404) return null;
        if (response.statusCode() / 100 != 2) throw failure("查询", key, response);
        long size = response.headers().firstValueAsLong("content-length").orElse(-1);
        return new Head(size, response.headers().firstValue("x-amz-meta-sha256").orElse(null));
    }

    @Override public void delete(String key) throws IOException {
        var response = send("DELETE", objectPath(key), Map.of(), new TreeMap<>(), null, SigV4.EMPTY_SHA256);
        if (response.statusCode() != 404 && response.statusCode() / 100 != 2) throw failure("删除", key, response);
    }

    @Override public List<Entry> list(String prefix) throws IOException { return list(prefix, Integer.MAX_VALUE); }

    List<Entry> list(String prefix, int limit) throws IOException {
        var out = new ArrayList<Entry>();
        String token = null;
        do {
            var query = new TreeMap<String, String>();
            query.put("list-type", "2");
            query.put("prefix", settings.prefix() + prefix);
            query.put("max-keys", Integer.toString(Math.min(1000, limit - out.size())));
            if (token != null) query.put("continuation-token", token);
            var response = send("GET", bucketPath(), query, new TreeMap<>(), null, SigV4.EMPTY_SHA256);
            if (response.statusCode() / 100 != 2) throw failure("列举", prefix, response);
            var doc = xml(response.body());
            var contents = doc.getElementsByTagName("Contents");
            for (int i = 0; i < contents.getLength(); i++) {
                var item = (Element) contents.item(i);
                String key = text(item, "Key");
                if (key == null || !key.startsWith(settings.prefix())) continue;
                String modified = text(item, "LastModified");
                out.add(new Entry(key.substring(settings.prefix().length()), Long.parseLong(Objects.requireNonNullElse(text(item, "Size"), "0")),
                        modified == null ? 0 : Instant.parse(modified).toEpochMilli()));
            }
            token = "true".equals(text(doc.getDocumentElement(), "IsTruncated")) ? text(doc.getDocumentElement(), "NextContinuationToken") : null;
        } while (token != null && out.size() < limit);
        return out;
    }

    /** Fails with a readable reason when the bucket, region or keys are wrong. */
    public void probe() throws IOException {
        try { list("", 1); }
        catch (IOException e) { throw new IOException("无法访问存储桶 " + settings.bucket() + "（" + settings.endpoint() + "）：" + e.getMessage(), e); }
    }

    /**
     * Presigned GET. The signing time is rounded down to the window, so every request inside one window gets the
     * same link (browser cache hits); the link stays valid for two windows, i.e. at least one window after issue.
     */
    @Override public Optional<Link> link(String key, Duration window) {
        long w = window.toSeconds(), now = clock.instant().getEpochSecond();
        var at = Instant.ofEpochSecond(now / w * w);
        long expires = Math.min(2 * w, 604800);
        String path = objectPath(key);
        String query = signer.presignedQuery("GET", path, host(), Map.of(), at, expires);
        return Optional.of(new Link(URI.create(origin() + path + "?" + query), (at.getEpochSecond() + expires) * 1000));
    }

    @Override public String kind() { return "s3"; }

    private HttpResponse<byte[]> send(String method, String path, Map<String, String> query, TreeMap<String, String> extra, byte[] body, String payloadHash) throws IOException {
        StorageException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            var headers = new TreeMap<>(extra);
            headers.put("host", host());
            headers.put("x-amz-date", SigV4.STAMP.format(clock.instant()));
            headers.put("x-amz-content-sha256", payloadHash);
            String auth = signer.authorization(method, path, query, headers);
            String q = query.isEmpty() ? "" : "?" + SigV4.canonicalQuery(query);
            var builder = HttpRequest.newBuilder(URI.create(origin() + path + q)).timeout(Duration.ofSeconds(body != null && body.length > 4 << 20 ? 300 : 60))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body))
                    .header("Authorization", auth);
            headers.forEach((k, v) -> { if (!k.equals("host")) builder.header(k, v); });
            try {
                var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() < 500 && response.statusCode() != 429 || attempt == ATTEMPTS) return response;
                last = failure("请求", path, response);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); throw new StorageException("对象存储请求被中断", e);
            } catch (IOException e) { last = new StorageException("无法连接对象存储：" + e.getMessage(), e); }
            try { Thread.sleep(200L << (2 * (attempt - 1))); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new StorageException("对象存储请求被中断", e); }
        }
        throw last;
    }

    private String origin() {
        var e = settings.endpoint();
        return e.getScheme() + "://" + host();
    }
    private String host() {
        var e = settings.endpoint();
        String host = settings.pathStyle() ? e.getHost() : settings.bucket() + "." + e.getHost();
        return e.getPort() == -1 ? host : host + ":" + e.getPort();
    }
    private String bucketPath() { return settings.pathStyle() ? "/" + settings.bucket() : "/"; }
    private String objectPath(String key) {
        String encoded = SigV4.encode(settings.prefix() + BlobStore.checked(key), false);
        return settings.pathStyle() ? "/" + settings.bucket() + "/" + encoded : "/" + encoded;
    }

    private static StorageException failure(String action, String key, HttpResponse<byte[]> response) {
        String code = "", message = "";
        try {
            if (response.body() != null && response.body().length > 0) {
                var doc = xml(response.body());
                code = Objects.requireNonNullElse(text(doc.getDocumentElement(), "Code"), "");
                message = Objects.requireNonNullElse(text(doc.getDocumentElement(), "Message"), "");
            }
        } catch (IOException ignored) {}
        return new StorageException("对象存储" + action + "失败（" + key + "）：HTTP " + response.statusCode() + (code.isEmpty() ? "" : " " + code) + (message.isEmpty() ? "" : " " + message));
    }

    private static org.w3c.dom.Document xml(byte[] body) throws IOException {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
        } catch (Exception e) { throw new IOException("对象存储返回了无法解析的内容", e); }
    }
    private static String text(Element parent, String tag) {
        for (var n = parent.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element e && e.getTagName().equals(tag)) return e.getTextContent();
        return null;
    }
}
