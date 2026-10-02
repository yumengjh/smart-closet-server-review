package com.smartcloset.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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
    public record PortableLoginRequest(String token) {}
    public record PortableLinkRequest(String currentPassword) {}
    public record PasswordChange(String currentPassword, String newPassword) {}
    public record UserView(String username, String role, boolean mustChangePassword, boolean portableLogin) {
        public UserView(String username, String role, boolean mustChangePassword) { this(username, role, mustChangePassword, false); }
    }
    private final AuthenticationManager authentication;
    private final SecurityContextRepository contexts;
    private final ClosetAccountService accounts;
    private final PortableLoginService portableLinks;
    public AuthController(AuthenticationManager authentication, SecurityContextRepository contexts, ClosetAccountService accounts, PortableLoginService portableLinks) {
        this.authentication = authentication; this.contexts = contexts; this.accounts = accounts; this.portableLinks = portableLinks;
    }

    @GetMapping("/csrf") public Map<String, String> csrf(CsrfToken token) { return Map.of("token", token.getToken()); }

    @PostMapping("/login") public ResponseEntity<?> login(@RequestBody Credentials credentials, HttpServletRequest request, HttpServletResponse response) {
        try {
            var auth = authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(credentials.username(), credentials.password()));
            var context = SecurityContextHolder.createEmptyContext(); context.setAuthentication(auth); SecurityContextHolder.setContext(context);
            if (request.getSession(false) != null) request.getSession(false).invalidate();
            contexts.saveContext(context, request, response);
            var user = accounts.find(auth.getName());
            return ResponseEntity.ok(new UserView(user.username(), user.role(), user.mustChangePassword()));
        } catch (BadCredentialsException | org.springframework.security.core.userdetails.UsernameNotFoundException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "账号或密码不正确。"));
        }
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
        contexts.saveContext(context, servletRequest, response);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(new UserView(owner, account.role(), account.mustChangePassword(), true));
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
        return new UserView(user.username(), user.role(), user.mustChangePassword(), request.getSession(false) != null && request.getSession(false).getAttribute(PortableLoginService.SESSION_LINK) != null);
    }


    @PostMapping("/password") public ResponseEntity<?> password(@RequestBody PasswordChange request, HttpServletRequest servletRequest,
            HttpServletResponse response) {
        try {
            String name = ClosetIdentity.username(); accounts.changePassword(name, request.currentPassword(), request.newPassword());
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("message", e.getMessage())); }
    }

    @PostMapping("/logout") public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(request, response, SecurityContextHolder.getContext().getAuthentication());
        return ResponseEntity.noContent().build();
    }
}
