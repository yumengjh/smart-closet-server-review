package com.smartcloset.security;

import com.smartcloset.ClosetProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Creates invited accounts and explicitly claims the legacy single-user dataset. */
@Component @Order(1)
public class ClosetAccountCommand implements ApplicationRunner {
    private final ClosetAccountService accounts;
    private final JdbcTemplate jdbc;
    private final ClosetProperties paths;
    private final ConfigurableApplicationContext context;
    public ClosetAccountCommand(ClosetAccountService accounts, JdbcTemplate jdbc, ClosetProperties paths, ConfigurableApplicationContext context) {
        this.accounts = accounts; this.jdbc = jdbc; this.paths = paths; this.context = context;
    }
    @Override public void run(ApplicationArguments args) throws Exception {
        String bootstrapName = System.getenv("CLOSET_BOOTSTRAP_USERNAME");
        String bootstrapPassword = System.getenv("CLOSET_BOOTSTRAP_PASSWORD");
        if (bootstrapName != null && !bootstrapName.isBlank() && bootstrapPassword != null && !bootstrapPassword.isBlank()) {
            accounts.bootstrap(bootstrapName, bootstrapPassword);
            LoggerFactory.getLogger(getClass()).info("Bootstrap administrator is ready: {}", bootstrapName);
        }
        var commands = args.getOptionValues("closet-command");
        if (commands == null || commands.isEmpty()) return;
        String command = commands.getFirst();
        try {
            switch (command) {
                case "create-user", "create-admin" -> {
                    String username = required(args, "username");
                    String password = required(args, "initial-password");
                    accounts.create(username, password, command.equals("create-admin") ? "ADMIN" : "USER");
                    LoggerFactory.getLogger(getClass()).info("Created {} account: {}", command, username);
                }
                case "reset-password" -> {
                    String username = required(args, "username"); accounts.resetPassword(username, required(args, "initial-password"));
                    LoggerFactory.getLogger(getClass()).info("Reset password; next login requires a password change: {}", username);
                }
                case "disable-user" -> { accounts.setEnabled(required(args, "username"), false); }
                case "enable-user" -> { accounts.setEnabled(required(args, "username"), true); }
                case "claim-legacy" -> claimLegacy(required(args, "username"));
                default -> throw new IllegalArgumentException("Unknown --closet-command: " + command);
            }
        } finally { context.close(); }
    }
    private void claimLegacy(String username) throws Exception {
        if (accounts.find(username) == null) throw new IllegalArgumentException("账号不存在：" + username);
        int jobs = jdbc.update("UPDATE workflow_job SET owner_username=? WHERE owner_username IS NULL", username);
        int garments = jdbc.update("UPDATE garment SET owner_username=? WHERE owner_username IS NULL", username);
        Path root = paths.storageDir().toAbsolutePath().normalize();
        int segmentations = 0;
        if (Files.isDirectory(root)) try (var children = Files.list(root)) {
            for (Path child : children.filter(Files::isDirectory).toList()) {
                if (!Files.isRegularFile(child.resolve("result.json"))) continue;
                try {
                    String id = UUID.fromString(child.getFileName().toString()).toString();
                    jdbc.update("MERGE INTO standalone_segmentation_owner(segmentation_id,owner_username,created_at_ms) KEY(segmentation_id) VALUES(?,?,?)",
                            id, username, System.currentTimeMillis()); segmentations++;
                } catch (IllegalArgumentException ignored) { }
            }
        }
        LoggerFactory.getLogger(getClass()).info("Legacy data claimed by {}: {} tasks, {} garments, {} standalone segmentations", username, jobs, garments, segmentations);
    }
    private static String required(ApplicationArguments args, String key) {
        var values = args.getOptionValues(key);
        if (values == null || values.isEmpty() || values.getFirst().isBlank()) throw new IllegalArgumentException("Missing --" + key);
        return values.getFirst();
    }
}
