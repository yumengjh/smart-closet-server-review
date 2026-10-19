package com.smartcloset.portable;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortableShutdownWatcherTest {
    @TempDir Path dir;

    @Test void exitsOnceWhenTheFileAppearsAndRemovesIt() throws Exception {
        var exited = new CountDownLatch(1);
        Path file = dir.resolve("shutdown");
        var watcher = new PortableShutdownWatcher(file, exited::countDown);
        watcher.check();
        assertEquals(1, exited.getCount(), "no file, no exit");
        watcher.start(Duration.ofMillis(20));
        Files.writeString(file, "stop");
        assertTrue(exited.await(5, TimeUnit.SECONDS));
        assertFalse(Files.exists(file));
    }
}
