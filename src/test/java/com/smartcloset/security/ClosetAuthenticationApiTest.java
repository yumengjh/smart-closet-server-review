package com.smartcloset.security;

import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=optional:file:./target/no-local-secrets.yml", "closet.model-path=target/absent.onnx"})
class ClosetAuthenticationApiTest {
    @TempDir static Path storage;
    @DynamicPropertySource static void config(DynamicPropertyRegistry p) { p.add("closet.storage-dir", () -> storage.toString()); }
    @LocalServerPort int port;
    @Autowired ClosetAccountService accounts;
    final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    final JsonMapper json = JsonMapper.builder().build();
    final String initialPassword = "InitialTestPassword123!";
    final String newPassword = "ChangedTestPassword456!";

    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> post(String path, Object body, boolean csrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", "application/json");
        if (csrf) request.header("X-XSRF-TOKEN", json.readTree(get("/api/auth/csrf").body()).path("token").asText());
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    String login() throws Exception {
        String username = "auth-" + UUID.randomUUID().toString().substring(0, 8);
        accounts.create(username, initialPassword, "USER");
        var response = post("/api/auth/login", Map.of("username", username, "password", initialPassword), true);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(json.readTree(response.body()).path("mustChangePassword").asBoolean());
        return username;
    }
    void changePassword() throws Exception {
        var response = post("/api/auth/password", Map.of("currentPassword", "", "newPassword", newPassword), true);
        assertEquals(204, response.statusCode(), response.body());
    }
    @Test void anonymousBusinessRequestsReturnExplicit401InsteadOf403() throws Exception {
        for (String path : new String[]{"/api/auth/me", "/api/closet/garments", "/api/closet/tasks", "/api/recognition/schema", "/api/closet/metadata-options"}) {
            var response = get(path);
            assertEquals(401, response.statusCode(), path + ": " + response.body());
            assertEquals("AUTHENTICATION_REQUIRED", json.readTree(response.body()).path("code").asText());
        }
    }
    @Test void firstLoginBlocksBusinessWithReasonAndPasswordChangeUnlocksScreenshotEndpoints() throws Exception {
        login();
        String[] endpoints = {"/api/closet/garments?status=ACTIVE&page=1&size=24", "/api/closet/garments?status=DRAFT&page=1&size=24", "/api/closet/tasks?page=1&size=50", "/api/recognition/schema", "/api/closet/quota", "/api/closet/metadata-options"};
        for (String path : endpoints) {
            var response = get(path);
            assertEquals(403, response.statusCode(), path + ": " + response.body());
            assertEquals("PASSWORD_CHANGE_REQUIRED", json.readTree(response.body()).path("code").asText());
        }
        assertEquals(400, post("/api/auth/password", Map.of("newPassword", "short", "currentPassword", ""), true).statusCode());
        changePassword();
        assertFalse(json.readTree(get("/api/auth/me").body()).path("mustChangePassword").asBoolean());
        for (String path : endpoints) assertEquals(200, get(path).statusCode(), path);
    }
    @Test void logoutAndCsrfFailuresHaveDistinctResponsesWithoutOpeningBusinessPermissions() throws Exception {
        login(); changePassword();
        var denied = post("/api/auth/logout", Map.of(), false);
        assertEquals(403, denied.statusCode());
        assertEquals("CSRF_INVALID", json.readTree(denied.body()).path("code").asText());
        assertEquals(403, get("/workflow/index.html").statusCode());
        assertEquals(204, post("/api/auth/logout", Map.of(), true).statusCode());
        assertEquals(401, get("/api/closet/garments").statusCode());
    }

    @Autowired PortableLoginService portableLinks;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    HttpClient recipient() { return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build(); }
    HttpResponse<String> recipientPost(HttpClient browser, String path, Object body) throws Exception {
        var csrf = browser.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
        return browser.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json").header("X-XSRF-TOKEN", json.readTree(csrf.body()).path("token").asText())
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> recipientMe(HttpClient browser) throws Exception {
        return browser.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/me")).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void portableLoginIsSingleUseAndOwnerCanRevokeAnAlreadyRedeemedSession() throws Exception {
        String owner = login(); changePassword();
        assertEquals(400, post("/api/auth/portable-links", Map.of("currentPassword", "wrong"), true).statusCode());
        assertEquals(403, post("/api/auth/portable-links", Map.of("currentPassword", newPassword), false).statusCode());
        var issued = post("/api/auth/portable-links", Map.of("currentPassword", newPassword), true);
        assertEquals(200, issued.statusCode(), issued.body());
        String token = json.readTree(issued.body()).path("token").asText();
        String id = json.readTree(issued.body()).path("id").asText();
        assertEquals(43, token.length());
        assertNotEquals(token, jdbc.queryForObject("SELECT token_hash FROM portable_login_link WHERE id=?", String.class, id));
        var browser = recipient();
        var exchange = recipientPost(browser, "/api/auth/portable-login", Map.of("token", token));
        assertEquals(200, exchange.statusCode());
        assertEquals(owner, json.readTree(exchange.body()).path("username").asText());
        assertTrue(json.readTree(recipientMe(browser).body()).path("portableLogin").asBoolean());
        assertEquals(401, recipientPost(recipient(), "/api/auth/portable-login", Map.of("token", token)).statusCode());
        assertEquals(403, recipientPost(browser, "/api/auth/password", Map.of("currentPassword", newPassword, "newPassword", initialPassword)).statusCode());
        assertEquals(403, recipientPost(browser, "/api/auth/portable-links", Map.of("currentPassword", newPassword)).statusCode());
        assertFalse(portableLinks.revoke("another-owner", id));
        assertTrue(portableLinks.revoke(owner, id));
        assertEquals(401, recipientMe(browser).statusCode());
    }
    @Test void passwordChangesAndAbsoluteDeadlineInvalidatePortableGrants() throws Exception {
        String owner = login(); changePassword();
        var link = portableLinks.create(owner, newPassword);
        var browser = recipient();
        assertEquals(200, recipientPost(browser, "/api/auth/portable-login", Map.of("token", link.token())).statusCode());
        jdbc.update("UPDATE portable_login_link SET consumed_at_ms=? WHERE id=?", System.currentTimeMillis() - 25 * 60 * 60 * 1000L, link.id());
        assertEquals(401, recipientMe(browser).statusCode());
        var pending = portableLinks.create(owner, newPassword);
        accounts.changePassword(owner, newPassword, initialPassword);
        assertNull(portableLinks.consume(pending.token()));
        var expired = portableLinks.create(owner, initialPassword);
        jdbc.update("UPDATE portable_login_link SET expires_at_ms=0 WHERE id=?", expired.id());
        assertNull(portableLinks.consume(expired.token()));
    }
    @Test void simultaneousRedemptionHasExactlyOneWinner() throws Exception {
        String owner = login(); changePassword(); var link = portableLinks.create(owner, newPassword);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(6)) {
            var ready = new java.util.concurrent.CountDownLatch(1);
            var futures = java.util.stream.IntStream.range(0, 6).mapToObj(i -> executor.submit(() -> { ready.await(); return portableLinks.consume(link.token()); })).toList();
            ready.countDown(); int winners = 0;
            for (var future : futures) if (future.get() != null) winners++;
            assertEquals(1, winners);
        }
    }
}
