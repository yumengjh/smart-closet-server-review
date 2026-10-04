package com.smartcloset.security;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClosetCorsPropertiesTest {
    @Test void originsCannotBeWildcardsPathsOrContainCredentials() {
        for (String origin : List.of("*", "https://*.example.com", "https://example.com/", "https://user:pass@example.com", "https://example.com?q=1", "https://example.com#x", "null"))
            assertThrows(IllegalArgumentException.class, () -> new ClosetCorsProperties(List.of(origin)), origin);
        assertEquals(List.of("https://closet.example.com"), new ClosetCorsProperties(List.of(" https://closet.example.com ", "")).allowedOrigins());
        assertTrue(new ClosetCorsProperties(null).allowedOrigins().isEmpty());
    }
}
