import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Portable package only (portable/ci/assemble.mjs): drops the ONNX Runtime libraries of other platforms and the macOS
 * debug symbols (.dSYM, about 75 MB) from the nested onnxruntime jar of a Spring Boot fat jar. Nested jars stay STORED,
 * as Spring Boot's loader requires. Run with a JDK: java SlimJar.java in.jar out.jar osx-aarch64
 */
public class SlimJar {
    public static void main(String[] args) throws IOException {
        Path in = Path.of(args[0]), out = Path.of(args[1]);
        String keep = "ai/onnxruntime/native/" + args[2] + "/";
        long removed = 0;
        try (var source = new ZipFile(in.toFile()); var target = new ZipOutputStream(Files.newOutputStream(out))) {
            for (var entries = source.entries(); entries.hasMoreElements(); ) {
                ZipEntry entry = entries.nextElement();
                byte[] data;
                try (InputStream input = source.getInputStream(entry)) { data = input.readAllBytes(); }
                if (entry.getName().matches("BOOT-INF/lib/onnxruntime-[^/]+\\.jar")) {
                    byte[] slim = slim(data, keep);
                    removed += data.length - slim.length;
                    data = slim;
                }
                var copy = new ZipEntry(entry.getName());
                copy.setTime(entry.getTime());
                if (entry.getMethod() == ZipEntry.STORED) {
                    var crc = new CRC32(); crc.update(data);
                    copy.setMethod(ZipEntry.STORED); copy.setSize(data.length); copy.setCompressedSize(data.length); copy.setCrc(crc.getValue());
                }
                target.putNextEntry(copy);
                target.write(data);
                target.closeEntry();
            }
        }
        System.out.printf("SlimJar: kept %s, removed %.1f MB%n", keep, removed / 1048576.0);
        if (removed == 0) throw new IllegalStateException("onnxruntime jar not found or nothing removed");
    }

    private static byte[] slim(byte[] jar, String keep) throws IOException {
        var buffer = new ByteArrayOutputStream();
        boolean kept = false;
        try (var input = new ZipInputStream(new java.io.ByteArrayInputStream(jar)); var output = new ZipOutputStream(buffer)) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null; ) {
                String name = entry.getName();
                boolean otherPlatform = name.startsWith("ai/onnxruntime/native/") && name.length() > "ai/onnxruntime/native/".length() && !name.startsWith(keep);
                if (otherPlatform || name.contains(".dSYM/")) continue;
                if (name.startsWith(keep) && !entry.isDirectory()) kept = true;
                var copy = new ZipEntry(name); copy.setTime(entry.getTime());
                output.putNextEntry(copy); input.transferTo(output); output.closeEntry();
            }
        }
        if (!kept) throw new IllegalStateException("onnxruntime jar has no library for " + keep);
        return buffer.toByteArray();
    }
}
