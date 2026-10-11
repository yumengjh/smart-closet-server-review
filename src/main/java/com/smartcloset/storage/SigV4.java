package com.smartcloset.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** AWS Signature Version 4 for S3-compatible services (header signing and presigned query strings). */
final class SigV4 {
    static final String ALGORITHM = "AWS4-HMAC-SHA256";
    static final String UNSIGNED = "UNSIGNED-PAYLOAD";
    static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final String accessKey, secretKey, region;

    SigV4(String accessKey, String secretKey, String region) { this.accessKey = accessKey; this.secretKey = secretKey; this.region = region; }

    /**
     * The Authorization header value. {@code headers} holds lowercase names (including host) and must contain
     * x-amz-date and x-amz-content-sha256; {@code query} holds raw (unencoded) parameters.
     */
    String authorization(String method, String encodedPath, Map<String, String> query, Map<String, String> headers) {
        String amzDate = headers.get("x-amz-date");
        var sorted = new TreeMap<>(headers);
        String signed = String.join(";", sorted.keySet());
        String signature = signature(method, encodedPath, query, sorted, headers.get("x-amz-content-sha256"), amzDate);
        return ALGORITHM + " Credential=" + accessKey + "/" + scope(amzDate) + ", SignedHeaders=" + signed + ", Signature=" + signature;
    }

    /** Query string (already encoded) of a presigned GET that only signs the host header. */
    String presignedQuery(String method, String encodedPath, String host, Map<String, String> extra, Instant at, long expiresSeconds) {
        String amzDate = STAMP.format(at);
        var query = new TreeMap<String, String>(extra);
        query.put("X-Amz-Algorithm", ALGORITHM);
        query.put("X-Amz-Credential", accessKey + "/" + scope(amzDate));
        query.put("X-Amz-Date", amzDate);
        query.put("X-Amz-Expires", Long.toString(expiresSeconds));
        query.put("X-Amz-SignedHeaders", "host");
        String signature = signature(method, encodedPath, query, new TreeMap<>(Map.of("host", host)), UNSIGNED, amzDate);
        return canonicalQuery(query) + "&X-Amz-Signature=" + signature;
    }

    private String signature(String method, String path, Map<String, String> query, SortedMap<String, String> headers, String payload, String amzDate) {
        var canonical = new StringBuilder(method).append('\n').append(path).append('\n').append(canonicalQuery(query)).append('\n');
        headers.forEach((k, v) -> canonical.append(k).append(':').append(v.trim().replaceAll(" +", " ")).append('\n'));
        canonical.append('\n').append(String.join(";", headers.keySet())).append('\n').append(payload);
        String toSign = ALGORITHM + "\n" + amzDate + "\n" + scope(amzDate) + "\n" + hex(sha256(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), amzDate.substring(0, 8));
        key = hmac(key, region); key = hmac(key, "s3"); key = hmac(key, "aws4_request");
        return hex(hmac(key, toSign));
    }

    private String scope(String amzDate) { return amzDate.substring(0, 8) + "/" + region + "/s3/aws4_request"; }

    static String canonicalQuery(Map<String, String> query) {
        var encoded = new TreeMap<String, String>();
        query.forEach((k, v) -> encoded.put(encode(k, true), encode(v == null ? "" : v, true)));
        var out = new StringJoiner("&");
        encoded.forEach((k, v) -> out.add(k + "=" + v));
        return out.toString();
    }

    /** RFC 3986 encoding as S3 expects: unreserved characters kept, "/" kept only in paths. */
    static String encode(String value, boolean slash) {
        var out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~' || (c == '/' && !slash))
                out.append(c);
            else out.append('%').append(String.format("%02X", b & 0xff));
        }
        return out.toString();
    }

    static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
    private static byte[] hmac(byte[] key, String data) {
        try { var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(data.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
