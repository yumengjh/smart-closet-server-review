package com.smartcloset.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record Credentials(String username, String password) {}
    public record Registration(String username, String password, String displayName, String inviteCode) {}
    public record PortableLoginRequest(String token) {}
    public record PortableLinkRequest(String currentPassword) {}
    public record PasswordChange(String currentPassword, String newPassword, Boolean signOutOthers) {}
    public record UserView(String username, String role, boolean mustChangePassword, boolean portableLogin, String displayName) {}
    private final AuthenticationManager authentication;
    private final SecurityContextRepository contexts;
    private final ClosetAccountService accounts;
    private final PortableLoginService portableLinks;
    private final LoginThrottle throttle;
    private final RegistrationService registrations;
    private final AccountEvents events;
    private final ClosetSessionRevoker sessions;
    public AuthController(AuthenticationManager authentication, SecurityContextRepository contexts, ClosetAccountService accounts, PortableLoginService portableLinks,
            LoginThrottle throttle, RegistrationService registrations, AccountEvents events, ClosetSessionRevoker sessions) {
        this.authentication = authentication; this.contexts = contexts; this.accounts = accounts; this.portableLinks = portableLinks;
        this.throttle = throttle; this.registrations = registrations; this.events = events; this.sessions = sessions;
    }

    @GetMapping("/csrf") public Map<String, String> csrf(CsrfToken token) { return Map.of("token", token.getToken()); }

    /** Every authentication failure (unknown, wrong password, disabled, locked) answers the same 401 so accounts cannot be probed. */
    @PostMapping("/login") public ResponseEntity<?> login(@RequestBody Credentials credentials, HttpServletRequest request, HttpServletResponse response) {
        String address = request.getRemoteAddr();
        long wait = throttle.loginRetryAfter(credentials.username(), address);
        if (wait > 0) return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", Long.toString(wait))
                .body(Map.of("code", "LOGIN_THROTTLED", "message", "登录失败次数过多，请 " + Math.max(1, (wait + 59) / 60) + " 分钟后再试。"));
        try {
            var auth = authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(credentials.username(), credentials.password()));
            throttle.loginSucceeded(auth.getName());
            return ResponseEntity.ok(signIn(auth, request, response));
        } catch (AuthenticationException e) {
            throttle.loginFailed(credentials.username(), address);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("code", "BAD_CREDENTIALS", "message", "账号或密码不正确。"));
        }
    }

    @GetMapping("/registration") public Map<String, String> registration() { return Map.of("mode", registrations.mode().name()); }

    /** Self registration signs the new account in exactly like a password login. */
    @PostMapping("/register") public ResponseEntity<?> register(@RequestBody Registration body, HttpServletRequest request, HttpServletResponse response) {
        if (registrations.mode() == AccountProperties.Registration.CLOSED)
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("code", "REGISTRATION_CLOSED", "message", "当前未开放注册，请联系管理员开通账号。"));
        long wait = throttle.tryRegister(request.getRemoteAddr());
        if (wait > 0) return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", Long.toString(wait))
                .body(Map.of("code", "REGISTRATION_THROTTLED", "message", "注册过于频繁，请稍后再试。"));
        var registered = registrations.register(body.username(), body.password(), body.displayName(), body.inviteCode());
        events.record(registered.username(), registered.username(), "REGISTERED", "via=" + registered.registeredVia());
        var account = accounts.find(registered.username());
        var auth = UsernamePasswordAuthenticationToken.authenticated(account.username(), null, List.of(new SimpleGrantedAuthority("ROLE_" + account.role())));
        return ResponseEntity.ok(signIn(auth, request, response));
    }

    private UserView signIn(Authentication auth, HttpServletRequest request, HttpServletResponse response) {
        var context = SecurityContextHolder.createEmptyContext(); context.setAuthentication(auth); SecurityContextHolder.setContext(context);
        if (request.getSession(false) != null) request.getSession(false).invalidate();
        contexts.saveContext(context, request, response);
        request.getSession().setAttribute(ClosetSessionRevoker.DEVICE_ATTRIBUTE, ClosetSessionRevoker.device(request.getHeader("User-Agent")));
        accounts.recordLogin(auth.getName());
        events.record(auth.getName(), auth.getName(), "LOGIN_SUCCEEDED", null);
        var user = accounts.find(auth.getName());
        return new UserView(user.username(), user.role(), user.mustChangePassword(), false, accounts.displayName(user.username()));
    }

    @PostMapping("/portable-login") public ResponseEntity<?> portableLogin(@RequestBody PortableLoginRequest request, HttpServletRequest servletRequest, HttpServletResponse response) {
        var grant = portableLinks.consume(request.token());
        if (grant == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header("Cache-Control", "no-store").body(Map.of("message", "便携登录链接无效、已使用或已过期，请让分享者重新生成。"));
        String owner = grant.owner();
        var account = accounts.find(owner);
        var auth = UsernamePasswordAuthenticationToken.authenticated(owner, null, List.of(new SimpleGrantedAuthority("ROLE_" + account.role())));
        var context = SecurityContextHolder.createEmptyContext(); context.setAuthentication(auth); SecurityContextHolder.setContext(context);
        if (servletRequest.getSession(false) != null) servletRequest.getSession(false).invalidate();
        servletRequest.getSession(true).setMaxInactiveInterval(24 * 60 * 60);
        servletRequest.getSession().setAttribute(PortableLoginService.SESSION_LINK, grant.id());
        servletRequest.getSession().setAttribute(ClosetSessionRevoker.DEVICE_ATTRIBUTE, ClosetSessionRevoker.device(servletRequest.getHeader("User-Agent")));
        contexts.saveContext(context, servletRequest, response);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(new UserView(owner, account.role(), account.mustChangePassword(), true, accounts.displayName(owner)));
    }

    @GetMapping("/portable-links") public ResponseEntity<List<PortableLoginService.Link>> portableLinks() {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(portableLinks.list(ClosetIdentity.username()));
    }

    @PostMapping("/portable-links") public ResponseEntity<?> createPortableLink(@RequestBody PortableLinkRequest request) {
        try { return ResponseEntity.ok().header("Cache-Control", "no-store").body(portableLinks.create(ClosetIdentity.username(), request.currentPassword())); }
        catch (IllegalArgumentException | IllegalStateException e) { return ResponseEntity.badRequest().header("Cache-Control", "no-store").body(Map.of("message", e.getMessage())); }
    }

    @DeleteMapping("/portable-links/{id}") public ResponseEntity<Void> revokePortableLink(@PathVariable String id) {
        return portableLinks.revoke(ClosetIdentity.username(), id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/me") public UserView me(HttpServletRequest request) {
        String name = ClosetIdentity.username(); var user = accounts.find(name);
        if (user == null) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return new UserView(user.username(), user.role(), user.mustChangePassword(),
                request.getSession(false) != null && request.getSession(false).getAttribute(PortableLoginService.SESSION_LINK) != null, accounts.displayName(name));
    }


    @PostMapping("/password") public ResponseEntity<?> password(@RequestBody PasswordChange request, HttpServletRequest servletRequest,
            HttpServletResponse response) {
        try {
            String name = ClosetIdentity.username(); accounts.changePassword(name, request.currentPassword(), request.newPassword());
            int revoked = Boolean.FALSE.equals(request.signOutOthers()) ? 0
                    : sessions.revokeOthers(name, servletRequest.getSession(false) == null ? null : servletRequest.getSession(false).getId());
            events.record(name, name, "PASSWORD_CHANGED", "revokedSessions=" + revoked);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("message", e.getMessage())); }
    }

    @PostMapping("/logout") public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(request, response, SecurityContextHolder.getContext().getAuthentication());
        return ResponseEntity.noContent().build();
    }
}
