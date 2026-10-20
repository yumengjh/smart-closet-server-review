package com.smartcloset.management.api;

import com.smartcloset.image.ProcessingException;
import com.smartcloset.management.ConditionalOnManagementEnabled;
import com.smartcloset.management.ManagementException;
import com.smartcloset.management.application.ManagementOperationLog;
import com.smartcloset.management.auth.ManagementPrincipal;
import com.smartcloset.security.AccountProperties;
import com.smartcloset.security.RegistrationService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Registration mode (deployment default with an administrator override) and invitation codes; codes appear only in the create response. */
@Validated
@RestController
@ConditionalOnManagementEnabled
@RequestMapping("/api/manage/v1")
public class ManagementRegistrationController {
    public record ModeChange(@Pattern(regexp = "^(CLOSED|INVITE|OPEN)$") String mode) {}
    public record CreateInvite(@Size(max = 100) String note, @Min(1) @Max(100) Integer maxUses, @Min(1) @Max(90) Integer expiresInDays) {}
    public record UpdateInvite(@Size(max = 100) String note, @Min(1) @Max(100) Integer maxUses, Long expiresAtMs) {}

    private final RegistrationService registrations;
    private final StrictJsonBodies bodies;
    private final ManagementOperationLog operations;
    public ManagementRegistrationController(RegistrationService registrations, StrictJsonBodies bodies, ManagementOperationLog operations) {
        this.registrations = registrations; this.bodies = bodies; this.operations = operations;
    }

    @GetMapping("/registration")
    public RegistrationService.ModeState registration() { return registrations.modeState(); }

    /** {@code mode} null or absent removes the override and returns to CLOSET_REGISTRATION. */
    @PutMapping("/registration")
    public RegistrationService.ModeState setRegistration(@RequestBody(required = false) byte[] body) {
        var request = bodies.read(body, ModeChange.class);
        var mode = request.mode() == null ? null : AccountProperties.Registration.valueOf(request.mode());
        var state = registrations.setMode(mode, actor());
        operations.record("REGISTRATION_MODE_CHANGED", null, "SUCCESS", "mode=" + (mode == null ? "DEFAULT" : mode.name()) + ",effective=" + state.mode());
        return state;
    }

    @GetMapping("/invites")
    public RegistrationService.InvitePage invites(@RequestParam(required = false) @Pattern(regexp = "^(ACTIVE|USED_UP|EXPIRED|REVOKED)$") String status,
            @RequestParam(defaultValue = "1") @Min(1) @Max(100000) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return registrations.invitePage(status, page, size);
    }

    @PostMapping("/invites")
    public ResponseEntity<RegistrationService.CreatedInvite> create(@RequestBody(required = false) byte[] body) {
        var request = bodies.read(body, CreateInvite.class);
        RegistrationService.CreatedInvite created;
        try { created = registrations.createInvite(actor(), request.note(), request.maxUses(), request.expiresInDays()); }
        catch (ProcessingException e) { throw ManagementException.invalid(e.getMessage()); }
        operations.record("INVITE_CREATED", null, "SUCCESS", "inviteId=" + created.id() + ",maxUses=" + created.maxUses());
        return ResponseEntity.created(URI.create("/api/manage/v1/invites/" + created.id())).header("Cache-Control", "no-store").body(created);
    }

    @GetMapping("/invites/{id}")
    public RegistrationService.InviteDetail invite(@PathVariable @Size(max = 36) String id) {
        var detail = registrations.inviteDetail(id);
        if (detail == null) throw notFound();
        return detail;
    }

    /** At least one field; {@code note} "" clears the note. */
    @PatchMapping("/invites/{id}")
    public RegistrationService.Invite update(@PathVariable @Size(max = 36) String id, @RequestBody(required = false) byte[] body) {
        var request = bodies.read(body, UpdateInvite.class);
        if (request.note() == null && request.maxUses() == null && request.expiresAtMs() == null) throw ManagementException.invalid("至少提供 note、maxUses、expiresAtMs 之一。");
        RegistrationService.Invite updated;
        try { updated = registrations.updateInvite(id, request.note(), request.note() != null && request.note().isBlank(), request.maxUses(), request.expiresAtMs()); }
        catch (ProcessingException e) {
            if (e.status() == HttpStatus.CONFLICT) throw new ManagementException(HttpStatus.CONFLICT, "INVITE_REVOKED", "邀请码已撤销", e.getMessage());
            throw ManagementException.invalid(e.getMessage());
        }
        if (updated == null) throw notFound();
        operations.record("INVITE_UPDATED", null, "SUCCESS", "inviteId=" + id + ",maxUses=" + request.maxUses() + ",expiresAtMs=" + request.expiresAtMs());
        return updated;
    }

    @DeleteMapping("/invites/{id}")
    public ResponseEntity<Void> revoke(@PathVariable @Size(max = 36) String id) {
        if (!registrations.revokeInvite(id)) throw notFound();
        operations.record("INVITE_REVOKED", null, "SUCCESS", "inviteId=" + id);
        return ResponseEntity.noContent().build();
    }

    private static ManagementException notFound() { return new ManagementException(HttpStatus.NOT_FOUND, "INVITE_NOT_FOUND", "邀请码不存在", "没有这个邀请码。"); }

    static String actor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof ManagementPrincipal p)
            return "manage:" + p.keyId() + (p.operator() == null ? "" : ":" + p.operator());
        return "manage";
    }
}
