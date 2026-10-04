package com.smartcloset.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PortableLoginFilterTest {
    @Test void rejectsRemoteHttpEvenWhenProxyConnectsFromLoopback() throws Exception {
        for (String peer : new String[]{"203.0.113.5", "127.0.0.1"}) {
            var request = new MockHttpServletRequest("POST", "/api/auth/portable-login");
            request.setRemoteAddr(peer); request.setServerName("closet.example.com");
            var response = new MockHttpServletResponse(); var chain = new MockFilterChain();
            new PortableLoginFilter(mock(PortableLoginService.class)).doFilter(request, response, chain);
            assertEquals(400, response.getStatus()); assertNull(chain.getRequest());
        }
    }

    @Test void acceptsHttpsAndDirectLoopbackDevelopment() throws Exception {
        for (boolean secure : new boolean[]{true, false}) {
            var request = new MockHttpServletRequest("POST", "/api/auth/portable-login");
            request.setSecure(secure); request.setRemoteAddr(secure ? "203.0.113.5" : "127.0.0.1");
            request.setServerName(secure ? "closet.example.com" : "localhost");
            var response = new MockHttpServletResponse(); var chain = new MockFilterChain();
            new PortableLoginFilter(mock(PortableLoginService.class)).doFilter(request, response, chain);
            assertEquals(200, response.getStatus()); assertSame(request, chain.getRequest());
        }
    }
}
