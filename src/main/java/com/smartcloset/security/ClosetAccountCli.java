package com.smartcloset.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Offline account commands avoid starting a second image worker against the live storage directory. */
public final class ClosetAccountCli {
    private ClosetAccountCli() {}

    public static boolean isCommand(String[] args) {
        for (String arg : args) if (arg.startsWith("--closet-command=")) return true;
        return false;
    }

    public static void run(String[] args) {
        try {
            Map<String, String> options = options(args);
            String command = required(options, "closet-command");
            Path storage = Path.of(System.getenv().getOrDefault("CLOSET_STORAGE_DIR", "./data")).toAbsolutePath().normalize();
            Files.createDirectories(storage.resolve("db"));
            String url = System.getenv().getOrDefault("CLOSET_DB_URL", "jdbc:h2:file:" + storage.resolve("db/closet"));
            String username = System.getenv().getOrDefault("CLOSET_DB_USER", "sa");
            String password = System.getenv().getOrDefault("CLOSET_DB_PASSWORD", "");
            Flyway.configure().dataSource(url, username, password).locations("classpath:db/migration/h2").callbacks(new com.smartcloset.processing.ProcessingMigrationBackup(storage)).load().migrate();
            var source = new DriverManagerDataSource(url, username, password);
            var jdbc = new JdbcTemplate(source);
            var encoder = new BCryptPasswordEncoder(12);
            switch (command) {
                case "create-user", "create-admin" -> {
                    String name = required(options, "username"), initial = required(options, "initial-password");
                    validate(name, initial);
                    if (count(jdbc, name) != 0) throw new IllegalArgumentException("账号已存在：" + name);
                    jdbc.update("INSERT INTO closet_user(username,password_hash,role,must_change_password,enabled,created_at_ms) VALUES(?,?,?,1,1,?)",
                            name, encoder.encode(initial), command.equals("create-admin") ? "ADMIN" : "USER", System.currentTimeMillis());
                    System.out.println("Created " + command + " account: " + name + ". First login requires a password change.");
                }
                case "reset-password" -> {
                    String name = required(options, "username"), initial = required(options, "initial-password");
                    validate(name, initial);
                    if (count(jdbc, name) == 0) throw new IllegalArgumentException("账号不存在：" + name);
                    jdbc.update("UPDATE closet_user SET password_hash=?,must_change_password=1 WHERE username=?", encoder.encode(initial), name);
                    System.out.println("Password reset. Next login requires a password change: " + name);
                }
                case "disable-user", "enable-user" -> {
                    String name = required(options, "username");
                    if (count(jdbc, name) == 0) throw new IllegalArgumentException("账号不存在：" + name);
                    jdbc.update("UPDATE closet_user SET enabled=? WHERE username=?", command.equals("enable-user") ? 1 : 0, name);
                    System.out.println((command.equals("enable-user") ? "Enabled " : "Disabled ") + name);
                }
                case "claim-legacy" -> {
                    String name = required(options, "username");
                    if (count(jdbc, name) == 0) throw new IllegalArgumentException("账号不存在：" + name);
                    int tasks = jdbc.update("UPDATE workflow_job SET owner_username=? WHERE owner_username IS NULL", name);
                    int garments = jdbc.update("UPDATE garment SET owner_username=? WHERE owner_username IS NULL", name);
                    int segmentations = claimSegmentations(jdbc, storage, name);
                    System.out.printf("Legacy data claimed by %s: %d tasks, %d garments, %d standalone segmentations.%n", name, tasks, garments, segmentations);
                }
                default -> throw new IllegalArgumentException("Unknown --closet-command: " + command);
            }
        } catch (Exception e) {
            System.err.println("Closet account command failed: " + e.getMessage());
            System.exit(2);
        }
    }

    private static int claimSegmentations(JdbcTemplate jdbc, Path storage, String username) throws Exception {
        Path root = storage.resolve("segmentations");
        if (!Files.isDirectory(root)) root = storage;
        int claimed = 0;
        try (var entries = Files.list(root)) {
            for (Path entry : entries.filter(Files::isDirectory).toList()) {
                if (!Files.isRegularFile(entry.resolve("result.json"))) continue;
                try {
                    String id = UUID.fromString(entry.getFileName().toString()).toString();
                    jdbc.update("MERGE INTO standalone_segmentation_owner(segmentation_id,owner_username,created_at_ms) KEY(segmentation_id) VALUES(?,?,?)",
                            id, username, System.currentTimeMillis());
                    claimed++;
                } catch (IllegalArgumentException ignored) { }
            }
        }
        return claimed;
    }

    private static Map<String, String> options(String[] args) {
        var result = new HashMap<String, String>();
        for (String arg : args) if (arg.startsWith("--")) for (String part : arg.split(",(?=--)", -1)) {
            int equals = part.indexOf('=');
            if (equals > 2) result.put(part.substring(2, equals), part.substring(equals + 1));
        }
        return result;
    }
    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing --" + key + "=...");
        return value;
    }
    private static int count(JdbcTemplate jdbc, String username) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM closet_user WHERE username=?", Integer.class, username);
    }
    private static void validate(String username, String password) {
        if (!username.matches("[A-Za-z0-9_.-]{2,80}")) throw new IllegalArgumentException("用户名需为2到80位英文字母、数字、点、下划线或短横线。");
        if (password.length() < 12 || password.length() > 200) throw new IllegalArgumentException("密码长度需为12到200个字符。");
    }
}
