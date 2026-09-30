package com.smartcloset.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record Credentials(String username, String password) {}
    public record PasswordChange(String currentPassword, String newPassword) {}
    public record UserView(String username, String role, boolean mustChangePassword) {}
    private final AuthenticationManager authentication;
    private final SecurityContextRepository contexts;
    private final ClosetAccountService accounts;
    public AuthController(AuthenticationManager authentication, SecurityContextRepository contexts, ClosetAccountService accounts) {
        this.authentication = authentication; this.contexts = contexts; this.accounts = accounts;
    }

    @GetMapping("/csrf") public Map<String, String> csrf(CsrfToken token) { return Map.of("token", token.getToken()); }

    @PostMapping("/login") public ResponseEntity<?> login(@RequestBody Credentials credentials, HttpServletRequest request, HttpServletResponse response) {
        try {
            var auth = authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(credentials.username(), credentials.password()));
            var context = SecurityContextHolder.createEmptyContext(); context.setAuthentication(auth); SecurityContextHolder.setContext(context);
            contexts.saveContext(context, request, response);
            var user = accounts.find(auth.getName());
            return ResponseEntity.ok(new UserView(user.username(), user.role(), user.mustChangePassword()));
        } catch (BadCredentialsException | org.springframework.security.core.userdetails.UsernameNotFoundException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "账号或密码不正确。"));
        }
    }

    @GetMapping("/me") public UserView me() {
        String name = ClosetIdentity.username(); var user = accounts.find(name);
        if (user == null) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return new UserView(user.username(), user.role(), user.mustChangePassword());
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
