package com.smartcloset.management;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcloset.security.ClosetAccountService;
import com.smartcloset.security.PortableLoginService;
import com.smartcloset.security.QuotaPolicy;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=optional:file:./target/no-local-secrets.yml", "closet.model-path=target/absent.onnx",
        "closet.management.enabled=true", "closet.management.auth.keys[0].id=primary",
        "closet.management.auth.keys[0].secret=management-test-secret-0123456789abcdef",
        "closet.management.auth.keys[1].id=next", "closet.management.auth.keys[1].secret=rotated-management-secret-0123456789",
        "closet.account.register-per-address-hourly=1000"})
class ManagementUserApiTest {
    static final String SECRET = "management-test-secret-0123456789abcdef";
    static final String PASSWORD = "InitialTestPassword123!";
    @TempDir static Path storage;
    @DynamicPropertySource static void config(DynamicPropertyRegistry p) { p.add("closet.storage-dir", () -> storage.toString()); }
    @LocalServerPort int port;
    @Autowired ClosetAccountService accounts;
    @Autowired PortableLoginService portableLinks;
    @Autowired JdbcTemplate jdbc;
    final JsonMapper json = JsonMapper.builder().build();
    ManagementSigner api;

    @BeforeEach void signer() { api = new ManagementSigner(port, "primary", SECRET); }

    String user() {
        String name = "u-" + UUID.randomUUID().toString().substring(0, 8);
        accounts.create(name, PASSWORD, "USER");
        return name;
    }
    JsonNode body(HttpResponse<String> response) { return json.readTree(response.body()); }
    void problem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"), response.headers().toString());
        assertEquals(code, body(response).path("code").asText(), response.body());
        assertTrue(body(response).path("type").asText().startsWith("urn:closet:problem:"));
    }

    /** Signs in through the real end-user flow and returns a client holding that session. */
    HttpClient signIn(String username, String password) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        var csrf = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/csrf")).build(), HttpResponse.BodyHandlers.ofString());
        String token = json.readTree(csrf.body()).path("token").asText();
        var login = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("username", username, "password", password)))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode(), login.body());
        return client;
    }
    int me(HttpClient client) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/me")).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test void rejectsUnsignedForgedSkewedUnknownAndReplayedRequests() throws Exception {
        var plain = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/manage/v1/users")).build(),
                HttpResponse.BodyHandlers.ofString());
        problem(plain, 401, "MANAGEMENT_SIGNATURE_MISSING");
        problem(new ManagementSigner(port, "primary", "wrong-secret-0123456789abcdef-wrong").send("GET", "/api/manage/v1/users", null), 401, "MANAGEMENT_SIGNATURE_INVALID");
        problem(new ManagementSigner(port, "ghost", SECRET).send("GET", "/api/manage/v1/users", null), 401, "MANAGEMENT_KEY_UNKNOWN");
        var skewed = new ManagementSigner(port, "primary", SECRET); skewed.clockOffsetMs = 301_000;
        problem(skewed.send("GET", "/api/manage/v1/users", null), 401, "MANAGEMENT_TIMESTAMP_SKEWED");
        String nonce = ManagementSigner.nonce();
        assertEquals(200, api.send("GET", "/api/manage/v1/users", null, nonce, "a").statusCode());
        problem(api.send("GET", "/api/manage/v1/users", null, nonce, "a"), 401, "MANAGEMENT_REPLAYED");
        assertEquals(200, new ManagementSigner(port, "next", "rotated-management-secret-0123456789").send("GET", "/api/manage/v1/users", null).statusCode());
    }

    @Test void createsUsersWithOneTimePasswordAndRejectsDuplicatesAndUnknownFields() throws Exception {
        String name = "new-" + UUID.randomUUID().toString().substring(0, 8);
        var created = api.send("POST", "/api/manage/v1/users", json.writeValueAsString(Map.of("username", name, "role", "USER")));
        assertEquals(201, created.statusCode(), created.body());
        assertEquals("/api/manage/v1/users/" + name, created.headers().firstValue("Location").orElseThrow());
        String temporary = body(created).path("temporaryPassword").asText();
        assertEquals(20, temporary.length());
        assertTrue(accounts.find(name).mustChangePassword());
        problem(api.send("POST", "/api/manage/v1/users", json.writeValueAsString(Map.of("username", name, "role", "USER"))), 409, "USER_ALREADY_EXISTS");
        problem(api.send("POST", "/api/manage/v1/users", "{\"username\":\"x1\",\"role\":\"USER\",\"extra\":1}"), 400, "MALFORMED_JSON");
        var invalid = api.send("POST", "/api/manage/v1/users", json.writeValueAsString(Map.of("username", "a b", "role", "ROOT")));
        problem(invalid, 400, "VALIDATION_FAILED");
        assertTrue(body(invalid).path("violations").size() >= 2, invalid.body());
        var fixed = api.send("POST", "/api/manage/v1/users", json.writeValueAsString(Map.of("username", name + "b", "role", "USER", "password", PASSWORD)));
        assertEquals(201, fixed.statusCode());
        assertTrue(body(fixed).path("temporaryPassword").isNull());
        String log = jdbc.queryForObject("SELECT detail FROM management_operation_log WHERE target_username=? AND action='USER_CREATED'", String.class, name);
        assertFalse(log.contains(temporary));
        assertEquals("test-admin", jdbc.queryForObject("SELECT operator FROM management_operation_log WHERE target_username=?", String.class, name));
    }

    @Test void listsWithFiltersSortingPagingAndCounts() throws Exception {
        String prefix = "list" + UUID.randomUUID().toString().substring(0, 4);
        for (int i = 0; i < 3; i++) accounts.create(prefix + "-" + i, PASSWORD, "USER");
        accounts.setEnabled(prefix + "-2", false);
        signIn(prefix + "-0", PASSWORD);
        var page = body(api.send("GET", "/api/manage/v1/users?q=" + prefix.toUpperCase() + "&sort=username&order=asc&size=2&page=1", null));
        assertEquals(3, page.path("total").asLong());
        assertEquals(2, page.path("items").size());
        assertEquals(prefix + "-0", page.path("items").get(0).path("username").asText());
        assertEquals(1, page.path("items").get(0).path("activeSessionCount").asInt());
        assertTrue(page.path("items").get(0).path("lastActiveAtMs").asLong() > 0);
        assertTrue(page.path("items").get(1).path("lastActiveAtMs").isNull());
        var disabled = body(api.send("GET", "/api/manage/v1/users?q=" + prefix + "&status=DISABLED", null));
        assertEquals(1, disabled.path("total").asLong());
        assertFalse(disabled.path("items").get(0).path("enabled").asBoolean());
        var active = body(api.send("GET", "/api/manage/v1/users?q=" + prefix + "&sort=lastActiveAt", null));
        assertEquals(prefix + "-0", active.path("items").get(0).path("username").asText());
        problem(api.send("GET", "/api/manage/v1/users?size=101", null), 400, "VALIDATION_FAILED");
        problem(api.send("GET", "/api/manage/v1/users?sort=password", null), 400, "VALIDATION_FAILED");
        problem(api.send("GET", "/api/manage/v1/users/nobody-here", null), 404, "USER_NOT_FOUND");
    }

    @Test void disablingSignsTheUserOutEverywhereAndRevokesPortableLinks() throws Exception {
        String name = user();
        accounts.changePassword(name, "", PASSWORD + "x");
        var session = signIn(name, PASSWORD + "x");
        assertEquals(200, me(session));
        var link = portableLinks.create(name, PASSWORD + "x");
        var detail = body(api.send("GET", "/api/manage/v1/users/" + name, null));
        assertEquals(1, detail.path("activeSessionCount").asInt());
        assertEquals(1, detail.path("activePortableLinkCount").asInt());
        assertEquals(1, body(api.send("GET", "/api/manage/v1/users/" + name + "/sessions", null)).size());
        var disabled = api.send("PATCH", "/api/manage/v1/users/" + name + "/status", "{\"enabled\":false}");
        assertEquals(200, disabled.statusCode(), disabled.body());
        assertEquals(1, body(disabled).path("revokedSessions").asInt());
        assertEquals(401, me(session));
        assertEquals("REVOKED", portableLinks.list(name).stream().filter(l -> l.id().equals(link.id())).findFirst().orElseThrow().status());
        assertEquals(0, body(api.send("PATCH", "/api/manage/v1/users/" + name + "/status", "{\"enabled\":true}")).path("revokedSessions").asInt());
        assertTrue(accounts.find(name).enabled());
    }

    @Test void resetPasswordRoleChangeAndSessionRevocation() throws Exception {
        String name = user();
        accounts.changePassword(name, "", PASSWORD + "y");
        var session = signIn(name, PASSWORD + "y");
        var reset = api.send("POST", "/api/manage/v1/users/" + name + "/password-reset", "{}");
        assertEquals(200, reset.statusCode(), reset.body());
        assertEquals(1, body(reset).path("revokedSessions").asInt());
        String temporary = body(reset).path("temporaryPassword").asText();
        assertEquals(401, me(session));
        assertTrue(accounts.matches(name, temporary));
        assertTrue(accounts.find(name).mustChangePassword());
        signIn(name, temporary);
        var promoted = api.send("PATCH", "/api/manage/v1/users/" + name + "/role", "{\"role\":\"ADMIN\"}");
        assertEquals(1, body(promoted).path("revokedSessions").asInt());
        assertEquals("ADMIN", accounts.find(name).role());
        signIn(name, temporary);
        assertEquals(1, body(api.send("DELETE", "/api/manage/v1/users/" + name + "/sessions", null)).path("revokedSessions").asInt());
        assertEquals(0, body(api.send("GET", "/api/manage/v1/users/" + name + "/sessions", null)).size());
        accounts.create(name + "-peer", PASSWORD, "ADMIN");
        assertEquals(200, api.send("PATCH", "/api/manage/v1/users/" + name + "/role", "{\"role\":\"USER\"}").statusCode());
    }

    @Test void lastEnabledAdministratorIsProtected() throws Exception {
        var admins = jdbc.queryForList("SELECT username FROM closet_user WHERE role='ADMIN' AND enabled=1", String.class);
        for (String admin : admins) jdbc.update("UPDATE closet_user SET enabled=0 WHERE username=?", admin);
        try {
            String boss = "boss-" + UUID.randomUUID().toString().substring(0, 6);
            accounts.create(boss, PASSWORD, "ADMIN");
            problem(api.send("PATCH", "/api/manage/v1/users/" + boss + "/status", "{\"enabled\":false}"), 409, "LAST_ADMIN_PROTECTED");
            problem(api.send("PATCH", "/api/manage/v1/users/" + boss + "/role", "{\"role\":\"USER\"}"), 409, "LAST_ADMIN_PROTECTED");
            assertTrue(accounts.find(boss).enabled());
            jdbc.update("UPDATE closet_user SET role='USER' WHERE username=?", boss);
        } finally {
            for (String admin : admins) jdbc.update("UPDATE closet_user SET enabled=1 WHERE username=?", admin);
        }
    }

    @Test void quotaOverridesApplyToTheSharedPolicyAndCanBeReset() throws Exception {
        String name = user();
        var initial = body(api.send("GET", "/api/manage/v1/users/" + name + "/quota", null));
        assertEquals(QuotaPolicy.DEFAULT_IMAGE_DAILY_LIMIT, initial.path("image").path("limit").asInt());
        assertFalse(initial.path("image").path("overridden").asBoolean());
        var changed = body(api.send("PUT", "/api/manage/v1/users/" + name + "/quota", "{\"imageDailyLimit\":50,\"agentDailyLimit\":0}"));
        assertEquals(50, changed.path("image").path("limit").asInt());
        assertTrue(changed.path("agent").path("overridden").asBoolean());
        assertEquals(0, changed.path("agent").path("limit").asInt());
        var invalid = api.send("PUT", "/api/manage/v1/users/" + name + "/quota", "{\"imageDailyLimit\":1001,\"agentDailyLimit\":null}");
        problem(invalid, 400, "VALIDATION_FAILED");
        assertEquals("imageDailyLimit", body(invalid).path("violations").get(0).path("field").asText());
        var reset = body(api.send("PUT", "/api/manage/v1/users/" + name + "/quota", "{\"imageDailyLimit\":null,\"agentDailyLimit\":null}"));
        assertFalse(reset.path("image").path("overridden").asBoolean());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM closet_user_quota WHERE owner_username=?", Integer.class, name));
    }

    @Test void portableLinksAreListedAndRevokedPerUser() throws Exception {
        String name = user();
        accounts.changePassword(name, "", PASSWORD + "z");
        var link = portableLinks.create(name, PASSWORD + "z");
        var links = body(api.send("GET", "/api/manage/v1/users/" + name + "/portable-links", null));
        assertEquals("PENDING", links.get(0).path("status").asText());
        assertTrue(links.get(0).path("createdAtMs").asLong() > 0);
        problem(api.send("DELETE", "/api/manage/v1/users/" + user() + "/portable-links/" + link.id(), null), 404, "PORTABLE_LINK_NOT_FOUND");
        assertEquals(204, api.send("DELETE", "/api/manage/v1/users/" + name + "/portable-links/" + link.id(), null).statusCode());
        assertEquals("REVOKED", body(api.send("GET", "/api/manage/v1/users/" + name + "/portable-links", null)).get(0).path("status").asText());
    }

    @Test void endUserSessionsCannotReachManagementAndManagementCreatesNoSession() throws Exception {
        String name = user();
        var session = signIn(name, PASSWORD);
        var response = session.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/manage/v1/users")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
        var signed = api.send("GET", "/api/manage/v1/users", null);
        assertTrue(signed.headers().allValues("Set-Cookie").isEmpty(), signed.headers().toString());
        assertTrue(signed.headers().firstValue("X-Request-Id").orElse("").startsWith("req-"));
    }

    @Test void accountFieldsInvitesAndEventsFollowTheContract() throws Exception {
        String name = "m-" + UUID.randomUUID().toString().substring(0, 8);
        var created = api.send("POST", "/api/manage/v1/users", json.writeValueAsString(Map.of("username", name, "role", "USER", "displayName", "管理创建")), UUID.randomUUID().toString(), "ops-alice");
        assertEquals(201, created.statusCode(), created.body());
        problem(api.send("POST", "/api/manage/v1/users", json.writeValueAsString(Map.of("username", name.toUpperCase(), "role", "USER"))), 409, "USER_ALREADY_EXISTS");
        var listed = body(api.send("GET", "/api/manage/v1/users?q=" + java.net.URLEncoder.encode("管理创", java.nio.charset.StandardCharsets.UTF_8), null)).path("items");
        assertEquals(1, listed.size());
        assertEquals("管理创建", listed.get(0).path("displayName").asText());
        assertEquals("ADMIN", listed.get(0).path("registeredVia").asText());
        assertTrue(listed.get(0).has("lastLoginAtMs") && listed.get(0).has("deactivatedAtMs"));
        var detail = body(api.send("GET", "/api/manage/v1/users/" + name, null));
        assertEquals("管理创建", detail.path("displayName").asText());

        assertEquals(200, api.send("PATCH", "/api/manage/v1/users/" + name + "/status", "{\"enabled\":false}").statusCode());
        var events = body(api.send("GET", "/api/manage/v1/users/" + name + "/events", null));
        assertEquals("MANAGE_USER_DISABLED", events.get(0).path("action").asText());
        assertEquals("MANAGE_USER_CREATED", events.get(1).path("action").asText());
        assertEquals("manage:primary:ops-alice", events.get(1).path("actor").asText());
        problem(api.send("GET", "/api/manage/v1/users/nobody-here/events", null), 404, "USER_NOT_FOUND");

        var registration = body(api.send("GET", "/api/manage/v1/registration", null));
        assertEquals("INVITE", registration.path("mode").asText());
        var invite = api.send("POST", "/api/manage/v1/invites", "{\"note\":\"渠道\",\"maxUses\":3}", UUID.randomUUID().toString(), "ops-alice");
        assertEquals(201, invite.statusCode(), invite.body());
        assertEquals("manage:primary:ops-alice", body(invite).path("createdBy").asText());
        assertEquals("ACTIVE", body(invite).path("status").asText());
        String code = body(invite).path("code").asText(), id = body(invite).path("id").asText();
        assertTrue(code.matches("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}"));
        var invites = api.send("GET", "/api/manage/v1/invites", null);
        assertFalse(invites.body().contains(code));
        assertTrue(invites.body().contains(id));
        assertEquals(registration.path("activeInviteCount").asInt() + 1, body(api.send("GET", "/api/manage/v1/registration", null)).path("activeInviteCount").asInt());
        problem(api.send("POST", "/api/manage/v1/invites", "{\"maxUses\":0}"), 400, "VALIDATION_FAILED");
        problem(api.send("POST", "/api/manage/v1/invites", "{\"uses\":1}"), 400, "MALFORMED_JSON");
        assertEquals(204, api.send("DELETE", "/api/manage/v1/invites/" + id, null).statusCode());
        problem(api.send("DELETE", "/api/manage/v1/invites/" + UUID.randomUUID(), null), 404, "INVITE_NOT_FOUND");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM management_operation_log WHERE action='INVITE_REVOKED' AND detail=?", Integer.class, "inviteId=" + id));
    }

    HttpResponse<String> register(String username, String inviteCode) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        var csrf = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/csrf")).build(), HttpResponse.BodyHandlers.ofString());
        var body = new java.util.HashMap<String, Object>(Map.of("username", username, "password", PASSWORD));
        if (inviteCode != null) body.put("inviteCode", inviteCode);
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/register"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", json.readTree(csrf.body()).path("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void fullAccountControlFromTheManagementPlatform() throws Exception {
        // Registration mode override takes effect immediately and can be reset to the deployment default.
        var closed = body(api.send("PUT", "/api/manage/v1/registration", "{\"mode\":\"CLOSED\"}", UUID.randomUUID().toString(), "ops-bob"));
        assertEquals("CLOSED", closed.path("mode").asText());
        assertEquals("INVITE", closed.path("configuredMode").asText());
        assertTrue(closed.path("overridden").asBoolean());
        assertEquals("manage:primary:ops-bob", closed.path("updatedBy").asText());
        assertEquals(403, register("r-" + UUID.randomUUID().toString().substring(0, 8), null).statusCode());
        assertEquals("OPEN", body(api.send("PUT", "/api/manage/v1/registration", "{\"mode\":\"OPEN\"}")).path("mode").asText());
        String open = "r-" + UUID.randomUUID().toString().substring(0, 8);
        assertEquals(200, register(open, null).statusCode());
        problem(api.send("PUT", "/api/manage/v1/registration", "{\"mode\":\"SOMETIMES\"}"), 400, "VALIDATION_FAILED");
        var reset = body(api.send("PUT", "/api/manage/v1/registration", "{\"mode\":null}"));
        assertEquals("INVITE", reset.path("mode").asText());
        assertFalse(reset.path("overridden").asBoolean());

        // Invites: detail lists who registered, update capacity and deadline, paged list filters by status.
        var invite = body(api.send("POST", "/api/manage/v1/invites", "{\"maxUses\":1}"));
        String id = invite.path("id").asText();
        String invited = "r-" + UUID.randomUUID().toString().substring(0, 8);
        assertEquals(200, register(invited, invite.path("code").asText()).statusCode());
        var detail = body(api.send("GET", "/api/manage/v1/invites/" + id, null));
        assertEquals("USED_UP", detail.path("invite").path("status").asText());
        assertEquals(invited, detail.path("registeredUsers").get(0).path("username").asText());
        problem(api.send("PATCH", "/api/manage/v1/invites/" + id, "{\"maxUses\":0}"), 400, "VALIDATION_FAILED");
        problem(api.send("PATCH", "/api/manage/v1/invites/" + id, "{}"), 400, "VALIDATION_FAILED");
        long deadline = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000;
        var updated = body(api.send("PATCH", "/api/manage/v1/invites/" + id, "{\"maxUses\":2,\"expiresAtMs\":" + deadline + ",\"note\":\"加一位\"}"));
        assertEquals("ACTIVE", updated.path("status").asText());
        assertEquals(deadline, updated.path("expiresAtMs").asLong());
        assertEquals("加一位", updated.path("note").asText());
        assertEquals(200, register("r-" + UUID.randomUUID().toString().substring(0, 8), invite.path("code").asText()).statusCode());
        var usedUp = body(api.send("GET", "/api/manage/v1/invites?status=USED_UP&size=100", null));
        assertTrue(usedUp.path("total").asInt() >= 1);
        usedUp.path("items").forEach(i -> assertEquals("USED_UP", i.path("status").asText()));
        assertEquals(204, api.send("DELETE", "/api/manage/v1/invites/" + id, null).statusCode());
        problem(api.send("PATCH", "/api/manage/v1/invites/" + id, "{\"maxUses\":3}"), 409, "INVITE_REVOKED");
        problem(api.send("GET", "/api/manage/v1/invites/" + UUID.randomUUID(), null), 404, "INVITE_NOT_FOUND");

        // User filters, detail invite link, profile edit.
        var viaInvite = body(api.send("GET", "/api/manage/v1/users?registeredVia=INVITE&size=100&sort=lastLoginAt", null)).path("items");
        assertTrue(viaInvite.toString().contains(invited));
        viaInvite.forEach(u -> assertEquals("INVITE", u.path("registeredVia").asText()));
        assertEquals(id, body(api.send("GET", "/api/manage/v1/users/" + invited, null)).path("inviteId").asText());
        assertEquals("客服改名", body(api.send("PATCH", "/api/manage/v1/users/" + invited + "/profile", "{\"displayName\":\"客服改名\"}")).path("displayName").asText());
        assertTrue(body(api.send("PATCH", "/api/manage/v1/users/" + invited + "/profile", "{\"displayName\":\"\"}")).path("displayName").isNull());
        problem(api.send("PATCH", "/api/manage/v1/users/" + invited + "/profile", "{}"), 400, "VALIDATION_FAILED");

        // Single session sign-out.
        var first = signIn(invited, PASSWORD); var second = signIn(invited, PASSWORD);
        var sessions = body(api.send("GET", "/api/manage/v1/users/" + invited + "/sessions", null));
        assertEquals(3, sessions.size(), "registration signs in too");
        assertEquals(204, api.send("DELETE", "/api/manage/v1/users/" + invited + "/sessions/" + sessions.get(0).path("sessionRef").asText(), null).statusCode());
        assertEquals(2, body(api.send("GET", "/api/manage/v1/users/" + invited + "/sessions", null)).size());
        problem(api.send("DELETE", "/api/manage/v1/users/" + invited + "/sessions/0123456789abcdef", null), 404, "SESSION_NOT_FOUND");
        assertNotNull(first); assertNotNull(second);

        // Login lock: five failures lock the username, the platform can unlock.
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String token = json.readTree(client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/csrf")).build(), HttpResponse.BodyHandlers.ofString()).body()).path("token").asText();
        for (int i = 0; i < 5; i++) client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("username", open, "password", "WrongPassword123!")))).build(), HttpResponse.BodyHandlers.ofString());
        var lock = body(api.send("GET", "/api/manage/v1/users/" + open + "/login-lock", null));
        assertTrue(lock.path("locked").asBoolean());
        assertEquals(5, lock.path("recentFailures").asInt());
        assertTrue(body(api.send("GET", "/api/manage/v1/users/" + open, null)).path("loginLock").path("locked").asBoolean());
        assertFalse(body(api.send("DELETE", "/api/manage/v1/users/" + open + "/login-lock", null)).path("locked").asBoolean());
        signIn(open, PASSWORD);

        // Site-wide audit feed.
        var feed = body(api.send("GET", "/api/manage/v1/account-events?username=" + open + "&size=100", null));
        var actions = new java.util.HashSet<String>(); feed.path("items").forEach(e -> actions.add(e.path("action").asText()));
        assertTrue(actions.containsAll(java.util.Set.of("REGISTERED", "MANAGE_LOGIN_UNLOCKED", "LOGIN_SUCCEEDED")), actions.toString());
        feed.path("items").forEach(e -> assertEquals(open, e.path("username").asText()));
        var unlocks = body(api.send("GET", "/api/manage/v1/account-events?action=MANAGE_LOGIN_UNLOCKED", null));
        assertTrue(unlocks.path("total").asInt() >= 1);
        problem(api.send("GET", "/api/manage/v1/account-events?action=bad-action", null), 400, "VALIDATION_FAILED");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM management_operation_log WHERE action='USER_LOGIN_UNLOCKED' AND target_username=?", Integer.class, open));
    }
}
