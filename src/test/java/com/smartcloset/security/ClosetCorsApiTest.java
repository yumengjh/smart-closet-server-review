package com.smartcloset.security;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=optional:file:./target/no-local-secrets.yml", "closet.model-path=target/absent.onnx",
        "closet.cors.allowed-origins=https://closet.example.com", "CLOSET_COOKIE_SECURE=true"})
class ClosetCorsApiTest {
    @TempDir static Path storage;
    @DynamicPropertySource static void config(DynamicPropertyRegistry p) { p.add("closet.storage-dir", () -> storage.toString()); }
    @LocalServerPort int port;
    @Autowired ClosetAccountService accounts;
    @Autowired PortableLoginService portableLinks;
    final HttpClient client = HttpClient.newHttpClient();
    final JsonMapper json = JsonMapper.builder().build();
    final Map<String, String> cookies = new LinkedHashMap<>();
    static final String ORIGIN = "https://closet.example.com";

    HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Origin", ORIGIN);
    }
    HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        if (!cookies.isEmpty()) request.header("Cookie", String.join("; ", cookies.values()));
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        for (String cookie : response.headers().allValues("set-cookie")) {
            String value = cookie.split(";", 2)[0]; cookies.put(value.split("=", 2)[0], value);
        }
        return response;
    }
    void cors(HttpResponse<String> response) {
        assertEquals(ORIGIN, response.headers().firstValue("access-control-allow-origin").orElseThrow());
        assertEquals("true", response.headers().firstValue("access-control-allow-credentials").orElseThrow());
        assertTrue(response.headers().allValues("vary").stream().anyMatch(v -> v.contains("Origin")));
    }
    HttpResponse<String> post(String path, Object body, boolean withCsrf) throws Exception {
        var request = request(path).header("Content-Type", "application/json");
        if (withCsrf) {
            String token = json.readTree(send(request("/api/auth/csrf").GET()).body()).path("token").asText();
            request.header("X-XSRF-TOKEN", token);
        }
        return send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))));
    }

    @Test void preflightDoesNotNeedASessionAndAllowsEveryClientTransport() throws Exception {
        for (String method : new String[]{"POST", "PUT", "PATCH", "DELETE"}) {
            var response = send(request("/api/closet/batches").header("Access-Control-Request-Method", method)
                    .header("Access-Control-Request-Headers", "content-type,x-xsrf-token,idempotency-key")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody()));
            assertEquals(200, response.statusCode(), response.body()); cors(response);
            assertTrue(response.headers().firstValue("access-control-allow-methods").orElseThrow().contains(method));
            assertTrue(response.headers().firstValue("access-control-allow-headers").orElseThrow().toLowerCase().contains("idempotency-key"));
        }
    }
    @Test void untrustedOriginsHeadersAndInternalCallbacksHaveNoCorsAccess() throws Exception {
        var evil = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/csrf"))
                .header("Origin", "https://evil.example.com").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, evil.statusCode()); assertTrue(evil.headers().firstValue("access-control-allow-origin").isEmpty());
        for (String path : new String[]{"/api/auth/login", "/api/agent/internal/tool"}) {
            var denied = send(request(path).header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "authorization").method("OPTIONS", HttpRequest.BodyPublishers.noBody()));
            assertEquals(403, denied.statusCode()); assertTrue(denied.headers().firstValue("access-control-allow-origin").isEmpty());
        }
    }
    @Test void crossOriginSessionAndCsrfPreserveBusinessAuthorizationAndCookieAttributes() throws Exception {
        var anonymous = send(request("/api/auth/me").GET()); assertEquals(401, anonymous.statusCode()); cors(anonymous);
        var csrf = send(request("/api/auth/csrf").GET()); cors(csrf);
        String xsrf = csrf.headers().allValues("set-cookie").stream().filter(s -> s.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow();
        assertTrue(xsrf.contains("Secure") && xsrf.contains("SameSite=Lax"), xsrf);
        assertFalse(xsrf.contains("Domain="), xsrf);
        String user = "cors-" + UUID.randomUUID().toString().substring(0, 8);
        accounts.create(user, "InitialPassword123!", "USER");
        var login = post("/api/auth/login", Map.of("username", user, "password", "InitialPassword123!"), true);
        assertEquals(200, login.statusCode(), login.body()); cors(login);
        String session = login.headers().allValues("set-cookie").stream().filter(s -> s.startsWith("YIXU_SESSION=")).findFirst().orElseThrow();
        assertTrue(session.contains("Secure") && session.contains("HttpOnly") && session.contains("SameSite=Lax"), session);
        assertFalse(session.contains("Domain="), session);
        var blocked = send(request("/api/closet/garments").GET()); assertEquals(403, blocked.statusCode()); cors(blocked);
        var noCsrf = post("/api/auth/password", Map.of("newPassword", "ChangedPassword456!"), false);
        assertEquals(403, noCsrf.statusCode()); cors(noCsrf); assertTrue(noCsrf.body().contains("CSRF_INVALID"));
        assertEquals(204, post("/api/auth/password", Map.of("currentPassword", "", "newPassword", "ChangedPassword456!"), true).statusCode());
        var garments = send(request("/api/closet/garments").GET()); assertEquals(200, garments.statusCode()); cors(garments);
        assertEquals(204, post("/api/auth/logout", Map.of(), true).statusCode());
        var expired = send(request("/api/auth/me").GET()); assertEquals(401, expired.statusCode()); cors(expired);
    }

    @Test void revokedPortableSessionsStillReturnReadableCrossOrigin401() throws Exception {
        String user = "revoke-" + UUID.randomUUID().toString().substring(0, 8);
        accounts.create(user, "ChangedPassword456!", "USER");
        accounts.changePassword(user, "", "ChangedPassword456!");
        var grant = portableLinks.create(user, "ChangedPassword456!");
        var redeemed = post("/api/auth/portable-login", Map.of("token", grant.token()), true);
        assertEquals(200, redeemed.statusCode()); cors(redeemed);
        assertTrue(portableLinks.revoke(user, grant.id()));
        var expired = send(request("/api/auth/me").GET());
        assertEquals(401, expired.statusCode()); cors(expired);
    }
}
