package com.smartcloset.workflow;

import com.smartcloset.ClosetProperties;
import com.smartcloset.image.Images;
import com.smartcloset.image.ProcessingException;
import com.smartcloset.storage.BlobStore;
import com.smartcloset.storage.LocalBlobStore;
import com.smartcloset.workflow.persistence.AssetRow;
import java.io.IOException;
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
    private final BlobStore store;

    public WorkflowFiles(ClosetProperties properties, ObjectMapper json) throws IOException {
        this(properties, json, new LocalBlobStore(properties.storageDir()));
    }
    @org.springframework.beans.factory.annotation.Autowired
    public WorkflowFiles(ClosetProperties properties, ObjectMapper json, BlobStore store) throws IOException {
        this.properties = properties; this.json = json; this.store = store;
        // Always local: the worker lock and legacy job.json files live here whatever stores the images.
        root = properties.storageDir().toAbsolutePath().normalize().resolve("workflows");
        Files.createDirectories(root);
    }
    public Path root() { return root; }
    public BlobStore store() { return store; }
    /** Local directory of a task; only legacy imports and tests on the local backend look here. */
    public Path directory(UUID id) { return root.resolve(id.toString()); }
    /** Storage key of a task image. */
    public String key(UUID id, String name) {
        if (!name.matches("input\\.png|base-cutout\\.png|step-[0-5]\\.png|original\\.(jpg|png)")) throw ProcessingException.invalid("无效的工作流图片名称。");
        return "workflows/" + id + "/" + name;
    }
    private static String dataKey(UUID id, String name) { return "workflows/" + id + "/" + name; }
    public boolean exists(UUID id, String name) throws IOException { return store.exists(key(id, name)); }
    /** Unverified bytes of a task image, null when missing. */
    public byte[] read(UUID id, String name) throws IOException { return store.get(key(id, name)); }
    public String url(UUID id, String name) { return "/api/workflows/" + id + "/files/" + name; }

    public void keepOriginal(UUID id, byte[] bytes) throws IOException {
        var decoded=Images.sampled(bytes,64);
        String name="original."+(decoded.format().equals("jpeg")?"jpg":"png");
        store.put(key(id,name),bytes,BlobStore.contentType(name));
    }
    /** Header-only asset validation keeps upload acknowledgement independent of inference. */
    public AssetRow originalAsset(UUID id,byte[] bytes)throws IOException {
        if(bytes.length==0||bytes.length>50L*1024*1024)throw ProcessingException.invalid("照片不能为空且不超过50 MB。");
        try(var stream=javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))){var readers=javax.imageio.ImageIO.getImageReaders(stream);if(!readers.hasNext())throw ProcessingException.invalid("请选择JPEG或PNG。");var reader=readers.next();try{reader.setInput(stream,true,true);String format=reader.getFormatName().toLowerCase();int w=reader.getWidth(0),h=reader.getHeight(0);if(!Set.of("jpeg","png").contains(format)||w<32||h<32||w>16384||h>16384||(long)w*h>64000000)throw ProcessingException.invalid("照片格式或尺寸无效。");String name="original."+(format.equals("jpeg")?"jpg":"png");store.put(key(id,name),bytes,BlobStore.contentType(name));var row=new AssetRow();row.id=UUID.randomUUID().toString();row.jobId=id.toString();row.fileName=name;row.sha256=hash(bytes);row.byteSize=(long)bytes.length;row.width=w;row.height=h;row.kind="INPUT";row.reviewStatus="PENDING";row.createdAtMs=System.currentTimeMillis();return row;}finally{reader.dispose();}}
    }
    public byte[] dataRead(UUID id,String name)throws IOException {if(!name.matches("quick-[a-z-]+\\.json"))throw ProcessingException.invalid("私有结果文件无效。");return store.get(dataKey(id,name));}
    public boolean dataExists(UUID id,String name)throws IOException {if(!name.matches("quick-[a-z-]+\\.json"))throw ProcessingException.invalid("私有结果文件无效。");return store.exists(dataKey(id,name));}
    public void data(UUID id,String name,byte[] bytes)throws IOException {if(!name.matches("quick-[a-z-]+\\.json"))throw ProcessingException.invalid("无效数据名。");store.put(dataKey(id,name),bytes,"application/json");}
    public AssetRow write(UUID id, String name, String kind, byte[] bytes) throws IOException {
        var asset = describe(id, name, kind, bytes);
        store.put(key(id, name), bytes, BlobStore.contentType(name));
        return asset;
    }
    public AssetRow describe(UUID id, String name, String kind, byte[] bytes) {
        key(id, name);
        var image = Images.decodePreservingAlpha(bytes, properties.maxPixels()).image();
        var row = new AssetRow(); row.id = UUID.randomUUID().toString(); row.jobId = id.toString(); row.fileName = name;
        row.sha256 = hash(bytes); row.byteSize = (long) bytes.length; row.width = image.getWidth(); row.height = image.getHeight();
        row.kind = kind; row.reviewStatus = "PENDING"; row.createdAtMs = System.currentTimeMillis(); return row;
    }
    public byte[] readVerified(AssetRow asset) throws IOException {
        var bytes = store.get(key(UUID.fromString(asset.jobId), asset.fileName));
        if (bytes == null) throw new java.nio.file.NoSuchFileException("Asset missing: " + asset.id);
        if (bytes.length != asset.byteSize || !hash(bytes).equals(asset.sha256))
            throw new IOException("Asset checksum mismatch: " + asset.id);
        return bytes;
    }
    public void receipt(UUID id, Receipt receipt) throws IOException {
        store.put(dataKey(id, "step-" + receipt.result().index() + ".receipt.json"), json.writeValueAsBytes(receipt), "application/json");
    }
    public Receipt receipt(UUID id, int index) throws IOException {
        byte[] saved = store.get(dataKey(id, "step-" + index + ".receipt.json"));
        if (saved == null) return null;
        var receipt = json.readValue(saved, Receipt.class);
        if (receipt.result().type() == Workflow.Type.RECOGNIZE) {
            if (receipt.result().index() != index || receipt.asset() != null || receipt.result().recognition() == null
                    || !Set.of(url(id,"input.png"),url(id,"original.jpg"),url(id,"original.png")).contains(receipt.result().inputUrl()) || receipt.result().outputUrl() != null)
                throw new IOException("Invalid recognition receipt");
            return receipt;
        }
        if (receipt.result().index() != index || !id.toString().equals(receipt.asset().jobId)
                || !("step-" + index + ".png").equals(receipt.asset().fileName)
                || !url(id, receipt.asset().fileName).equals(receipt.result().outputUrl()))
            throw new IOException("Invalid workflow receipt");
        readVerified(receipt.asset()); return receipt;
    }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
