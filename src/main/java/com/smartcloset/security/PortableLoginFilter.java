package com.smartcloset.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Enforce portable grants on every request, including asset and administrator routes. */
final class PortableLoginFilter extends OncePerRequestFilter {
    private final PortableLoginService links;
    PortableLoginFilter(PortableLoginService links) { this.links = links; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/api/auth/portable") && !request.isSecure() && !(loopback(request.getRemoteAddr()) && loopbackHost(request.getServerName()))) {
            deny(response, 400, "便携登录需要 HTTPS 连接。"); return;
        }
        var session = request.getSession(false);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        Object grant = session == null ? null : session.getAttribute(PortableLoginService.SESSION_LINK);
        if (grant instanceof String id) {
            if (auth == null || !links.validSession(id, auth.getName())) {
                session.invalidate(); SecurityContextHolder.clearContext();
                if (path.startsWith("/api/")) { deny(response, 401, "登录授权已撤销或到期，请重新登录。"); return; }
            } else if (path.equals("/api/auth/password") || path.startsWith("/api/auth/portable-links")) {
                deny(response, 403, "链接登录不能修改密码或管理登录授权，请使用账号密码登录。"); return;
            }
        }
        chain.doFilter(request, response);
    }
    private static boolean loopback(String address) { return "127.0.0.1".equals(address) || "::1".equals(address) || "0:0:0:0:0:0:0:1".equals(address); }
    private static boolean loopbackHost(String host) { return "localhost".equalsIgnoreCase(host) || "[::1]".equals(host) || loopback(host); }
    private static void deny(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8"); response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}
