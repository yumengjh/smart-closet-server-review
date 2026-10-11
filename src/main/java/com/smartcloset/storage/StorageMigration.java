package com.smartcloset.storage;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * {@code java -jar app.jar --closet-command=storage-migrate --step=scan|copy|verify|pull}: copies the image files of a
 * storage directory to the bucket configured by CLOSET_STORAGE_S3_* under the same relative keys, so the database
 * needs no change. Safe to repeat: an object whose size and sha256 already match is skipped. Never deletes anything.
 * <ul>
 * <li>scan: what would be copied, by kind; checks the database references when the database is free</li>
 * <li>copy: uploads new or changed files (works while the server runs)</li>
 * <li>verify: every file and every database reference is in the bucket (stop the server first: H2 allows one process)</li>
 * <li>pull: downloads objects the local directory lacks (to go back to local storage)</li>
 * </ul>
 */
public final class StorageMigration {
    /** Kinds of files that move to the bucket, by relative path. Everything else (db, backups, locks, ledger, temp) stays. */
    static final Map<String, Pattern> KINDS = new LinkedHashMap<>();
    static {
        String uuid = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
        KINDS.put("衣物图片与处理记录 workflows/", Pattern.compile("workflows/" + uuid + "/[A-Za-z0-9][A-Za-z0-9._-]*"));
        KINDS.put("批量录入 batches/", Pattern.compile("batches/" + uuid + "/[A-Za-z0-9][A-Za-z0-9._-]*"));
        KINDS.put("单独抠图与贴纸", Pattern.compile(uuid + "/(sticker-[A-Za-z0-9_-]+/)?[A-Za-z0-9][A-Za-z0-9._-]*"));
        KINDS.put("本人照片/基准人物/设计图 private-person/", Pattern.compile("private-person/" + uuid + "\\.png"));
        KINDS.put("OOTD 样本 ootd-samples/", Pattern.compile("ootd-samples/[A-Za-z0-9][A-Za-z0-9._-]*\\.png"));
    }

    private final Path root;
    private final BlobStore bucket;
    private final String dbUrl, dbUser, dbPassword;
    private final int threads;
    private final PrintStream out;

    StorageMigration(Path root, BlobStore bucket, String dbUrl, String dbUser, String dbPassword, int threads, PrintStream out) {
        this.root = root.toAbsolutePath().normalize(); this.bucket = bucket; this.dbUrl = dbUrl; this.dbUser = dbUser; this.dbPassword = dbPassword;
        this.threads = threads; this.out = out;
    }

    public static boolean isCommand(String[] args) { return Arrays.asList(args).contains("--closet-command=storage-migrate"); }

    /** Exit code: 0 done, 1 something is missing or failed, 2 wrong usage or configuration. */
    public static int run(String[] args, Map<String, String> env, PrintStream out) {
        var options = new HashMap<String, String>();
        for (String arg : args) if (arg.startsWith("--") && arg.indexOf('=') > 2) options.put(arg.substring(2, arg.indexOf('=')), arg.substring(arg.indexOf('=') + 1));
        String step = options.getOrDefault("step", "");
        if (!Set.of("scan", "copy", "verify", "pull").contains(step)) {
            out.println("用法：--closet-command=storage-migrate --step=scan|copy|verify|pull [--threads=8]");
            out.println("  目标存储桶读取 CLOSET_STORAGE_S3_ENDPOINT/REGION/BUCKET/ACCESS_KEY/SECRET_KEY/PREFIX/PATH_STYLE；");
            out.println("  本机目录读取 CLOSET_STORAGE_DIR（默认 ./data），数据库读取 CLOSET_DB_URL（默认目录下 db/closet）。");
            return 2;
        }
        try {
            var settings = StorageSettings.fromEnv(withType(env));
            Path root = Path.of(env.getOrDefault("CLOSET_STORAGE_DIR", "./data"));
            if (!Files.isDirectory(root)) { out.println("本机存储目录不存在：" + root.toAbsolutePath()); return 2; }
            String url = env.getOrDefault("CLOSET_DB_URL", "jdbc:h2:file:" + root.toAbsolutePath().normalize().resolve("db/closet"));
            int threads = Integer.parseInt(options.getOrDefault("threads", "8"));
            var store = new S3BlobStore(settings);
            out.println("目标：" + settings.endpoint() + " 桶 " + settings.bucket() + (settings.prefix().isEmpty() ? "" : " 前缀 " + settings.prefix()));
            out.println("本机目录：" + root.toAbsolutePath().normalize());
            store.probe();
            var migration = new StorageMigration(root, store, url, env.getOrDefault("CLOSET_DB_USER", "sa"), env.getOrDefault("CLOSET_DB_PASSWORD", ""), Math.max(1, Math.min(32, threads)), out);
            return switch (step) {
                case "scan" -> migration.scan();
                case "copy" -> migration.copy();
                case "verify" -> migration.verify();
                default -> migration.pull();
            };
        } catch (IllegalArgumentException e) {
            out.println("配置错误：" + e.getMessage()); return 2;
        } catch (Exception e) {
            out.println("失败：" + e.getMessage()); return 1;
        }
    }

    private static Map<String, String> withType(Map<String, String> env) {
        var copy = new HashMap<>(env); copy.put("CLOSET_STORAGE_TYPE", "s3"); return copy;
    }

    /** Relative keys of the files that move, grouped by kind. */
    Map<String, List<BlobStore.Entry>> localFiles() throws IOException {
        var groups = new LinkedHashMap<String, List<BlobStore.Entry>>();
        KINDS.keySet().forEach(k -> groups.put(k, new ArrayList<>()));
        try (var walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                String key = root.relativize(p).toString().replace('\\', '/');
                String kind = kind(key);
                if (kind != null) groups.get(kind).add(new BlobStore.Entry(key, Files.size(p), Files.getLastModifiedTime(p).toMillis()));
            }
        }
        return groups;
    }

    static String kind(String key) {
        for (var e : KINDS.entrySet()) if (e.getValue().matcher(key).matches() && !key.endsWith(".tmp") && !key.endsWith(".lock")) return e.getKey();
        return null;
    }

    int scan() throws IOException {
        var groups = localFiles();
        long files = 0, bytes = 0;
        out.println("要迁移的文件：");
        for (var e : groups.entrySet()) {
            long size = e.getValue().stream().mapToLong(BlobStore.Entry::size).sum();
            files += e.getValue().size(); bytes += size;
            out.printf("  %-36s %7d 个  %s%n", e.getKey(), e.getValue().size(), human(size));
        }
        out.printf("  合计 %d 个文件，%s%n", files, human(bytes));
        var present = new HashSet<String>();
        groups.values().forEach(list -> list.forEach(entry -> present.add(entry.key())));
        try (var db = database()) {
            var refs = references(db);
            long missing = refs.stream().filter(r -> !present.contains(r.key())).count();
            out.printf("数据库引用 %d 个文件，其中本机缺失 %d 个（迁移前就不存在，迁移不会让它们变好或变坏）%n", refs.size(), missing);
        } catch (SQLException e) {
            out.println("数据库正在被后端使用或无法打开，跳过数据库核对（停服后 verify 会核对）：" + firstLine(e));
        }
        return 0;
    }

    int copy() throws Exception {
        var all = localFiles().values().stream().flatMap(List::stream).toList();
        long total = all.stream().mapToLong(BlobStore.Entry::size).sum();
        out.printf("开始复制 %d 个文件（%s），并发 %d%n", all.size(), human(total), threads);
        var uploaded = new AtomicLong(); var skipped = new AtomicLong(); var done = new AtomicLong(); var sent = new AtomicLong();
        var failures = new ConcurrentLinkedQueue<String>();
        long started = System.currentTimeMillis(); var lastReport = new AtomicLong(started);
        var pool = Executors.newFixedThreadPool(threads);
        try {
            var futures = new ArrayList<Future<?>>();
            for (var entry : all) futures.add(pool.submit(() -> {
                try {
                    byte[] bytes = Files.readAllBytes(root.resolve(entry.key()));
                    String sha = SigV4.hex(SigV4.sha256(bytes));
                    var head = bucket instanceof S3BlobStore s3 ? s3.head(entry.key()) : null;
                    if (head != null && head.size() == bytes.length && sha.equals(head.sha256())) skipped.incrementAndGet();
                    else { bucket.put(entry.key(), bytes, BlobStore.contentType(entry.key())); uploaded.incrementAndGet(); sent.addAndGet(bytes.length); }
                } catch (Exception e) {
                    failures.add(entry.key() + "：" + firstLine(e));
                }
                long n = done.incrementAndGet(), now = System.currentTimeMillis(), last = lastReport.get();
                if (now - last > 5000 && lastReport.compareAndSet(last, now))
                    out.printf("  进度 %d/%d，已上传 %d、跳过 %d、失败 %d，已发送 %s%n", n, all.size(), uploaded.get(), skipped.get(), failures.size(), human(sent.get()));
                return null;
            }));
            for (var f : futures) f.get();
        } finally { pool.shutdown(); }
        out.printf("复制完成：上传 %d 个（%s），已存在跳过 %d 个，失败 %d 个，用时 %d 秒%n", uploaded.get(), human(sent.get()), skipped.get(), failures.size(), (System.currentTimeMillis() - started) / 1000);
        failures.stream().limit(30).forEach(f -> out.println("  失败：" + f));
        if (!failures.isEmpty()) out.println("可以直接再运行一次 copy，已传过的会跳过。");
        return failures.isEmpty() ? 0 : 1;
    }

    int verify() throws IOException {
        var remote = new HashMap<String, Long>();
        for (var e : bucket.list("")) remote.put(e.key(), e.size());
        var local = localFiles().values().stream().flatMap(List::stream).toList();
        var notCopied = new ArrayList<String>();
        for (var e : local) { Long size = remote.get(e.key()); if (size == null || size != e.size()) notCopied.add(e.key()); }
        out.printf("本机 %d 个文件，存储桶 %d 个对象；未复制或大小不一致 %d 个%n", local.size(), remote.size(), notCopied.size());
        notCopied.stream().limit(30).forEach(k -> out.println("  未复制：" + k));
        var localKeys = new HashSet<String>(); local.forEach(e -> localKeys.add(e.key()));
        List<Reference> refs;
        try (var db = database()) { refs = references(db); }
        catch (SQLException e) {
            out.println("无法读取数据库（请先停止后端再 verify）：" + firstLine(e)); return 1;
        }
        var lost = new ArrayList<Reference>(); int alreadyMissing = 0, sizeMismatch = 0;
        for (var r : refs) {
            Long size = remote.get(r.key());
            if (size == null) { if (localKeys.contains(r.key())) lost.add(r); else alreadyMissing++; }
            else if (r.size() != null && !r.size().equals(size)) { lost.add(r); sizeMismatch++; }
        }
        out.printf("数据库引用 %d 个文件：存储桶中缺失 %d 个（其中大小不一致 %d 个），本机原本就缺失 %d 个%n", refs.size(), lost.size(), sizeMismatch, alreadyMissing);
        lost.stream().limit(30).forEach(r -> out.println("  缺失：" + r.key() + "（" + r.source() + "）"));
        boolean ok = notCopied.isEmpty() && lost.isEmpty();
        out.println(ok ? "核对通过：可以切换到存储桶。" : "核对未通过：请再运行 copy 后重新 verify。");
        return ok ? 0 : 1;
    }

    int pull() throws IOException {
        var local = new LocalBlobStore(root);
        int pulled = 0, kept = 0, failed = 0;
        for (var e : bucket.list("")) {
            if (kind(e.key()) == null) continue;
            Path path = local.path(e.key());
            if (Files.isRegularFile(path) && Files.size(path) == e.size()) { kept++; continue; }
            try {
                byte[] bytes = bucket.get(e.key());
                if (bytes == null) continue;
                local.put(e.key(), bytes, BlobStore.contentType(e.key())); pulled++;
            } catch (IOException ex) { failed++; out.println("  失败：" + e.key() + "：" + firstLine(ex)); }
        }
        out.printf("下载 %d 个，本机已有 %d 个，失败 %d 个%n", pulled, kept, failed);
        return failed == 0 ? 0 : 1;
    }

    /** A file the database points at, with its expected size when the database records one. */
    record Reference(String key, Long size, String source) {}

    List<Reference> references(Connection db) throws SQLException {
        var refs = new ArrayList<Reference>();
        try (var s = db.createStatement()) {
            try (var r = s.executeQuery("SELECT job_id, file_name, byte_size FROM image_asset")) {
                while (r.next()) refs.add(new Reference("workflows/" + r.getString(1) + "/" + r.getString(2), r.getLong(3), "image_asset"));
            }
            try (var r = s.executeQuery("SELECT id FROM private_media WHERE state='ACTIVE'")) {
                while (r.next()) refs.add(new Reference("private-person/" + r.getString(1) + ".png", null, "private_media"));
            }
            try (var r = s.executeQuery("SELECT image_path FROM ootd_sample WHERE image_path IS NOT NULL")) {
                while (r.next()) refs.add(new Reference("ootd-samples/" + r.getString(1), null, "ootd_sample"));
            }
            try (var r = s.executeQuery("SELECT segmentation_id FROM standalone_segmentation_owner")) {
                while (r.next()) refs.add(new Reference(r.getString(1) + "/result.json", null, "standalone_segmentation_owner"));
            }
        }
        return refs;
    }

    /** Read-only: the migration never changes the database. */
    private Connection database() throws SQLException {
        String url = dbUrl.startsWith("jdbc:h2:file:") && !dbUrl.toUpperCase().contains("ACCESS_MODE_DATA") ? dbUrl + ";ACCESS_MODE_DATA=r" : dbUrl;
        var connection = DriverManager.getConnection(url, dbUser, dbPassword);
        connection.setReadOnly(true);
        return connection;
    }

    private static String firstLine(Throwable e) {
        String m = Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName());
        int nl = m.indexOf('\n'); return nl < 0 ? m : m.substring(0, nl);
    }
    static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / 1048576.0);
        return String.format("%.2f GB", bytes / 1073741824.0);
    }
}
