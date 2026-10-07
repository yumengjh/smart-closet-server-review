package com.smartcloset.management.auth;

import com.smartcloset.management.ManagementProblems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** Not a bean on purpose: it must only run inside the management security chain, never as a global servlet filter. */
public class ManagementAuthenticationFilter extends OncePerRequestFilter {
    public static final String AUTHORITY = "MANAGEMENT";
    private static final Logger log = LoggerFactory.getLogger(ManagementAuthenticationFilter.class);
    private final ManagementAuthenticator authenticator;
    private final ObjectMapper json;
    private final int maxBodyBytes;

    public ManagementAuthenticationFilter(ManagementAuthenticator authenticator, ObjectMapper json, int maxBodyBytes) {
        this.authenticator = authenticator; this.json = json; this.maxBodyBytes = maxBodyBytes;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader("X-Request-Id");
        String requestId = supplied != null && supplied.matches("[A-Za-z0-9._:-]{1,128}") ? supplied : UUID.randomUUID().toString();
        request.setAttribute(ManagementProblems.REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader("X-Request-Id", requestId);
        if (request.getContentLengthLong() > maxBodyBytes) { tooLarge(response, requestId); return; }
        byte[] body = HmacCanonical.readAll(request.getInputStream(), maxBodyBytes);
        if (body == null) { tooLarge(response, requestId); return; }
        ManagementPrincipal principal;
        try {
            principal = authenticator.authenticate(request, body, requestId);
        } catch (ManagementAuthenticator.Failure failure) {
            log.warn("management request rejected code={} method={} path={} requestId={}", failure.code(), request.getMethod(),
                    request.getRequestURI(), requestId);
            write(response, failure.status(), failure.code(), "管理接口认证失败", failure.getMessage(), requestId);
            return;
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new SimpleGrantedAuthority(AUTHORITY))));
        SecurityContextHolder.setContext(context);
        try { chain.doFilter(new CachedBodyRequest(request, body), response); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private void tooLarge(HttpServletResponse response, String requestId) throws IOException {
        write(response, 413, "PAYLOAD_TOO_LARGE", "请求体过大", "请求体超过 " + maxBodyBytes + " 字节。", requestId);
    }

    void write(HttpServletResponse response, int status, String code, String title, String detail, String requestId) throws IOException {
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "urn:closet:problem:" + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
        body.put("title", title); body.put("status", status); body.put("detail", detail);
        body.put("code", code); body.put("requestId", requestId);
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), body);
    }

    static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        CachedBodyRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] b, int off, int len) { return input.read(b, off, len); }
            };
        }
        @Override public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
        }
        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
    }
}
