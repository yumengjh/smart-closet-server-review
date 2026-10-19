package com.smartcloset.portable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Portable package only (docs/PORTABLE_PACKAGE.md): the launcher asks the server to stop by creating a file, because a
 * background java.exe on Windows cannot receive a signal and a forced kill could lose the last H2 writes. Off unless
 * closet.portable.shutdown-file is set; no network surface.
 */
@Component
@ConditionalOnProperty(name = "closet.portable.shutdown-file")
public class PortableShutdownWatcher {
    private static final Logger log = LoggerFactory.getLogger(PortableShutdownWatcher.class);
    private final Path file;
    private final Runnable exit;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "portable-shutdown-watcher"); thread.setDaemon(true); return thread;
    });

    @Autowired
    public PortableShutdownWatcher(@Value("${closet.portable.shutdown-file}") String file, ConfigurableApplicationContext context) {
        this(Path.of(file), () -> System.exit(SpringApplication.exit(context, () -> 0)));
    }

    PortableShutdownWatcher(Path file, Runnable exit) { this.file = file.toAbsolutePath().normalize(); this.exit = exit; }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        // A request left over from a previous run must not stop this one.
        try { Files.deleteIfExists(file); } catch (IOException e) { log.warn("portable shutdown file not removable: {}", file); }
        start(Duration.ofSeconds(1));
    }

    void start(Duration interval) {
        timer.scheduleWithFixedDelay(this::check, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("Portable launcher shutdown watcher active: {}", file);
    }

    void check() {
        if (!Files.exists(file)) return;
        try { Files.deleteIfExists(file); } catch (IOException ignored) { }
        log.info("Portable launcher requested shutdown");
        timer.shutdown();
        // A separate non-daemon thread: System.exit waits for shutdown hooks, which must not wait on this timer.
        new Thread(exit, "portable-shutdown").start();
    }
}
