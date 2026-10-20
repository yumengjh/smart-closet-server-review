package com.smartcloset.security;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LoginThrottleTest {
    final AtomicLong now = new AtomicLong(1_000_000_000L);
    final LoginThrottle throttle = new LoginThrottle(new AccountProperties(null, 3, 5, 2), now::get);

    @Test void usernameWindowSlidesAndSuccessClearsIt() {
        for (int i = 0; i < 3; i++) { assertEquals(0, throttle.loginRetryAfter("Alice", "10.0.0.1")); throttle.loginFailed("alice", "10.0.0.1"); }
        assertEquals(15 * 60, throttle.loginRetryAfter("ALICE", "10.0.0.2"));
        now.addAndGet(LoginThrottle.LOGIN_WINDOW_MS - 60_000);
        assertEquals(60, throttle.loginRetryAfter("alice", "10.0.0.2"));
        throttle.loginSucceeded("alice");
        assertEquals(0, throttle.loginRetryAfter("alice", "10.0.0.2"));
    }

    @Test void addressLimitCoversManyUsernames() {
        for (int i = 0; i < 5; i++) throttle.loginFailed("user" + i, "10.0.0.9");
        assertTrue(throttle.loginRetryAfter("fresh", "10.0.0.9") > 0);
        assertEquals(0, throttle.loginRetryAfter("fresh", "10.0.0.8"));
    }

    @Test void registrationIsCountedPerAddressAndHour() {
        assertEquals(0, throttle.tryRegister("10.0.0.3"));
        assertEquals(0, throttle.tryRegister("10.0.0.3"));
        assertTrue(throttle.tryRegister("10.0.0.3") > 0);
        now.addAndGet(LoginThrottle.REGISTER_WINDOW_MS);
        assertEquals(0, throttle.tryRegister("10.0.0.3"));
    }

    @Test void defaultsToInviteRegistration() {
        var defaults = new AccountProperties(null, null, null, null);
        assertEquals(AccountProperties.Registration.INVITE, defaults.registration());
    }

    @Test void inviteCodesAreNormalizedAndDeviceLabelsAreCoarse() {
        assertEquals("ABCD2345WXYZ", RegistrationService.normalize(" abcd-2345 wxyz "));
        assertNull(RegistrationService.normalize("ABCD-2345-WXY0"));
        assertEquals("Edge · Windows", ClosetSessionRevoker.device("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/130.0 Safari/537.36 Edg/130.0"));
        assertEquals("Chrome · Android", ClosetSessionRevoker.device("Mozilla/5.0 (Linux; Android 14) Chrome/130.0 Mobile Safari/537.36"));
        assertEquals("未知设备", ClosetSessionRevoker.device(null));
        assertThrows(IllegalArgumentException.class, () -> ClosetAccountService.normalizeDisplayName("a‮b"));
        assertNull(ClosetAccountService.normalizeDisplayName("   "));
    }
}
