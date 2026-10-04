package com.smartcloset.security;

import com.smartcloset.SmartClosetApplication;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class ClosetSessionRestartTest {
    @TempDir Path storage;
    final JsonMapper json = JsonMapper.builder().build();
    CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
    String base;

    ConfigurableApplicationContext start() {
        var context = SpringApplication.run(SmartClosetApplication.class,
                "--server.port=0", "--closet.storage-dir=" + storage,
                "--spring.config.import=optional:file:./target/no-local-secrets.yml",
                "--closet.model-path=target/absent.onnx", "--logging.level.root=WARN");
        base = "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
        return context;
    }
    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> post(String path, Object body) throws Exception {
        String token = json.readTree(get("/api/auth/csrf").body()).path("token").asText();
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", token).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    String sessionCookie() {
        return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("YIXU_SESSION"))
                .findFirst().orElseThrow().getValue();
    }
    void assertCookieCannotAuthenticate(String value) throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + "/api/auth/me"))
                .header("Cookie", "YIXU_SESSION=" + value).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode(), response.body());
    }

    @Test void persistentLoginSurvivesServerAndBrowserRestartButLogoutAndExpiryStillRevokeIt() throws Exception {
        var credentials = Map.of("username", "session-restart", "password", "RestartTestPassword123!");
        try (var first = start()) {
            var accounts = first.getBean(ClosetAccountService.class);
            accounts.create(credentials.get("username"), credentials.get("password"), "USER");
            accounts.changePassword(credentials.get("username"), "", credentials.get("password"));
            var login = post("/api/auth/login", credentials);
            assertEquals(200, login.statusCode(), login.body());
            String cookieHeader = login.headers().allValues("set-cookie").stream()
                    .filter(value -> value.startsWith("YIXU_SESSION=")).findFirst().orElseThrow();
            assertTrue(cookieHeader.contains("Max-Age=604800"), cookieHeader);
            assertTrue(cookieHeader.contains("HttpOnly"), cookieHeader);
            assertTrue(cookieHeader.contains("SameSite=Lax"), cookieHeader);
            assertEquals(604800, first.getBean(JdbcTemplate.class).queryForObject(
                    "SELECT MAX_INACTIVE_INTERVAL FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?", Integer.class, credentials.get("username")));
        }
        // A fresh browser/client keeps only its cookies, not the old HTTP connection.
        var reopened = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        for (var cookie : cookies.getCookieStore().getCookies()) reopened.getCookieStore().add(URI.create(base), cookie);
        cookies = reopened;
        client = HttpClient.newBuilder().cookieHandler(cookies).build();
        try (var restarted = start()) {
            var me = get("/api/auth/me");
            assertEquals(200, me.statusCode(), me.body());
            assertEquals(credentials.get("username"), json.readTree(me.body()).path("username").asText());
            assertEquals(200, get("/api/closet/garments").statusCode());
            String old = sessionCookie();
            assertEquals(200, post("/api/auth/login", credentials).statusCode());
            assertNotEquals(old, sessionCookie(), "Reauthentication must rotate the session ID");
            assertCookieCannotAuthenticate(old);
            String loggedOut = sessionCookie();
            assertEquals(204, post("/api/auth/logout", Map.of()).statusCode());
            assertCookieCannotAuthenticate(loggedOut);
            assertEquals(401, get("/api/auth/me").statusCode());
            assertEquals(200, post("/api/auth/login", credentials).statusCode());
            restarted.getBean(JdbcTemplate.class).update("UPDATE SPRING_SESSION SET LAST_ACCESS_TIME=0, EXPIRY_TIME=0 WHERE PRINCIPAL_NAME=?", credentials.get("username"));
            assertEquals(401, get("/api/auth/me").statusCode());
        }
    }
}
