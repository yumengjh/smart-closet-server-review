package com.smartcloset.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/** Files under the storage directory, written with temp file + fsync + atomic rename (the behaviour before buckets). */
public class LocalBlobStore implements BlobStore {
    private final Path root;

    public LocalBlobStore(Path root) { this.root = root.toAbsolutePath().normalize(); }

    public Path root() { return root; }
    /** The file behind a key; only the local backend has one (used to stream with Range support). */
    public Path path(String key) {
        Path path = root.resolve(BlobStore.checked(key)).normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("Invalid storage key");
        return path;
    }

    @Override public void put(String key, byte[] bytes, String contentType) throws IOException {
        Path target = path(key);
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), ".pending-", ".tmp");
        try {
            try (var channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
    }
    @Override public byte[] get(String key) throws IOException {
        Path path = path(key);
        try { return Files.readAllBytes(path); } catch (NoSuchFileException e) { return null; }
    }
    @Override public boolean exists(String key) { return Files.isRegularFile(path(key)); }
    @Override public void delete(String key) throws IOException { Files.deleteIfExists(path(key)); }
    @Override public List<Entry> list(String prefix) throws IOException {
        var out = new ArrayList<Entry>();
        // The prefix may end inside a name ("private-person/ab"), so walk its directory and filter.
        Path dir = prefix.isEmpty() ? root : prefix.endsWith("/") ? path(prefix.substring(0, prefix.length() - 1)) : path(prefix).getParent();
        if (!Files.isDirectory(dir)) return out;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String key = root.relativize(p).toString().replace('\\', '/');
                if (key.startsWith(prefix)) out.add(new Entry(key, Files.size(p), Files.getLastModifiedTime(p).toMillis()));
            }
        }
        return out;
    }
    @Override public String kind() { return "local"; }
}
