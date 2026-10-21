package com.smartcloset.security;

import com.smartcloset.image.ProcessingException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ClosetOwnership {
    private final JdbcTemplate jdbc;
    public ClosetOwnership(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static ProcessingException hidden() { return new ProcessingException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "没有找到该内容。"); }
    public void workflow(UUID id) {
        if (PresetOwner.reserved(ClosetIdentity.username()) || id == null || jdbc.queryForObject("SELECT COUNT(*) FROM workflow_job WHERE id=? AND owner_username=?", Integer.class, id.toString(), ClosetIdentity.username()) != 1) throw hidden();
    }
    /** File GETs only. Mutation checks deliberately keep using workflow()/asset(). */
    public void workflowRead(UUID id) {
        String owner = ClosetIdentity.username();
        if (owner == null || id == null || jdbc.queryForObject("SELECT COUNT(*) FROM workflow_job WHERE id=? AND owner_username IN (?,?)", Integer.class,
                id.toString(), owner, PresetOwner.USERNAME) != 1) throw hidden();
    }
    public void assetRead(UUID id) {
        String owner = ClosetIdentity.username();
        if (owner == null || id == null || jdbc.queryForObject("SELECT COUNT(*) FROM image_asset a JOIN workflow_job j ON j.id=a.job_id WHERE a.id=? AND j.owner_username IN (?,?)", Integer.class,
                id.toString(), owner, PresetOwner.USERNAME) != 1) throw hidden();
    }
    public void workflowIfPresent(UUID id) { if (id != null && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM workflow_job WHERE id=?)", Boolean.class, id.toString()))) workflow(id); }
    public void garment(UUID id) {
        if (id == null || jdbc.queryForObject("SELECT COUNT(*) FROM garment WHERE id=? AND owner_username=?", Integer.class, id.toString(), ClosetIdentity.username()) != 1) throw hidden();
    }
    public void asset(UUID id) {
        if (PresetOwner.reserved(ClosetIdentity.username()) || id == null || jdbc.queryForObject("SELECT COUNT(*) FROM image_asset a JOIN workflow_job j ON j.id=a.job_id WHERE a.id=? AND j.owner_username=?", Integer.class, id.toString(), ClosetIdentity.username()) != 1) throw hidden();
    }
    public void segmentation(UUID id) {
        if (id == null || jdbc.queryForObject("SELECT COUNT(*) FROM standalone_segmentation_owner WHERE segmentation_id=? AND owner_username=?", Integer.class, id.toString(), ClosetIdentity.username()) != 1) throw hidden();
    }
    public void registerSegmentation(UUID id) {
        jdbc.update("MERGE INTO standalone_segmentation_owner(segmentation_id,owner_username,created_at_ms) KEY(segmentation_id) VALUES(?,?,?)",
                id.toString(), ClosetIdentity.username(), System.currentTimeMillis());
    }
    public boolean administrator(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM closet_user WHERE username=? AND role='ADMIN')", Boolean.class, id.toString()));
    }
}
