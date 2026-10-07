package com.smartcloset.management;

import com.smartcloset.management.auth.HmacManagementAuthenticator;
import com.smartcloset.management.auth.ManagementAuthenticationFilter;
import com.smartcloset.management.auth.ManagementAuthenticator;
import com.smartcloset.management.auth.ReplayGuard;
import java.time.Clock;
import java.util.LinkedHashMap;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Entry point of the optional management module. Disabled unless closet.management.enabled=true;
 * the closet core never references this package.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnManagementEnabled
@EnableConfigurationProperties(ManagementProperties.class)
public class ManagementConfiguration {
    public static final String PATH = "/api/manage/**";

    @Bean ManagementAuthenticator managementAuthenticator(ManagementProperties properties) {
        return new HmacManagementAuthenticator(properties,
                new ReplayGuard(properties.auth().nonceTtl().toMillis(), 100_000), Clock.systemUTC());
    }

    @Bean @Order(1)
    SecurityFilterChain managementSecurity(HttpSecurity http, ManagementAuthenticator authenticator,
            ManagementProperties properties, ObjectMapper json) throws Exception {
        var filter = new ManagementAuthenticationFilter(authenticator, json, properties.maxBodyBytes());
        http.securityMatcher(PATH)
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable())
            .securityContext(context -> context.requireExplicitSave(true))
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .logout(logout -> logout.disable())
            .addFilterBefore(filter, AnonymousAuthenticationFilter.class)
            .exceptionHandling(errors -> errors
                    .authenticationEntryPoint((request, response, e) -> problem(json, response, 401, "MANAGEMENT_SIGNATURE_MISSING", "缺少管理接口签名。"))
                    .accessDeniedHandler((request, response, e) -> problem(json, response, 401, "MANAGEMENT_SIGNATURE_MISSING", "缺少管理接口签名。")))
            .authorizeHttpRequests(auth -> auth
                    .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                    .anyRequest().hasAuthority(ManagementAuthenticationFilter.AUTHORITY));
        return http.build();
    }

    private static void problem(ObjectMapper json, jakarta.servlet.http.HttpServletResponse response, int status, String code,
            String detail) throws java.io.IOException {
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "urn:closet:problem:" + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
        body.put("title", "管理接口认证失败"); body.put("status", status); body.put("detail", detail); body.put("code", code);
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), body);
    }
}
