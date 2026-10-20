package com.smartcloset.security;

import com.smartcloset.image.ProcessingException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Administrator-only via the /api/admin/ rule in SecurityConfiguration; portable sessions cannot write (PortableLoginFilter). */
@RestController
@RequestMapping("/api/admin")
public class AdminUserController {
    public record CreateUser(String username, String role, String displayName) {}
    public record StatusChange(Boolean enabled) {}
    public record RoleChange(String role) {}
    public record QuotaChange(Integer imageDailyLimit, Integer agentDailyLimit) {}
    public record InviteRequest(String note, Integer maxUses, Integer expiresInDays) {}
    public record RegistrationView(String mode, int activeInviteCount) {}
    private static final String USERNAME = "[A-Za-z0-9_.-]{2,80}";

    private final AdminUserService users;
    private final RegistrationService registrations;
    public AdminUserController(AdminUserService users, RegistrationService registrations) { this.users = users; this.registrations = registrations; }

    @GetMapping("/users") public AdminUserService.Page list(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
            @RequestParam(required = false) String role, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        if (q != null && q.length() > 80) throw invalid("搜索词最多 80 个字符。");
        return users.list(q, status, role, Math.max(1, Math.min(page, 100000)), Math.max(1, Math.min(size, 100)));
    }

    @PostMapping("/users") public ResponseEntity<AdminUserService.Credential> create(@RequestBody CreateUser body) {
        if (body.role() != null && !List.of("USER", "ADMIN").contains(body.role())) throw invalid("角色只能是 USER 或 ADMIN。");
        return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control", "no-store").body(users.create(actor(), body.username(), body.role(), body.displayName()));
    }

    @PatchMapping("/users/{username}/status") public AdminUserService.StatusResult status(@PathVariable String username, @RequestBody StatusChange body) {
        if (body.enabled() == null) throw invalid("缺少 enabled。");
        return users.setEnabled(actor(), checked(username), body.enabled());
    }

    @PatchMapping("/users/{username}/role") public AdminUserService.RoleResult role(@PathVariable String username, @RequestBody RoleChange body) {
        if (body.role() == null || !List.of("USER", "ADMIN").contains(body.role())) throw invalid("角色只能是 USER 或 ADMIN。");
        return users.changeRole(actor(), checked(username), body.role());
    }

    @PostMapping("/users/{username}/password-reset") public ResponseEntity<AdminUserService.Reset> resetPassword(@PathVariable String username) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(users.resetPassword(actor(), checked(username)));
    }

    @DeleteMapping("/users/{username}/sessions") public Map<String, Integer> revokeSessions(@PathVariable String username) {
        return Map.of("revokedSessions", users.revokeSessions(actor(), checked(username)));
    }

    @GetMapping("/users/{username}/quota") public QuotaPolicy.Snapshot quota(@PathVariable String username) { return users.quota(checked(username)); }

    @PutMapping("/users/{username}/quota") public QuotaPolicy.Snapshot updateQuota(@PathVariable String username, @RequestBody QuotaChange body) {
        return users.updateQuota(actor(), checked(username), body.imageDailyLimit(), body.agentDailyLimit());
    }

    @GetMapping("/users/{username}/events") public List<AccountEvents.Event> events(@PathVariable String username) { return users.events(checked(username)); }

    @GetMapping("/registration") public RegistrationView registration() {
        return new RegistrationView(registrations.mode().name(), registrations.activeInviteCount());
    }

    @GetMapping("/invites") public List<RegistrationService.Invite> invites() { return registrations.invites(100); }

    @PostMapping("/invites") public ResponseEntity<RegistrationService.CreatedInvite> createInvite(@RequestBody InviteRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control", "no-store")
                .body(registrations.createInvite(actor(), body.note(), body.maxUses(), body.expiresInDays()));
    }

    @DeleteMapping("/invites/{id}") public ResponseEntity<Void> revokeInvite(@PathVariable String id) {
        if (id.length() > 36 || !registrations.revokeInvite(id)) throw new ProcessingException(HttpStatus.NOT_FOUND, "INVITE_NOT_FOUND", "没有找到这个邀请码。");
        return ResponseEntity.noContent().build();
    }

    private static String actor() { return ClosetIdentity.username(); }
    private static String checked(String username) {
        if (!username.matches(USERNAME)) throw new ProcessingException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "没有找到这个用户。");
        return username;
    }
    private static ProcessingException invalid(String message) { return new ProcessingException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message); }
}
