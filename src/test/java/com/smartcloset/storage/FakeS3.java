package com.smartcloset.storage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A path-style S3 endpoint for tests. It recomputes the SigV4 signature of every request from what actually arrived
 * on the wire (method, raw path, raw query, signed headers) and answers 403 on a mismatch, the way a real bucket does.
 */
public class FakeS3 implements AutoCloseable {
    public record Stored(byte[] bytes, String contentType, String sha256, long modified) {}
    public static final String ACCESS = "AKIDEXAMPLE", SECRET = "fake-secret/with+symbols", REGION = "ap-guangzhou", BUCKET = "closet-1250000000";
    public final Map<String, Stored> objects = new ConcurrentSkipListMap<>();
    public final AtomicInteger requests = new AtomicInteger(), failNext = new AtomicInteger();
    private final HttpServer server;
    private final SigV4 signer = new SigV4(ACCESS, SECRET, REGION);

    public FakeS3() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    public StorageSettings settings(String prefix) {
        return StorageSettings.of(Map.of("type", "s3", "s3.endpoint", endpoint(), "s3.region", REGION, "s3.bucket", BUCKET,
                "s3.access-key", ACCESS, "s3.secret-key", SECRET, "s3.prefix", prefix, "s3.path-style", "true")::get);
    }

    private void handle(HttpExchange x) throws IOException {
        requests.incrementAndGet();
        try (x) {
            byte[] body = x.getRequestBody().readAllBytes();
            if (failNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0) { reply(x, 503, error("SlowDown")); return; }
            String rawPath = x.getRequestURI().getRawPath();
            var query = query(x.getRequestURI().getRawQuery());
            if (!authorized(x, rawPath, query, body)) { reply(x, 403, error("SignatureDoesNotMatch")); return; }
            String prefix = "/" + BUCKET;
            if (!rawPath.startsWith(prefix)) { reply(x, 404, error("NoSuchBucket")); return; }
            String key = URLDecoder.decode(rawPath.substring(prefix.length()).replaceFirst("^/", "").replace("+", "%2B"), StandardCharsets.UTF_8);
            switch (x.getRequestMethod()) {
                case "PUT" -> {
                    objects.put(key, new Stored(body, x.getRequestHeaders().getFirst("content-type"), x.getRequestHeaders().getFirst("x-amz-meta-sha256"), System.currentTimeMillis()));
                    reply(x, 200, new byte[0]);
                }
                case "GET" -> {
                    if (key.isEmpty()) { list(x, query); return; }
                    var o = objects.get(key);
                    if (o == null) { reply(x, 404, error("NoSuchKey")); return; }
                    if (o.contentType() != null) x.getResponseHeaders().add("Content-Type", o.contentType());
                    reply(x, 200, o.bytes());
                }
                case "HEAD" -> {
                    var o = objects.get(key);
                    if (o == null) { x.sendResponseHeaders(404, -1); return; }
                    if (o.sha256() != null) x.getResponseHeaders().add("x-amz-meta-sha256", o.sha256());
                    x.getResponseHeaders().add("Content-Length", Integer.toString(o.bytes().length));
                    x.sendResponseHeaders(200, -1);
                }
                case "DELETE" -> { objects.remove(key); x.sendResponseHeaders(204, -1); }
                default -> reply(x, 405, error("MethodNotAllowed"));
            }
        }
    }

    private boolean authorized(HttpExchange x, String rawPath, Map<String, String> query, byte[] body) {
        String host = x.getRequestHeaders().getFirst("Host");
        if (query.containsKey("X-Amz-Signature")) {
            var at = SigV4.STAMP.parse(query.get("X-Amz-Date"), Instant::from);
            long expires = Long.parseLong(query.get("X-Amz-Expires"));
            if (Instant.now().isAfter(at.plusSeconds(expires))) return false;
            var extra = new TreeMap<>(query);
            extra.keySet().removeIf(k -> k.startsWith("X-Amz-"));
            String expected = signer.presignedQuery(x.getRequestMethod(), rawPath, host, extra, at, expires);
            return expected.endsWith("&X-Amz-Signature=" + query.get("X-Amz-Signature"));
        }
        String auth = x.getRequestHeaders().getFirst("Authorization");
        if (auth == null) return false;
        String signedHeaders = auth.replaceAll(".*SignedHeaders=([^,]+).*", "$1");
        var headers = new TreeMap<String, String>();
        for (String name : signedHeaders.split(";")) headers.put(name, Objects.requireNonNullElse(x.getRequestHeaders().getFirst(name), ""));
        String payload = headers.get("x-amz-content-sha256");
        if (!payload.equals(SigV4.hex(SigV4.sha256(body)))) return false;
        return signer.authorization(x.getRequestMethod(), rawPath, query, headers).equals(auth);
    }

    private void list(HttpExchange x, Map<String, String> q) throws IOException {
        String prefix = q.getOrDefault("prefix", "");
        int max = Integer.parseInt(q.getOrDefault("max-keys", "1000")), start = Integer.parseInt(q.getOrDefault("continuation-token", "0"));
        var keys = objects.keySet().stream().filter(k -> k.startsWith(prefix)).toList();
        var xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">");
        int end = Math.min(keys.size(), start + Math.min(max, 2)); // tiny pages exercise continuation tokens
        for (int i = start; i < end; i++) {
            var o = objects.get(keys.get(i));
            xml.append("<Contents><Key>").append(keys.get(i)).append("</Key><Size>").append(o.bytes().length)
               .append("</Size><LastModified>").append(Instant.ofEpochMilli(o.modified())).append("</LastModified></Contents>");
        }
        boolean truncated = end < keys.size();
        xml.append("<IsTruncated>").append(truncated).append("</IsTruncated>");
        if (truncated) xml.append("<NextContinuationToken>").append(end).append("</NextContinuationToken>");
        reply(x, 200, xml.append("</ListBucketResult>").toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> query(String raw) {
        var out = new TreeMap<String, String>();
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq), v = eq < 0 ? "" : part.substring(eq + 1);
            out.put(URLDecoder.decode(k.replace("+", "%2B"), StandardCharsets.UTF_8), URLDecoder.decode(v.replace("+", "%2B"), StandardCharsets.UTF_8));
        }
        return out;
    }
    private static byte[] error(String code) { return ("<Error><Code>" + code + "</Code><Message>fake</Message></Error>").getBytes(StandardCharsets.UTF_8); }
    private static void reply(HttpExchange x, int status, byte[] body) throws IOException {
        x.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) x.getResponseBody().write(body);
    }
    @Override public void close() { server.stop(0); }
}
