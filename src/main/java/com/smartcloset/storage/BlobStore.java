package com.smartcloset.storage;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Where image files and their small JSON companions live. Keys are the paths relative to the storage directory
 * ({@code workflows/{job}/step-2.png}), so the database never changes when files move between backends.
 * The database, the worker lock and the deletion ledger always stay on the local disk.
 */
public interface BlobStore {
    record Entry(String key, long size, long lastModifiedMs) {}

    /** Durable once it returns: callers commit the database only after this. */
    void put(String key, byte[] bytes, String contentType) throws IOException;
    /** The bytes, or null when the object does not exist. */
    byte[] get(String key) throws IOException;
    boolean exists(String key) throws IOException;
    void delete(String key) throws IOException;
    /** Every object whose key starts with the prefix. */
    List<Entry> list(String prefix) throws IOException;
    /** Changes whenever the object's content may have changed; null when it does not exist. Derived files key on it. */
    default String version(String key) throws IOException {
        for (Entry entry : list(key)) if (entry.key().equals(key)) return entry.size() + "-" + entry.lastModifiedMs();
        return null;
    }
    /** A time-limited direct link the browser can fetch without the server, when the backend has one. */
    default Optional<Link> link(String key, Duration window) { return Optional.empty(); }
    /** "local" or "s3". */
    String kind();

    /** A direct link and the moment it stops working. */
    record Link(URI uri, long expiresAtMs) {}

    static String contentType(String key) {
        String k = key.toLowerCase();
        if (k.endsWith(".png")) return "image/png";
        if (k.endsWith(".jpg") || k.endsWith(".jpeg")) return "image/jpeg";
        if (k.endsWith(".webp")) return "image/webp";
        if (k.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    /** Keys are relative, slash separated, without empty, "." or ".." segments. */
    static String checked(String key) {
        if (key == null || key.isEmpty() || key.startsWith("/") || key.contains("\\") || key.length() > 512)
            throw new IllegalArgumentException("Invalid storage key");
        for (String part : key.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IllegalArgumentException("Invalid storage key");
        return key;
    }
}
