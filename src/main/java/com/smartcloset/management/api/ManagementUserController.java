package com.smartcloset.management.api;

import com.smartcloset.management.ConditionalOnManagementEnabled;
import com.smartcloset.management.application.ManagementUserService;
import com.smartcloset.security.AccountEvents;
import com.smartcloset.security.LoginThrottle;
import com.smartcloset.security.QuotaPolicy;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@ConditionalOnManagementEnabled
@RequestMapping("/api/manage/v1/users")
public class ManagementUserController {
    static final String USERNAME = "^[A-Za-z0-9_.-]{2,80}$";
    static final String ROLE = "^(USER|ADMIN)$";

    public record CreateUser(@NotNull @Pattern(regexp = USERNAME) String username, @NotNull @Pattern(regexp = ROLE) String role,
            @Size(min = 12, max = 200) String password, @Size(max = 40) String displayName) {}
    public record StatusChange(@NotNull Boolean enabled) {}
    public record RoleChange(@NotNull @Pattern(regexp = ROLE) String role) {}
    public record PasswordReset(@Size(min = 12, max = 200) String password) {}
    /** {@code displayName} is required; an empty string clears the name. */
    public record ProfileChange(@NotNull @Size(max = 40) String displayName) {}
    public record QuotaChange(@Min(0) @Max(1000) Integer imageDailyLimit, @Min(0) @Max(1000) Integer agentDailyLimit) {}

    private final ManagementUserService users;
    private final StrictJsonBodies bodies;
    public ManagementUserController(ManagementUserService users, StrictJsonBodies bodies) { this.users = users; this.bodies = bodies; }

    @GetMapping
    public ManagementUserService.Page list(@RequestParam(required = false) @Size(max = 80) String q,
            @RequestParam(required = false) @Pattern(regexp = "^(ENABLED|DISABLED)$") String status,
            @RequestParam(required = false) @Pattern(regexp = ROLE) String role,
            @RequestParam(required = false) @Pattern(regexp = "^(ADMIN|INVITE|OPEN)$") String registeredVia,
            @RequestParam(required = false) Boolean deactivated,
            @RequestParam(defaultValue = "createdAt") @Pattern(regexp = "^(createdAt|username|lastActiveAt|lastLoginAt)$") String sort,
            @RequestParam(defaultValue = "desc") @Pattern(regexp = "^(asc|desc)$") String order,
            @RequestParam(defaultValue = "1") @Min(1) @Max(100000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return users.list(new ManagementUserService.Query(q, status, role, sort, order, page, size, registeredVia, deactivated));
    }

    @PostMapping
    public ResponseEntity<ManagementUserService.Credential> create(@RequestBody(required = false) byte[] body) {
        var request = bodies.read(body, CreateUser.class);
        var created = users.create(request.username(), request.role(), request.password(), request.displayName());
        return ResponseEntity.created(URI.create("/api/manage/v1/users/" + created.username())).body(created);
    }

    @GetMapping("/{username}")
    public ManagementUserService.Detail detail(@PathVariable @Pattern(regexp = USERNAME) String username) { return users.detail(username); }

    @PatchMapping("/{username}/status")
    public ManagementUserService.StatusResult status(@PathVariable @Pattern(regexp = USERNAME) String username,
            @RequestBody(required = false) byte[] body) {
        return users.setEnabled(username, bodies.read(body, StatusChange.class).enabled());
    }

    @PatchMapping("/{username}/role")
    public ManagementUserService.RoleResult role(@PathVariable @Pattern(regexp = USERNAME) String username,
            @RequestBody(required = false) byte[] body) {
        return users.changeRole(username, bodies.read(body, RoleChange.class).role());
    }

    @PostMapping("/{username}/password-reset")
    public ManagementUserService.Reset resetPassword(@PathVariable @Pattern(regexp = USERNAME) String username,
            @RequestBody(required = false) byte[] body) {
        return users.resetPassword(username, bodies.read(body, PasswordReset.class).password());
    }

    @GetMapping("/{username}/sessions")
    public List<ManagementUserService.Session> sessions(@PathVariable @Pattern(regexp = USERNAME) String username) {
        return users.sessions(username);
    }

    @DeleteMapping("/{username}/sessions")
    public Map<String, Integer> revokeSessions(@PathVariable @Pattern(regexp = USERNAME) String username) {
        return Map.of("revokedSessions", users.revokeSessions(username));
    }

    @PatchMapping("/{username}/profile")
    public ManagementUserService.Profile profile(@PathVariable @Pattern(regexp = USERNAME) String username, @RequestBody(required = false) byte[] body) {
        return users.updateProfile(username, bodies.read(body, ProfileChange.class).displayName());
    }

    @DeleteMapping("/{username}/sessions/{sessionRef}")
    public ResponseEntity<Void> revokeSession(@PathVariable @Pattern(regexp = USERNAME) String username,
            @PathVariable @Pattern(regexp = "^[0-9a-f]{16}$") String sessionRef) {
        users.revokeSession(username, sessionRef);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{username}/login-lock")
    public LoginThrottle.UserLock loginLock(@PathVariable @Pattern(regexp = USERNAME) String username) { return users.loginLock(username); }

    @DeleteMapping("/{username}/login-lock")
    public LoginThrottle.UserLock unlock(@PathVariable @Pattern(regexp = USERNAME) String username) { return users.clearLoginLock(username); }

    @GetMapping("/{username}/events")
    public List<AccountEvents.Event> events(@PathVariable @Pattern(regexp = USERNAME) String username) { return users.events(username); }

    @GetMapping("/{username}/portable-links")
    public List<ManagementUserService.PortableLink> portableLinks(@PathVariable @Pattern(regexp = USERNAME) String username) {
        return users.portableLinks(username);
    }

    @DeleteMapping("/{username}/portable-links/{linkId}")
    public ResponseEntity<Void> revokePortableLink(@PathVariable @Pattern(regexp = USERNAME) String username,
            @PathVariable @Size(max = 36) String linkId) {
        users.revokePortableLink(username, linkId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{username}/quota")
    public QuotaPolicy.Snapshot quota(@PathVariable @Pattern(regexp = USERNAME) String username) { return users.quota(username); }

    @PutMapping("/{username}/quota")
    public QuotaPolicy.Snapshot updateQuota(@PathVariable @Pattern(regexp = USERNAME) String username,
            @RequestBody(required = false) byte[] body) {
        var request = bodies.read(body, QuotaChange.class);
        return users.updateQuota(username, request.imageDailyLimit(), request.agentDailyLimit());
    }
}
