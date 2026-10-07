package com.smartcloset.management.auth;

import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * CLOSET-HMAC-SHA256 canonical request. Kept dependency-free so any host can port it;
 * see docs/management-api.yaml (securitySchemes.closetHmac).
 */
public final class HmacCanonical {
    public static final String ALGORITHM = "CLOSET-HMAC-SHA256";
    private HmacCanonical() {}

    public static String canonical(String method, String rawPath, String rawQuery, String timestamp, String nonce,
            String operator, String requestId, byte[] body) {
        return String.join("\n", ALGORITHM, method.toUpperCase(java.util.Locale.ROOT), rawPath, canonicalQuery(rawQuery),
                timestamp, nonce, operator == null ? "" : operator, requestId == null ? "" : requestId, sha256Hex(body));
    }

    /** Decodes form-style (+ as space) then re-encodes with RFC 3986; pairs sorted by encoded name, then encoded value. */
    public static String canonicalQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return "";
        var pairs = new ArrayList<String[]>();
        for (String part : rawQuery.split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String name = decode(eq < 0 ? part : part.substring(0, eq)), value = eq < 0 ? "" : decode(part.substring(eq + 1));
            pairs.add(new String[]{encode(name), encode(value)});
        }
        pairs.sort(Comparator.<String[], String>comparing(p -> p[0]).thenComparing(p -> p[1]));
        var joined = new ArrayList<String>();
        for (var p : pairs) joined.add(p[0] + "=" + p[1]);
        return String.join("&", joined);
    }

    public static String sign(String secret, String canonical) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("HmacSHA256 is unavailable", e); }
    }

    public static String sha256Hex(byte[] body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body == null ? new byte[0] : body)); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }

    public static String encode(String value) {
        var out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~')
                out.append((char) c);
            else out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16))).append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
        }
        return out.toString();
    }

    private static String decode(String value) {
        try { return URLDecoder.decode(value, StandardCharsets.UTF_8); }
        catch (IllegalArgumentException e) { return value; }
    }

    static byte[] readAll(java.io.InputStream input, int limit) throws java.io.IOException {
        var out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int read;
        while ((read = input.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            if (out.size() > limit) return null;
        }
        return out.toByteArray();
    }
}
