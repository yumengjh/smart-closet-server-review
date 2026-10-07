package com.smartcloset.person;

import java.util.*;
import java.nio.file.*;
import java.io.*;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import com.smartcloset.image.*;
import com.smartcloset.ClosetProperties;
import com.smartcloset.workflow.WorkflowFiles;

/** Owner-only media. No public catalog, recognition step, disk input replicas or portrait caches. */
@Service
public class PrivateMedia {
    public record Media(UUID id,String purpose,String state,long deletionEpoch,String hash,String url,long createdAt) {}
    private final JdbcTemplate db;private final Path root;private final org.springframework.transaction.support.TransactionTemplate tx;private final java.util.concurrent.atomic.AtomicLong nextOrphanScan=new java.util.concurrent.atomic.AtomicLong();
    public PrivateMedia(JdbcTemplate db,ClosetProperties config,org.springframework.transaction.PlatformTransactionManager manager)throws IOException{this.db=db;tx=new org.springframework.transaction.support.TransactionTemplate(manager);root=config.storageDir().toAbsolutePath().resolve("private-person");Files.createDirectories(root);Files.createDirectories(root.resolve("deletion-ledger"));}
    public Media get(String owner,UUID id){return db.query("SELECT * FROM private_media WHERE id=? AND owner_username=?",(r,n)->new Media(id,r.getString("purpose"),r.getString("state"),r.getLong("deletion_epoch"),r.getString("sha256"),"/api/me/private-media/"+id,r.getLong("created_at_ms")),id.toString(),owner).stream().findFirst().orElseThrow(()->new ProcessingException(HttpStatus.NOT_FOUND,"MEDIA_NOT_FOUND","私人图片不存在。"));}
    public List<Media> list(String owner,String purpose){return db.queryForList("SELECT id FROM private_media WHERE owner_username=? AND purpose=? AND state='ACTIVE' ORDER BY created_at_ms DESC",String.class,owner,purpose).stream().map(id->get(owner,UUID.fromString(id))).toList();}
    public Media put(String owner,String purpose,byte[] input)throws IOException {return putOutput(owner,purpose,input,null,null);}
    public Media putOutput(String owner,String purpose,byte[] input,String jobId,Long epoch)throws IOException {
        if(!Set.of("PHOTO","BASELINE","OOTD").contains(purpose))throw ProcessingException.invalid("图片用途无效。");
        // Header/pixel limits, EXIF orientation and re-encoding remove all embedded GPS/metadata.
        var image=Images.sampled(input,2048).image();var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);byte[] png=bytes.toByteArray();UUID id=UUID.randomUUID();
        Path temporary=Files.createTempFile(root,".pending-",".tmp");try{Files.write(temporary,png);Files.move(temporary,root.resolve(id+".png"),StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temporary);}
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()){
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                @Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)try{Files.deleteIfExists(root.resolve(id+".png"));}catch(IOException ignored){}}
            });
        }
        try{db.update("INSERT INTO private_media(id,owner_username,purpose,sha256,state,deletion_epoch,created_at_ms,source_job_id,source_job_epoch) VALUES(?,?,?,?,'ACTIVE',0,?,?,?)",id.toString(),owner,purpose,WorkflowFiles.hash(png),System.currentTimeMillis(),jobId,epoch);}catch(RuntimeException e){Files.deleteIfExists(root.resolve(id+".png"));throw e;}return get(owner,id);
    }
    public byte[] read(String owner,UUID id)throws IOException{var m=get(owner,id);if(!m.state().equals("ACTIVE"))throw new ProcessingException(HttpStatus.NOT_FOUND,"MEDIA_DELETED","图片已删除或正在清理。");byte[] bytes=Files.readAllBytes(root.resolve(id+".png"));if(!WorkflowFiles.hash(bytes).equals(m.hash()))throw new IOException("Private media checksum mismatch");return bytes;}
    public Media delete(String owner,UUID id){
        tx.executeWithoutResult(t -> {
            db.queryForObject("SELECT username FROM closet_user WHERE username=? FOR UPDATE",String.class,owner);get(owner,id);
            db.update("UPDATE private_media SET state='PURGING',deletion_epoch=deletion_epoch+1 WHERE id=? AND owner_username=? AND state='ACTIVE'",id.toString(),owner);
            db.update("UPDATE image_render_job SET status='CANCEL_REQUESTED',deletion_epoch=deletion_epoch+1,updated_at_ms=? WHERE owner_username=? AND status IN ('QUEUED','RUNNING','WAITING_RESOURCE','UNKNOWN') AND id IN (SELECT job_id FROM render_media_dependency WHERE media_id=?)",System.currentTimeMillis(),owner,id.toString());
        });
        try{Files.writeString(root.resolve("deletion-ledger").resolve(id.toString()),"deleted\n",StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);}catch(IOException e){throw new ProcessingException(HttpStatus.SERVICE_UNAVAILABLE,"MEDIA_DELETE_PENDING","访问已撤销，删除清单写入失败，正在等待重试。");}
        purge(id);return get(owner,id);
    }
    private void purge(UUID id){try{Files.deleteIfExists(root.resolve(id+".png"));db.update("UPDATE private_media SET state='DELETED' WHERE id=? AND state='PURGING'",id.toString());}catch(IOException ignored){/* Tombstone denies access; scheduled domain scan retries cleanup. */}}
    /** Crash leftovers have no database owner or dependency. Allow active writes one hour to commit. */
    void purgeOrphans(long now){
        try(var entries=Files.list(root)){for(var file:entries.toList()){
            String name=file.getFileName().toString();boolean pending=name.startsWith(".pending-")&&name.endsWith(".tmp");boolean output=name.matches("[a-f0-9-]{36}\\.png");
            if(!pending&&!output||!Files.isRegularFile(file)||Files.getLastModifiedTime(file).toMillis()>now-3600000)continue;
            if(output&&db.queryForObject("SELECT COUNT(*) FROM private_media WHERE id=?",Integer.class,name.substring(0,36))!=0)continue;
            try{Files.deleteIfExists(file);}catch(IOException ignored){}
        }}catch(IOException ignored){}
    }
    public void purgePending(){
        long now=System.currentTimeMillis(),due=nextOrphanScan.get();if(now>=due&&nextOrphanScan.compareAndSet(due,now+3600000))purgeOrphans(now);
        // Persistent tombstones survive restoring an older database; never restore a deleted image.
        try(var entries=Files.list(root.resolve("deletion-ledger"))){for(var entry:entries.toList()){String id=entry.getFileName().toString();if(id.matches("[a-f0-9-]{36}"))db.update("UPDATE private_media SET state='PURGING',deletion_epoch=deletion_epoch+1 WHERE id=? AND state='ACTIVE'",id);}}catch(IOException ignored){}
        db.query("SELECT id FROM private_media WHERE state='PURGING'",(r,n)->UUID.fromString(r.getString(1))).forEach(id->{try{Files.writeString(root.resolve("deletion-ledger").resolve(id.toString()),"deleted\n",StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);purge(id);}catch(IOException ignored){}});}
    public boolean available(String owner,UUID id,long epoch){try{var m=get(owner,id);return m.state().equals("ACTIVE")&&m.deletionEpoch()==epoch;}catch(ProcessingException e){return false;}}
}
