package com.smartcloset.management.auth;

import java.util.concurrent.ConcurrentHashMap;

/** In-memory nonce memory for a single-instance deployment; replace with a shared store when running several instances. */
public class ReplayGuard {
    public enum Result { FRESH, REPLAYED, FULL }
    private final ConcurrentHashMap<String, Long> seen = new ConcurrentHashMap<>();
    private final long ttlMs;
    private final int capacity;

    public ReplayGuard(long ttlMs, int capacity) { this.ttlMs = ttlMs; this.capacity = capacity; }

    public synchronized Result remember(String keyId, String nonce, long nowMs) {
        String key = keyId + ":" + nonce;
        Long expires = seen.get(key);
        if (expires != null && expires > nowMs) return Result.REPLAYED;
        if (seen.size() >= capacity) seen.values().removeIf(at -> at <= nowMs);
        if (seen.size() >= capacity) return Result.FULL;
        seen.put(key, nowMs + ttlMs);
        return Result.FRESH;
    }
}
