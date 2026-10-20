package com.smartcloset.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableConfigurationProperties({ClosetCorsProperties.class, AccountProperties.class})
public class SecurityConfiguration {
    @Bean CorsConfigurationSource corsConfigurationSource(ClosetCorsProperties properties) {
        var source = new UrlBasedCorsConfigurationSource();
        var internal = new CorsConfiguration();
        source.registerCorsConfiguration("/api/agent/internal/**", internal);
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.allowedOrigins());
        cors.setAllowCredentials(true);
        cors.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "Idempotency-Key", "Accept"));
        cors.setExposedHeaders(List.of("Location", "Retry-After"));
        cors.setMaxAge(600L);
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    @Bean CookieCsrfTokenRepository csrfTokenRepository(@Value("${CLOSET_COOKIE_SECURE:#{null}}") Boolean secure) {
        var repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> {
            cookie.sameSite("Lax");
            if (secure != null) cookie.secure(secure);
        });
        return repository;
    }
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean AuthenticationProvider closetAuthenticationProvider(org.springframework.jdbc.core.JdbcTemplate jdbc, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(name -> {
            var rows = jdbc.query("SELECT username,password_hash,role,enabled FROM closet_user WHERE username=?",
                    (r,n) -> User.withUsername(r.getString(1)).password(r.getString(2)).authorities("ROLE_" + r.getString(3)).disabled(r.getInt(4) == 0).build(), name);
            if (rows.isEmpty()) throw new org.springframework.security.core.userdetails.UsernameNotFoundException("账号或密码不正确。");
            return rows.getFirst();
        });
        provider.setPasswordEncoder(encoder);
        return provider;
    }

    @Bean AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception { return configuration.getAuthenticationManager(); }
    @Bean SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }

    @Bean SecurityFilterChain closetSecurity(HttpSecurity http, ClosetAccountService accounts,
            SecurityContextRepository contexts, PortableLoginService portableLinks, tools.jackson.databind.ObjectMapper json,
            CorsConfigurationSource corsConfigurationSource, CookieCsrfTokenRepository csrfTokenRepository) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .headers(headers -> headers.referrerPolicy(policy -> policy.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
            .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository).ignoringRequestMatchers("/api/agent/internal/tool", "/api/agent/internal/permits"))
            .exceptionHandling(errors -> errors
                    .authenticationEntryPoint((request, response, error) -> writeError(response, json, 401,
                            "AUTHENTICATION_REQUIRED", "登录已失效，请重新登录。"))
                    .accessDeniedHandler((request, response, error) -> {
                        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                        var account = signedIn(auth) ? accounts.find(auth.getName()) : null;
                        if (error instanceof CsrfException) writeError(response, json, 403, "CSRF_INVALID", "安全校验已失效，请刷新页面后重新提交。");
                        else if (account != null && account.enabled() && account.mustChangePassword())
                            writeError(response, json, 403, "PASSWORD_CHANGE_REQUIRED", "请先修改初始密码，再使用衣柜功能。");
                        else writeError(response, json, 403, "ACCESS_DENIED", "当前账号无权执行此操作。");
                    }))
            .securityContext(context -> context.securityContextRepository(contexts))
            .sessionManagement(session -> session
                    .maximumSessions(5)
                    .maxSessionsPreventsLogin(false))
            .sessionManagement(session -> session.sessionFixation(fixation -> fixation.migrateSession()))
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .logout(logout -> logout.disable())
            .authorizeHttpRequests(auth -> auth
                    .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC).permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/agent/internal/tool", "/api/agent/internal/permits").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/register").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/auth/registration").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/auth/portable-login").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                    .requestMatchers("/workflow/**", "/studio/**").access((authentication, context) -> {
                        var current = authentication.get();
                        boolean allowed = signedIn(current) && !accounts.mustChange(current.getName())
                                && current.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
                        return new org.springframework.security.authorization.AuthorizationDecision(allowed);
                    })
                    .requestMatchers("/api/**").access((authentication, context) -> {
                        var current = authentication.get();
                        if (!signedIn(current)) return new org.springframework.security.authorization.AuthorizationDecision(false);
                        String uri = context.getRequest().getRequestURI();
                        if (uri.startsWith("/api/auth/")) return new org.springframework.security.authorization.AuthorizationDecision(true);
                        if (accounts.mustChange(current.getName())) return new org.springframework.security.authorization.AuthorizationDecision(false);
                        var request = context.getRequest();
                        String method = request.getMethod();
                        boolean adminOnly = uri.startsWith("/api/admin/") || ("POST".equals(method) && ("/api/workflows".equals(uri) || "/api/recognitions".equals(uri)
                                        || (uri.startsWith("/api/garments/") && uri.endsWith("/workflows"))))
                                || uri.equals("/api/garments") || uri.startsWith("/api/segmentations/") || uri.equals("/api/segmentations")
                                || uri.equals("/api/segmentation-methods");
                        boolean isAdmin = current.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
                        return new org.springframework.security.authorization.AuthorizationDecision(!adminOnly || isAdmin);
                    })
                    .anyRequest().permitAll());
        http.addFilterAfter(new PortableLoginFilter(portableLinks), org.springframework.web.filter.CorsFilter.class);
        return http.build();
    }

    private static boolean signedIn(org.springframework.security.core.Authentication auth) {
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
    }
    private static void writeError(HttpServletResponse response, tools.jackson.databind.ObjectMapper json,
            int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), Map.of("code", code, "message", message));
    }
}
