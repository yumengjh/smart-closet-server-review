package com.smartcloset.security;

import static org.junit.jupiter.api.Assertions.*;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Registration, profile, devices, deactivation and in-app user administration (design 2026-10-19 用户账号). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=optional:file:./target/no-local-secrets.yml", "closet.model-path=target/absent.onnx",
        "closet.account.registration=INVITE", "closet.account.register-per-address-hourly=1000", "closet.account.login-max-failures-per-address=1000"})
class UserAccountApiTest {
    @TempDir static Path storage;
    @DynamicPropertySource static void config(DynamicPropertyRegistry p) { p.add("closet.storage-dir", () -> storage.toString()); }
    @LocalServerPort int port;
    @Autowired ClosetAccountService accounts;
    @Autowired RegistrationService registrations;
    @Autowired PortableLoginService portableLinks;
    @Autowired JdbcTemplate jdbc;
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String PASSWORD = "RegisteredPassword123!";

    final class Browser {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        final String userAgent;
        Browser(String userAgent) { this.userAgent = userAgent; }
        Browser() { this("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/130.0 Safari/537.36"); }
        HttpResponse<String> send(String method, String path, Object body) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("User-Agent", userAgent);
            if (!"GET".equals(method)) {
                var csrf = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/csrf")).build(), HttpResponse.BodyHandlers.ofString());
                request.header("Content-Type", "application/json").header("X-XSRF-TOKEN", JSON.readTree(csrf.body()).path("token").asText());
            }
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null); }
        HttpResponse<String> post(String path, Object body) throws Exception { return send("POST", path, body); }
        JsonNode ok(HttpResponse<String> response) { assertTrue(response.statusCode() / 100 == 2, response.statusCode() + " " + response.body()); return response.body().isEmpty() ? null : JSON.readTree(response.body()); }
        Browser login(String username, String password) throws Exception { ok(post("/api/auth/login", Map.of("username", username, "password", password))); return this; }
    }
    static String code(HttpResponse<String> response) { return JSON.readTree(response.body()).path("code").asText(); }
    static String name() { return "acc-" + UUID.randomUUID().toString().substring(0, 8); }
    static Map<String, Object> registration(String username, String code) {
        var body = new HashMap<String, Object>(Map.of("username", username, "password", PASSWORD, "displayName", "  小鱼  "));
        if (code != null) body.put("inviteCode", code);
        return body;
    }
    String admin() {
        String admin = name();
        accounts.create(admin, PASSWORD, "ADMIN", null, "ADMIN", null, false);
        return admin;
    }

    @Test void inviteRegistrationConsumesCodesAndSignsTheNewUserIn() throws Exception {
        var anonymous = new Browser();
        assertEquals("INVITE", anonymous.ok(anonymous.get("/api/auth/registration")).path("mode").asText());
        assertEquals("INVITE_REQUIRED", code(anonymous.post("/api/auth/register", registration(name(), null))));
        assertEquals("INVITE_INVALID", code(anonymous.post("/api/auth/register", registration(name(), "AAAA-BBBB-CCCC"))));

        var invite = registrations.createInvite("tester", "朋友", 2, 7);
        assertTrue(invite.code().matches("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}"));
        assertNotEquals(invite.code(), jdbc.queryForObject("SELECT code_hash FROM registration_invite WHERE id=?", String.class, invite.id()));
        String first = name();
        var browser = new Browser();
        var user = browser.ok(browser.post("/api/auth/register", registration(first, invite.code().toLowerCase().replace("-", " "))));
        assertEquals(first, user.path("username").asText());
        assertEquals("小鱼", user.path("displayName").asText());
        assertFalse(user.path("mustChangePassword").asBoolean());
        assertEquals(200, browser.get("/api/closet/garments?status=ACTIVE&page=1&size=1").statusCode());
        var profile = browser.ok(browser.get("/api/auth/profile"));
        assertEquals("INVITE", profile.path("registeredVia").asText());
        assertTrue(profile.path("lastLoginAtMs").asLong() > 0);

        var taken = new Browser().post("/api/auth/register", registration(first.toUpperCase(), invite.code()));
        assertEquals(409, taken.statusCode());
        assertEquals("USERNAME_TAKEN", code(taken));
        assertEquals(0, registrations.invites(100).stream().filter(i -> i.id().equals(invite.id())).findFirst().orElseThrow().usedCount() - 1,
                "a rejected registration must not consume the invite");
        new Browser().ok(new Browser().post("/api/auth/register", registration(name(), invite.code())));
        assertEquals("USED_UP", registrations.invite(invite.id()).status());
        assertEquals("INVITE_INVALID", code(new Browser().post("/api/auth/register", registration(name(), invite.code()))));

        var revoked = registrations.createInvite("tester", null, 1, 1);
        assertTrue(registrations.revokeInvite(revoked.id()));
        assertEquals("INVITE_INVALID", code(new Browser().post("/api/auth/register", registration(name(), revoked.code()))));
        var expired = registrations.createInvite("tester", null, 1, 1);
        jdbc.update("UPDATE registration_invite SET expires_at_ms=1 WHERE id=?", expired.id());
        assertEquals("EXPIRED", registrations.invite(expired.id()).status());
        assertEquals("INVITE_INVALID", code(new Browser().post("/api/auth/register", registration(name(), expired.code()))));
        var shortPassword = new HashMap<>(registration(name(), registrations.createInvite("tester", null, 1, 1).code()));
        shortPassword.put("password", "short");
        assertEquals(400, new Browser().post("/api/auth/register", shortPassword).statusCode());
    }

    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping mappings;

    /** Invite links must reach the Vue app; the bundle itself is only present after build:server, so check the route mapping. */
    @Test void inviteLinksOpenTheSinglePageAppAnonymously() throws Exception {
        var spaRoutes = new java.util.HashSet<String>();
        mappings.getHandlerMethods().forEach((info, method) -> {
            if (method.getBeanType() == com.smartcloset.api.ClosetSpaController.class) spaRoutes.addAll(info.getPatternValues());
        });
        assertTrue(spaRoutes.containsAll(java.util.List.of("/register", "/admin/users", "/login")), spaRoutes.toString());
        int status = new Browser().get("/register?invite=ABCD-EFGH-JKLM").statusCode();
        assertTrue(status != 401 && status != 403, "anonymous visitors must be able to open /register, got " + status);
    }

    @Test void concurrentRegistrationsNeverOverspendAnInvite() throws Exception {
        var invite = registrations.createInvite("tester", null, 3, 7);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var results = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 8; i++) results.add(executor.submit(() -> new Browser().post("/api/auth/register", registration(name(), invite.code())).statusCode()));
            int created = 0;
            for (var result : results) if (result.get() == 200) created++;
            assertEquals(3, created);
        }
        assertEquals(3, registrations.invite(invite.id()).usedCount());
    }

    @Test void failedLoginsAreUniformAndThrottledPerUsername() throws Exception {
        String user = name(); accounts.create(user, PASSWORD, "USER");
        String disabled = name(); accounts.create(disabled, PASSWORD, "USER"); accounts.setEnabled(disabled, false);
        var browser = new Browser();
        var wrong = browser.post("/api/auth/login", Map.of("username", user, "password", "WrongPassword123!"));
        var off = browser.post("/api/auth/login", Map.of("username", disabled, "password", PASSWORD));
        var unknown = browser.post("/api/auth/login", Map.of("username", name(), "password", PASSWORD));
        for (var response : java.util.List.of(wrong, off, unknown)) {
            assertEquals(401, response.statusCode(), response.body());
            assertEquals("账号或密码不正确。", JSON.readTree(response.body()).path("message").asText());
        }
        for (int i = 0; i < 4; i++) browser.post("/api/auth/login", Map.of("username", user, "password", "WrongPassword123!"));
        var throttled = browser.post("/api/auth/login", Map.of("username", user, "password", PASSWORD));
        assertEquals(429, throttled.statusCode());
        assertEquals("LOGIN_THROTTLED", code(throttled));
        assertTrue(throttled.headers().firstValue("Retry-After").isPresent());
    }

    @Test void profileDevicesAndSignOutOthers() throws Exception {
        String user = name(); accounts.create(user, PASSWORD, "USER", null, "ADMIN", null, false);
        var phone = new Browser("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Version/18.0 Mobile/15E148 Safari/604.1").login(user, PASSWORD);
        var laptop = new Browser().login(user, PASSWORD);
        var updated = laptop.ok(laptop.send("PATCH", "/api/auth/profile", Map.of("displayName", "  鱼搭用户  ")));
        assertEquals("鱼搭用户", updated.path("displayName").asText());
        assertEquals("鱼搭用户", laptop.ok(laptop.get("/api/auth/me")).path("displayName").asText());
        assertEquals(400, laptop.send("PATCH", "/api/auth/profile", Map.of("displayName", "x".repeat(41))).statusCode());
        assertEquals(400, laptop.send("PATCH", "/api/auth/profile", Map.of("displayName", "a\u0000b")).statusCode());
        var cleared = new HashMap<String, Object>(); cleared.put("displayName", null);
        assertTrue(laptop.ok(laptop.send("PATCH", "/api/auth/profile", cleared)).path("displayName").isNull());

        var devices = laptop.ok(laptop.get("/api/auth/sessions"));
        assertEquals(2, devices.size());
        assertTrue(devices.get(0).path("current").asBoolean());
        assertEquals("Chrome · macOS", devices.get(0).path("device").asText());
        assertEquals("Safari · iPhone", devices.get(1).path("device").asText());
        assertFalse(devices.toString().contains(jdbc.queryForObject("SELECT SESSION_ID FROM SPRING_SESSION WHERE PRINCIPAL_NAME=? LIMIT 1", String.class, user)));

        var link = portableLinks.create(user, PASSWORD);
        assertEquals(1, laptop.ok(laptop.send("DELETE", "/api/auth/sessions/others", null)).path("revokedSessions").asInt());
        assertEquals(401, phone.get("/api/auth/me").statusCode());
        assertEquals(200, laptop.get("/api/auth/me").statusCode());
        assertNull(portableLinks.consume(link.token()));
    }

    @Test void passwordChangeSignsOutOtherDevicesUnlessAskedNotTo() throws Exception {
        String user = name(); accounts.create(user, PASSWORD, "USER", null, "ADMIN", null, false);
        var other = new Browser().login(user, PASSWORD);
        var current = new Browser().login(user, PASSWORD);
        String next = "AnotherPassword456!";
        assertEquals(204, current.post("/api/auth/password", Map.of("currentPassword", PASSWORD, "newPassword", next, "signOutOthers", false)).statusCode());
        assertEquals(200, other.get("/api/auth/me").statusCode());
        assertEquals(204, current.post("/api/auth/password", Map.of("currentPassword", next, "newPassword", PASSWORD)).statusCode());
        assertEquals(401, other.get("/api/auth/me").statusCode());
        assertEquals(200, current.get("/api/auth/me").statusCode());
    }

    @Test void deactivationDisablesTheAccountKeepsDataAndAdminCanRestore() throws Exception {
        String user = name(); accounts.create(user, PASSWORD, "USER", null, "ADMIN", null, false);
        var browser = new Browser().login(user, PASSWORD);
        var other = new Browser().login(user, PASSWORD);
        assertEquals("INVALID_PASSWORD", code(browser.post("/api/auth/deactivate", Map.of("currentPassword", "WrongPassword123!"))));
        assertEquals(204, browser.post("/api/auth/deactivate", Map.of("currentPassword", PASSWORD)).statusCode());
        assertEquals(401, browser.get("/api/auth/me").statusCode());
        assertEquals(401, other.get("/api/auth/me").statusCode());
        assertEquals(401, new Browser().post("/api/auth/login", Map.of("username", user, "password", PASSWORD)).statusCode());
        assertNotNull(accounts.profile(user).deactivatedAtMs());

        var admin = new Browser().login(admin(), PASSWORD);
        var listed = admin.ok(admin.get("/api/admin/users?q=" + user)).path("items").get(0);
        assertFalse(listed.path("enabled").asBoolean());
        assertTrue(listed.path("deactivatedAtMs").asLong() > 0);
        admin.ok(admin.send("PATCH", "/api/admin/users/" + user + "/status", Map.of("enabled", true)));
        assertNull(accounts.profile(user).deactivatedAtMs());
        new Browser().login(user, PASSWORD);
    }

    @Test void lastAdministratorCannotDeactivate() throws Exception {
        String only = admin();
        jdbc.update("UPDATE closet_user SET enabled=0 WHERE role='ADMIN' AND username<>?", only);
        try {
            var browser = new Browser().login(only, PASSWORD);
            var response = browser.post("/api/auth/deactivate", Map.of("currentPassword", PASSWORD));
            assertEquals(409, response.statusCode());
            assertEquals("LAST_ADMIN_PROTECTED", code(response));
        } finally { jdbc.update("UPDATE closet_user SET enabled=1 WHERE role='ADMIN'"); }
    }

    @Test void portableSessionsCanEditProfileButNotDevicesOrAccounts() throws Exception {
        String user = admin();
        var link = portableLinks.create(user, PASSWORD);
        var browser = new Browser();
        browser.ok(browser.post("/api/auth/portable-login", Map.of("token", link.token())));
        assertEquals(200, browser.send("PATCH", "/api/auth/profile", Map.of("displayName", "外借")).statusCode());
        assertEquals(403, browser.get("/api/auth/sessions").statusCode());
        assertEquals(403, browser.send("DELETE", "/api/auth/sessions/others", null).statusCode());
        assertEquals(403, browser.post("/api/auth/deactivate", Map.of("currentPassword", PASSWORD)).statusCode());
        assertEquals(200, browser.get("/api/admin/users").statusCode());
        assertEquals(403, browser.post("/api/admin/invites", Map.of()).statusCode());
        assertEquals(403, browser.post("/api/admin/users", Map.of("username", name(), "role", "USER")).statusCode());
    }

    @Test void administratorsManageUsersAndInvitesInsideTheApp() throws Exception {
        String adminName = admin();
        String plain = name(); accounts.create(plain, PASSWORD, "USER", "普通用户", "ADMIN", null, false);
        assertEquals(403, new Browser().login(plain, PASSWORD).get("/api/admin/users").statusCode());
        var admin = new Browser().login(adminName, PASSWORD);

        String created = name();
        var credential = admin.ok(admin.post("/api/admin/users", Map.of("username", created, "role", "USER", "displayName", "新同学")));
        String temporary = credential.path("temporaryPassword").asText();
        assertEquals(20, temporary.length());
        assertEquals(409, admin.post("/api/admin/users", Map.of("username", created.toUpperCase(), "role", "USER")).statusCode());
        assertTrue(new Browser().login(created, temporary).ok(new Browser().login(created, temporary).get("/api/auth/me")).path("mustChangePassword").asBoolean());

        var byName = admin.ok(admin.get("/api/admin/users?q=" + java.net.URLEncoder.encode("新同", java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(1, byName.path("total").asInt());
        assertEquals(created, byName.path("items").get(0).path("username").asText());
        assertEquals("ADMIN", byName.path("items").get(0).path("registeredVia").asText());

        assertEquals("SELF_PROTECTED", code(admin.send("PATCH", "/api/admin/users/" + adminName + "/status", Map.of("enabled", false))));
        assertEquals("SELF_PROTECTED", code(admin.send("PATCH", "/api/admin/users/" + adminName + "/role", Map.of("role", "USER"))));
        admin.ok(admin.send("PATCH", "/api/admin/users/" + plain + "/role", Map.of("role", "ADMIN")));
        admin.ok(admin.send("PATCH", "/api/admin/users/" + plain + "/role", Map.of("role", "USER")));
        var reset = admin.ok(admin.post("/api/admin/users/" + plain + "/password-reset", Map.of()));
        new Browser().login(plain, reset.path("temporaryPassword").asText());
        assertEquals(404, admin.post("/api/admin/users/nobody-here/password-reset", Map.of()).statusCode());
        var quota = admin.ok(admin.send("PUT", "/api/admin/users/" + plain + "/quota", Map.of("imageDailyLimit", 50)));
        assertEquals(50, quota.path("image").path("limit").asInt());
        assertTrue(quota.path("image").path("overridden").asBoolean());
        assertEquals(400, admin.send("PUT", "/api/admin/users/" + plain + "/quota", Map.of("imageDailyLimit", 5000)).statusCode());
        admin.ok(admin.send("DELETE", "/api/admin/users/" + plain + "/sessions", null));
        var events = admin.ok(admin.get("/api/admin/users/" + plain + "/events"));
        var actions = new java.util.ArrayList<String>(); events.forEach(e -> actions.add(e.path("action").asText()));
        assertTrue(actions.containsAll(java.util.List.of("ADMIN_ROLE_CHANGED", "ADMIN_PASSWORD_RESET", "ADMIN_QUOTA_CHANGED", "ADMIN_SESSIONS_REVOKED", "LOGIN_SUCCEEDED")), actions.toString());
        assertEquals(adminName, events.get(0).path("actor").asText());
        assertFalse(events.toString().contains(reset.path("temporaryPassword").asText()));

        assertEquals("INVITE", admin.ok(admin.get("/api/admin/registration")).path("mode").asText());
        var invite = admin.ok(admin.post("/api/admin/invites", Map.of("note", "朋友", "maxUses", 2, "expiresInDays", 3)));
        assertEquals(adminName, invite.path("createdBy").asText());
        assertEquals("ACTIVE", invite.path("status").asText());
        String inviteCode = invite.path("code").asText();
        assertFalse(admin.ok(admin.get("/api/admin/invites")).toString().contains(inviteCode));
        assertEquals(400, admin.post("/api/admin/invites", Map.of("maxUses", 0)).statusCode());
        assertEquals(204, admin.send("DELETE", "/api/admin/invites/" + invite.path("id").asText(), null).statusCode());
        assertEquals(204, admin.send("DELETE", "/api/admin/invites/" + invite.path("id").asText(), null).statusCode());
        assertEquals(404, admin.send("DELETE", "/api/admin/invites/" + UUID.randomUUID(), null).statusCode());
        assertEquals("INVITE_INVALID", code(new Browser().post("/api/auth/register", registration(name(), inviteCode))));
    }
}
