package com.smartcloset.management;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.smartcloset.management.auth.HmacCanonical;
import com.smartcloset.management.auth.ReplayGuard;
import org.junit.jupiter.api.Test;

class HmacCanonicalTest {
    /** Shared cross-implementation vector; smart-closet-admin-server's signer test asserts the same literals. */
    @Test void sharedTestVector() {
        String canonical = HmacCanonical.canonical("GET", "/api/manage/v1/users", "size=1&q=a+b", "1790000000000",
                "nonce-0123456789abcdef", "admin", "trace-1", new byte[0]);
        assertEquals("""
                CLOSET-HMAC-SHA256
                GET
                /api/manage/v1/users
                q=a%20b&size=1
                1790000000000
                nonce-0123456789abcdef
                admin
                trace-1
                e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855""", canonical);
        assertEquals("QrNFOGRrxbMHYQK3h_5AzEq2QbFkYErcWAuygoZ8r88", HmacCanonical.sign("0123456789abcdef0123456789abcdef", canonical));
    }

    @Test void queryIsDecodedReencodedAndSorted() {
        assertEquals("", HmacCanonical.canonicalQuery(null));
        assertEquals("a=&metric=garments_created&metric=image_units&q=%E8%A1%A3%20~",
                HmacCanonical.canonicalQuery("metric=image_units&q=%E8%A1%A3+%7E&a&metric=garments_created"));
        assertEquals(HmacCanonical.canonicalQuery("q=a%20b"), HmacCanonical.canonicalQuery("q=a+b"));
    }

    @Test void missingHeadersCanonicalizeAsEmptyLines() {
        String canonical = HmacCanonical.canonical("post", "/x", null, "1", "n", null, null, "{}".getBytes());
        assertEquals("CLOSET-HMAC-SHA256\nPOST\n/x\n\n1\nn\n\n\n44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a", canonical);
    }

    @Test void replayGuardRejectsRepeatsUntilExpiryAndBoundsMemory() {
        var guard = new ReplayGuard(1000, 2);
        assertEquals(ReplayGuard.Result.FRESH, guard.remember("k", "a", 0));
        assertEquals(ReplayGuard.Result.REPLAYED, guard.remember("k", "a", 500));
        assertEquals(ReplayGuard.Result.FRESH, guard.remember("other", "a", 500));
        assertEquals(ReplayGuard.Result.FULL, guard.remember("k", "b", 900));
        assertEquals(ReplayGuard.Result.FRESH, guard.remember("k", "a", 1001));
    }
}
