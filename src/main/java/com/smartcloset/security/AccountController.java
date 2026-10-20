package com.smartcloset.security;

import com.smartcloset.image.ProcessingException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.*;

/** The signed-in user's own profile, devices and deactivation. Portable sessions may only edit the profile (PortableLoginFilter). */
@RestController
@RequestMapping("/api/auth")
public class AccountController {
    public record ProfileView(String username, String displayName, String role, long createdAtMs, Long lastLoginAtMs, String registeredVia) {}
    public record ProfileChange(String displayName) {}
    public record Deactivation(String currentPassword) {}
    private final ClosetAccountService accounts;
    private final ClosetSessionRevoker sessions;
    private final AccountEvents events;
    public AccountController(ClosetAccountService accounts, ClosetSessionRevoker sessions, AccountEvents events) {
        this.accounts = accounts; this.sessions = sessions; this.events = events;
    }

    @GetMapping("/profile") public ProfileView profile() { return view(ClosetIdentity.username()); }

    @PatchMapping("/profile") public ProfileView updateProfile(@RequestBody ProfileChange body) {
        String name = ClosetIdentity.username();
        if (accounts.mustChange(name)) throw new ProcessingException(HttpStatus.FORBIDDEN, "PASSWORD_CHANGE_REQUIRED", "请先修改初始密码，再使用衣柜功能。");
        try { accounts.updateDisplayName(name, body.displayName()); }
        catch (IllegalArgumentException e) { throw new ProcessingException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage()); }
        events.record(name, name, "PROFILE_UPDATED", null);
        return view(name);
    }

    @GetMapping("/sessions") public ResponseEntity<List<ClosetSessionRevoker.OwnSession>> sessions(HttpServletRequest request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(sessions.list(ClosetIdentity.username(), currentSession(request)));
    }

    /** Other devices lose their session and every portable grant of the account. */
    @DeleteMapping("/sessions/others") public Map<String, Integer> signOutOthers(HttpServletRequest request) {
        String name = ClosetIdentity.username();
        int revoked = sessions.revokeOthers(name, currentSession(request));
        accounts.revokePortableLinks(name);
        events.record(name, name, "SESSIONS_REVOKED", "revokedSessions=" + revoked);
        return Map.of("revokedSessions", revoked);
    }

    @PostMapping("/deactivate") public ResponseEntity<Void> deactivate(@RequestBody Deactivation body, HttpServletRequest request, HttpServletResponse response) {
        String name = ClosetIdentity.username();
        if (accounts.mustChange(name) || !accounts.matches(name, body.currentPassword()))
            throw new ProcessingException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "当前密码不正确。");
        try { accounts.deactivate(name); }
        catch (ClosetAccountService.LastAdminException e) { throw new ProcessingException(HttpStatus.CONFLICT, "LAST_ADMIN_PROTECTED", "你是最后一个启用的管理员，不能注销账号。"); }
        events.record(name, name, "DEACTIVATED", null);
        new SecurityContextLogoutHandler().logout(request, response, SecurityContextHolder.getContext().getAuthentication());
        return ResponseEntity.noContent().build();
    }

    private ProfileView view(String name) {
        var p = accounts.profile(name);
        if (p == null) throw new ProcessingException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "登录已失效，请重新登录。");
        return new ProfileView(p.username(), p.displayName(), p.role(), p.createdAtMs(), p.lastLoginAtMs(), p.registeredVia());
    }

    private static String currentSession(HttpServletRequest request) { return request.getSession(false) == null ? null : request.getSession(false).getId(); }
}
