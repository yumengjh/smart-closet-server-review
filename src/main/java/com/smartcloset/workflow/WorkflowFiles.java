package com.smartcloset.workflow;

import com.smartcloset.ClosetProperties;
import com.smartcloset.image.Images;
import com.smartcloset.image.ProcessingException;
import com.smartcloset.workflow.persistence.AssetRow;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Immutable files per task; receipts bridge a completed file write and its database commit. */
@Component
public class WorkflowFiles {
    public record Receipt(Workflow.StepResult result, AssetRow asset, String providerRequestId) {}
    private final Path root;
    private final ClosetProperties properties;
    private final ObjectMapper json;

    public WorkflowFiles(ClosetProperties properties, ObjectMapper json) throws IOException {
        this.properties = properties; this.json = json;
        root = properties.storageDir().toAbsolutePath().normalize().resolve("workflows");
        Files.createDirectories(root);
    }
    public Path root() { return root; }
    public Path directory(UUID id) { return root.resolve(id.toString()); }
    public Path path(UUID id, String name) {
        if (!name.matches("input\\.png|step-[0-5]\\.png")) throw ProcessingException.invalid("无效的工作流图片名称。");
        return directory(id).resolve(name);
    }
    public String url(UUID id, String name) { return "/api/workflows/" + id + "/files/" + name; }

    public AssetRow write(UUID id, String name, String kind, byte[] bytes) throws IOException {
        var asset = describe(id, name, kind, bytes);
        Files.createDirectories(directory(id));
        atomic(path(id, name), bytes);
        return asset;
    }
    public AssetRow describe(UUID id, String name, String kind, byte[] bytes) {
        path(id, name);
        var image = Images.decodePreservingAlpha(bytes, properties.maxPixels()).image();
        var row = new AssetRow(); row.id = UUID.randomUUID().toString(); row.jobId = id.toString(); row.fileName = name;
        row.sha256 = hash(bytes); row.byteSize = (long) bytes.length; row.width = image.getWidth(); row.height = image.getHeight();
        row.kind = kind; row.reviewStatus = "PENDING"; row.createdAtMs = System.currentTimeMillis(); return row;
    }
    public byte[] readVerified(AssetRow asset) throws IOException {
        var bytes = Files.readAllBytes(path(UUID.fromString(asset.jobId), asset.fileName));
        if (bytes.length != asset.byteSize || !hash(bytes).equals(asset.sha256))
            throw new IOException("Asset checksum mismatch: " + asset.id);
        return bytes;
    }
    public void receipt(UUID id, Receipt receipt) throws IOException {
        atomic(directory(id).resolve("step-" + receipt.result().index() + ".receipt.json"), json.writeValueAsBytes(receipt));
    }
    public Receipt receipt(UUID id, int index) throws IOException {
        var path = directory(id).resolve("step-" + index + ".receipt.json");
        if (!Files.isRegularFile(path)) return null;
        var receipt = json.readValue(path.toFile(), Receipt.class);
        if (receipt.result().type() == Workflow.Type.RECOGNIZE) {
            if (receipt.result().index() != index || receipt.asset() != null || receipt.result().recognition() == null
                    || !url(id, "input.png").equals(receipt.result().inputUrl()) || receipt.result().outputUrl() != null)
                throw new IOException("Invalid recognition receipt");
            return receipt;
        }
        if (receipt.result().index() != index || !id.toString().equals(receipt.asset().jobId)
                || !("step-" + index + ".png").equals(receipt.asset().fileName)
                || !url(id, receipt.asset().fileName).equals(receipt.result().outputUrl()))
            throw new IOException("Invalid workflow receipt");
        readVerified(receipt.asset()); return receipt;
    }
    private void atomic(Path target, byte[] bytes) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(), ".pending-", ".tmp");
        try {
            try (var channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
    }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
