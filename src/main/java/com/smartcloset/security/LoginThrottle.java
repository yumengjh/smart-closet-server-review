package com.smartcloset.security;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/** In-memory sliding windows for failed logins and registrations; the service runs as a single instance. */
@Component
public class LoginThrottle {
    static final long LOGIN_WINDOW_MS = 15 * 60 * 1000L, REGISTER_WINDOW_MS = 60 * 60 * 1000L;
    private static final int MAX_KEYS = 50_000;
    private final AccountProperties properties;
    private final Map<String, ArrayDeque<Long>> hits = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    @org.springframework.beans.factory.annotation.Autowired
    public LoginThrottle(AccountProperties properties) { this(properties, System::currentTimeMillis); }
    LoginThrottle(AccountProperties properties, LongSupplier clock) { this.properties = properties; this.clock = clock; }

    /** Seconds until the next login attempt is allowed, or 0. */
    public long loginRetryAfter(String username, String address) {
        return Math.max(retryAfter(userKey(username), properties.loginMaxFailures(), LOGIN_WINDOW_MS),
                retryAfter("login-addr:" + address, properties.loginMaxFailuresPerAddress(), LOGIN_WINDOW_MS));
    }
    public void loginFailed(String username, String address) { add(userKey(username)); add("login-addr:" + address); }
    public void loginSucceeded(String username) { hits.remove(userKey(username)); }

    public record UserLock(boolean locked, int recentFailures, long retryAfterSeconds) {}
    /** Failed logins of one username in the current window; address limits are not per user and are not shown. */
    public UserLock userLock(String username) {
        long wait = retryAfter(userKey(username), properties.loginMaxFailures(), LOGIN_WINDOW_MS);
        var queue = hits.get(userKey(username));
        int failures = 0;
        if (queue != null) synchronized (queue) { failures = queue.size(); }
        return new UserLock(wait > 0, failures, wait);
    }
    /** Administrator unlock: forgets the username's failures (address windows are unaffected). */
    public void clearUser(String username) { hits.remove(userKey(username)); }

    /** Counts the attempt when allowed; returns seconds to wait otherwise. */
    public long tryRegister(String address) {
        String key = "register:" + address;
        long wait = retryAfter(key, properties.registerPerAddressHourly(), REGISTER_WINDOW_MS);
        if (wait == 0) add(key);
        return wait;
    }

    private static String userKey(String username) { return "login-user:" + (username == null ? "" : username.strip().toLowerCase(Locale.ROOT)); }

    private long retryAfter(String key, int limit, long window) {
        var queue = hits.get(key);
        if (queue == null) return 0;
        synchronized (queue) {
            long now = clock.getAsLong();
            while (!queue.isEmpty() && queue.peekFirst() <= now - window) queue.pollFirst();
            if (queue.size() < limit) return 0;
            return Math.max(1, (queue.peekFirst() + window - now + 999) / 1000);
        }
    }

    private void add(String key) {
        if (hits.size() > MAX_KEYS) prune();
        var queue = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (queue) { queue.addLast(clock.getAsLong()); if (queue.size() > 200) queue.pollFirst(); }
    }

    private void prune() {
        long cutoff = clock.getAsLong() - REGISTER_WINDOW_MS;
        hits.entrySet().removeIf(e -> { synchronized (e.getValue()) { return e.getValue().isEmpty() || e.getValue().peekLast() <= cutoff; } });
    }
}
