package com.smartcloset.management.auth;

import com.smartcloset.management.ManagementProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;

public class HmacManagementAuthenticator implements ManagementAuthenticator {
    private static final String OPERATOR = "X-Operator";
    private final ManagementProperties properties;
    private final ReplayGuard replays;
    private final Clock clock;

    public HmacManagementAuthenticator(ManagementProperties properties, ReplayGuard replays, Clock clock) {
        this.properties = properties; this.replays = replays; this.clock = clock;
    }

    @Override public ManagementPrincipal authenticate(HttpServletRequest request, byte[] body, String requestId) {
        String keyId = request.getHeader("X-Closet-Key-Id"), timestamp = request.getHeader("X-Closet-Timestamp");
        String nonce = request.getHeader("X-Closet-Nonce"), signature = request.getHeader("X-Closet-Signature");
        String operator = request.getHeader(OPERATOR);
        if (keyId == null || timestamp == null || nonce == null || signature == null || !timestamp.matches("\\d{1,15}")
                || !nonce.matches("[A-Za-z0-9_-]{16,64}") || !signature.matches("[A-Za-z0-9_-]{20,128}"))
            throw new Failure(401, "MANAGEMENT_SIGNATURE_MISSING", "缺少或无法解析管理接口签名头。");
        if (operator != null && (operator.length() > 128 || operator.chars().anyMatch(Character::isISOControl)))
            throw new Failure(401, "MANAGEMENT_SIGNATURE_MISSING", "X-Operator 需为不超过 128 个字符的单行文本。");
        String secret = properties.secret(keyId);
        if (secret == null) throw new Failure(401, "MANAGEMENT_KEY_UNKNOWN", "未知的管理接口密钥。");
        long now = clock.millis();
        if (Math.abs(now - Long.parseLong(timestamp)) > properties.auth().clockSkew().toMillis())
            throw new Failure(401, "MANAGEMENT_TIMESTAMP_SKEWED", "请求时间与服务器时间相差过大。");
        // The signed request id is the header the caller sent, even if it was replaced locally for being malformed.
        String canonical = HmacCanonical.canonical(request.getMethod(), request.getRequestURI(), request.getQueryString(),
                timestamp, nonce, operator, request.getHeader("X-Request-Id"), body);
        byte[] expected = HmacCanonical.sign(secret, canonical).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.US_ASCII)))
            throw new Failure(401, "MANAGEMENT_SIGNATURE_INVALID", "管理接口签名不正确。");
        switch (replays.remember(keyId, nonce, now)) {
            case REPLAYED -> throw new Failure(401, "MANAGEMENT_REPLAYED", "该请求已处理过，请勿重放。");
            case FULL -> throw new Failure(503, "MANAGEMENT_BUSY", "管理接口繁忙，请稍后重试。");
            case FRESH -> { }
        }
        return new ManagementPrincipal(keyId, operator, requestId);
    }
}
