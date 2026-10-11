package com.smartcloset.storage;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StorageMigrationTest {
    @TempDir Path data;
    @TempDir Path restored;
    FakeS3 s3;
    final ByteArrayOutputStream log = new ByteArrayOutputStream();
    static final String JOB = "11111111-2222-3333-4444-555555555555", SEG = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", MEDIA = "99999999-8888-7777-6666-555555555555";

    @BeforeEach void setUp() throws Exception {
        s3 = new FakeS3();
        write("workflows/" + JOB + "/original.jpg", "jpeg");
        write("workflows/" + JOB + "/step-1.png", "step one");
        write("workflows/" + JOB + "/step-1.receipt.json", "{}");
        write("workflows/" + JOB + "/.pending-123.tmp", "half");
        write("workflows/.worker.lock", "");
        write("batches/" + JOB + "/atlas-" + JOB + ".png", "atlas");
        write(SEG + "/result.json", "{}");
        write(SEG + "/cutout.png", "cutout");
        write(SEG + "/sticker-v3/image.png", "sticker");
        write(".processing-77/cutout.png", "staging");
        write("private-person/" + MEDIA + ".png", "person");
        write("private-person/deletion-ledger/" + MEDIA, "deleted");
        write("ootd-samples/s1.png", "sample");
        write("backups/before-v13-1/database.zip", "zip");
        try (var db = DriverManager.getConnection("jdbc:h2:file:" + data.resolve("db/closet"), "sa", ""); var s = db.createStatement()) {
            s.execute("CREATE TABLE image_asset(job_id VARCHAR(40), file_name VARCHAR(40), byte_size BIGINT)");
            s.execute("INSERT INTO image_asset VALUES('" + JOB + "','step-1.png',8),('" + JOB + "','original.jpg',4),('" + JOB + "','step-5.png',3)");
            s.execute("CREATE TABLE private_media(id VARCHAR(40), state VARCHAR(20))");
            s.execute("INSERT INTO private_media VALUES('" + MEDIA + "','ACTIVE'),('00000000-0000-0000-0000-000000000000','DELETED')");
            s.execute("CREATE TABLE ootd_sample(image_path VARCHAR(80))");
            s.execute("INSERT INTO ootd_sample VALUES('s1.png'),(NULL)");
            s.execute("CREATE TABLE standalone_segmentation_owner(segmentation_id VARCHAR(40))");
            s.execute("INSERT INTO standalone_segmentation_owner VALUES('" + SEG + "')");
        }
    }
    @AfterEach void tearDown() { s3.close(); }

    void write(String key, String text) throws Exception {
        Path p = data.resolve(key); Files.createDirectories(p.getParent()); Files.writeString(p, text);
    }
    int run(String step, Path dir) {
        var env = new HashMap<String, String>(Map.of("CLOSET_STORAGE_DIR", dir.toString(), "CLOSET_STORAGE_S3_ENDPOINT", s3.endpoint(),
                "CLOSET_STORAGE_S3_REGION", FakeS3.REGION, "CLOSET_STORAGE_S3_BUCKET", FakeS3.BUCKET, "CLOSET_STORAGE_S3_ACCESS_KEY", FakeS3.ACCESS,
                "CLOSET_STORAGE_S3_SECRET_KEY", FakeS3.SECRET, "CLOSET_STORAGE_S3_PREFIX", "closet", "CLOSET_STORAGE_S3_PATH_STYLE", "true"));
        log.reset();
        int code = StorageMigration.run(new String[]{"--closet-command=storage-migrate", "--step=" + step, "--threads=3"}, env, new PrintStream(log, true, StandardCharsets.UTF_8));
        return code;
    }
    String output() { return log.toString(StandardCharsets.UTF_8); }

    @Test void copiesOnlyImageFilesUnderTheSameKeysAndCanRepeat() {
        assertEquals(0, run("scan", data), output());
        assertTrue(output().contains("合计 9 个文件"), output());
        assertTrue(output().contains("本机缺失 1 个"), "step-5.png is referenced but was never on disk: " + output());
        assertEquals(1, run("verify", data), "nothing copied yet: " + output());

        assertEquals(0, run("copy", data), output());
        assertEquals(new TreeSet<>(List.of("closet/workflows/" + JOB + "/original.jpg", "closet/workflows/" + JOB + "/step-1.png",
                "closet/workflows/" + JOB + "/step-1.receipt.json", "closet/batches/" + JOB + "/atlas-" + JOB + ".png",
                "closet/" + SEG + "/result.json", "closet/" + SEG + "/cutout.png", "closet/" + SEG + "/sticker-v3/image.png",
                "closet/private-person/" + MEDIA + ".png", "closet/ootd-samples/s1.png")), new TreeSet<>(s3.objects.keySet()));
        assertEquals("image/jpeg", s3.objects.get("closet/workflows/" + JOB + "/original.jpg").contentType());

        int before = s3.requests.get();
        assertEquals(0, run("copy", data), output());
        assertTrue(output().contains("上传 0 个") && output().contains("跳过 9 个"), output());
        assertTrue(s3.requests.get() - before <= 9 + 1, "a repeat only asks HEAD per file");

        assertEquals(0, run("verify", data), output());
        assertTrue(output().contains("本机原本就缺失 1 个"), output());
    }

    @Test void verifyCatchesAMissingOrChangedObjectAndPullRestoresTheDirectory() throws Exception {
        assertEquals(0, run("copy", data), output());
        s3.objects.remove("closet/private-person/" + MEDIA + ".png");
        assertEquals(1, run("verify", data), output());
        assertTrue(output().contains("缺失：private-person/" + MEDIA + ".png"), output());
        write("workflows/" + JOB + "/step-1.png", "STEP ONE");
        assertEquals(0, run("copy", data), output());
        assertTrue(output().contains("上传 2 个"), output());
        assertEquals(0, run("verify", data), output());

        Files.createDirectories(restored);
        assertEquals(0, run("pull", restored), output());
        assertEquals("STEP ONE", Files.readString(restored.resolve("workflows/" + JOB + "/step-1.png")));
        assertEquals("person", Files.readString(restored.resolve("private-person/" + MEDIA + ".png")));
        assertEquals(0, run("pull", restored), output());
        assertTrue(output().contains("下载 0 个"), output());
    }

    @Test void explainsUsageAndConfigurationProblems() {
        log.reset();
        assertEquals(2, StorageMigration.run(new String[]{"--closet-command=storage-migrate"}, Map.of(), new PrintStream(log, true, StandardCharsets.UTF_8)));
        assertEquals(2, StorageMigration.run(new String[]{"--closet-command=storage-migrate", "--step=copy"}, Map.of("CLOSET_STORAGE_DIR", data.toString()), new PrintStream(log, true, StandardCharsets.UTF_8)));
        assertTrue(output().contains("CLOSET_STORAGE_S3_BUCKET"), output());
    }

    @Test void onlyImageKindsMove() {
        assertNotNull(StorageMigration.kind("workflows/" + JOB + "/quick-quality.json"));
        assertNull(StorageMigration.kind("workflows/.worker.lock"));
        assertNull(StorageMigration.kind("workflows/" + JOB + "/.pending-1.tmp"));
        assertNull(StorageMigration.kind("db/closet.mv.db"));
        assertNull(StorageMigration.kind("private-person/deletion-ledger/" + MEDIA));
        assertNull(StorageMigration.kind("backups/x/assets/workflows/" + JOB + "/step-1.png"));
    }
}
