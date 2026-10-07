package com.smartcloset.management;

import com.smartcloset.management.auth.HmacCanonical;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Minimal reference client for the management contract, mirroring what any host would implement. */
final class ManagementSigner {
    final HttpClient client = HttpClient.newHttpClient();
    final int port;
    final String keyId;
    final String secret;
    long clockOffsetMs;

    ManagementSigner(int port, String keyId, String secret) { this.port = port; this.keyId = keyId; this.secret = secret; }

    HttpResponse<String> send(String method, String pathAndQuery, String body) throws Exception {
        return send(method, pathAndQuery, body, nonce(), "test-admin");
    }

    HttpResponse<String> send(String method, String pathAndQuery, String body, String nonce, String operator) throws Exception {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        var uri = URI.create("http://127.0.0.1:" + port + pathAndQuery);
        String timestamp = Long.toString(System.currentTimeMillis() + clockOffsetMs), requestId = "req-" + nonce;
        String canonical = HmacCanonical.canonical(method, uri.getRawPath(), uri.getRawQuery(), timestamp, nonce, operator, requestId, bytes);
        var request = HttpRequest.newBuilder(uri)
                .header("X-Closet-Key-Id", keyId).header("X-Closet-Timestamp", timestamp).header("X-Closet-Nonce", nonce)
                .header("X-Closet-Signature", HmacCanonical.sign(secret, canonical)).header("X-Request-Id", requestId)
                .header("X-Operator", operator)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(bytes));
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String nonce() { return UUID.randomUUID().toString().replace("-", ""); }
}
