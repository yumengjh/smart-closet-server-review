package com.smartcloset.garment;

import com.smartcloset.image.ProcessingException;
import com.smartcloset.recognition.*;
import com.smartcloset.workflow.Workflow.*;
import com.smartcloset.workflow.persistence.AssetRow;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;

@Repository
public class GarmentRepository {
    public record View(UUID id, long revision, UUID sourceAssetId, String sourceUrl, String sourceOrigin,
                       UUID displayAssetId, String displayUrl, Recognition.Result automatic,
                       Map<String, List<String>> overrides, Map<String, List<String>> effective,
                       UUID latestRecognitionJobId, UUID appliedRecognitionJobId, List<Run> runs,
                       String status, String restoreStatus, long createdAtMs, long updatedAtMs, UUID latestImageJobId,
                       List<ImageOption> images) {}
    public record ImageOption(UUID id, String url, String kind, String reviewStatus) {}
    public record ClosetPage(List<View> items, long total, int page, int size) {}
    public record Run(UUID id, String status, long createdAtMs, String outputUrl) {}
    public record Edit(Long revision, Map<String, List<String>> fields, List<String> resetFields) {}
    public record SelectImage(Long revision, UUID assetId) {}
    public record Confirm(Long revision, UUID assetId, Map<String, List<String>> fields, List<String> resetFields) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    public GarmentRepository(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager, Flyway workflowMigrations) {
        this.jdbc = jdbc; this.json = json; tx = new TransactionTemplate(manager);
    }
    private record Row(String id, long revision, String source, String display, String origin, String automatic, String overrides,
                       String latest, String applied, String latestImage, String status, String restoreStatus, long created, long updated) {}
    private Row row(UUID id, boolean lock) {
        var rows = jdbc.query("SELECT * FROM garment WHERE id=?" + (lock ? " FOR UPDATE" : ""), (r, n) -> new Row(r.getString("id"), r.getLong("revision"),
            r.getString("source_asset_id"), r.getString("display_asset_id"), r.getString("source_origin"), r.getString("automatic_json"), r.getString("overrides_json"),
            r.getString("latest_recognition_job_id"), r.getString("applied_recognition_job_id"), r.getString("latest_image_job_id"),
            r.getString("closet_status"), r.getString("restore_status"), r.getLong("created_at_ms"), r.getLong("updated_at_ms")), id.toString());
        if (rows.isEmpty()) throw new ProcessingException(HttpStatus.NOT_FOUND, "GARMENT_NOT_FOUND", "没有找到该衣物。");
        return rows.getFirst();
    }
    public View get(UUID id) {
        return tx.execute(t -> {
            var r = row(id, true); var overrides = overrides(r.overrides());
            var automatic = r.automatic() == null ? null : json.readValue(r.automatic(), Recognition.Result.class);
            var effective = new LinkedHashMap<String, List<String>>();
            for (var f : RecognitionSchema.FIELDS) effective.put(f.key(), overrides.containsKey(f.key()) ? overrides.get(f.key())
                : automatic == null ? List.of() : automatic.attributes().get(f.key()).values());
            var runs = jdbc.query("SELECT id,status,created_at_ms,output_url FROM workflow_job WHERE garment_id=? ORDER BY created_at_ms DESC,id DESC",
                (rs, n) -> new Run(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3), rs.getString(4)), id.toString());
            var images = jdbc.query("SELECT a.id,j.id AS job_id,a.file_name,a.kind,a.review_status FROM image_asset a JOIN workflow_job j ON a.job_id=j.id WHERE j.garment_id=? ORDER BY j.created_at_ms,a.created_at_ms,a.file_name",
                    (rs,n) -> new ImageOption(UUID.fromString(rs.getString(1)), "/api/workflows/" + rs.getString(2) + "/files/" + rs.getString(3), rs.getString(4), rs.getString(5)), id.toString());
            return new View(id, r.revision(), UUID.fromString(r.source()), assetUrl(r.source()), r.origin(), UUID.fromString(r.display()), assetUrl(r.display()),
                automatic, overrides, effective, uuid(r.latest()), uuid(r.applied()), runs,
                r.status(), r.restoreStatus(), r.created(), r.updated(), uuid(r.latestImage()), images);
        });
    }
    public ClosetPage listCloset(String owner, String status, String query, String category, String color, String season, int page, int size) {
        if (page < 1 || size < 1 || size > 50) throw ProcessingException.invalid("page 从 1 开始，size 为 1 到 50。");
        var ids = jdbc.query("SELECT id FROM garment WHERE owner_username=? AND closet_status=? ORDER BY updated_at_ms DESC,id DESC",
                (r,n) -> UUID.fromString(r.getString(1)), owner, status);
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        var matches = new ArrayList<View>();
        for (UUID id : ids) {
            View view = get(id);
            if (category != null && !category.isBlank() && !view.effective().getOrDefault("category", List.of()).contains(category)) continue;
            if (color != null && !color.isBlank() && !view.effective().getOrDefault("primaryColor", List.of()).contains(color)) continue;
            if (season != null && !season.isBlank() && !view.effective().getOrDefault("seasons", List.of()).contains(season)) continue;
            if (!q.isBlank() && view.effective().values().stream().flatMap(Collection::stream).noneMatch(value -> value.toLowerCase(Locale.ROOT).contains(q))) continue;
            matches.add(view);
        }
        int from = Math.min(matches.size(), (page - 1) * size), to = Math.min(matches.size(), from + size);
        return new ClosetPage(matches.subList(from, to), matches.size(), page, size);
    }
    public List<UUID> list(int page, int size) {
        if (page < 1 || size < 1 || size > 50) throw ProcessingException.invalid("page 从 1 开始，size 为 1 到 50。");
        return jdbc.query("SELECT id FROM garment ORDER BY created_at_ms DESC,id DESC LIMIT ? OFFSET ?", (r, n) -> UUID.fromString(r.getString(1)), size, (long)(page - 1) * size);
    }
    public String assetUrl(String id) {
        return jdbc.queryForObject("SELECT job_id,file_name FROM image_asset WHERE id=?", (r,n) -> "/api/workflows/" + r.getString(1) + "/files/" + r.getString(2), id);
    }
    /** Called inside the workflow insert transaction, after its input asset has been saved. */
    public void submitted(Job job, List<AssetRow> assets) {
        UUID id = job.request().garmentId(); if (id == null) return;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM garment WHERE id=?", Integer.class, id.toString());
        if (count == 0) {
            var source = assets.stream().filter(a -> "INPUT".equals(a.kind)).findFirst().orElseThrow();
            jdbc.update("INSERT INTO garment(id,source_asset_id,display_asset_id,source_origin,overrides_json,display_user_selected,revision,created_at_ms,updated_at_ms,owner_username,closet_status) VALUES(?,?,?,?,?,0,0,?,?,?,'DRAFT')",
                id.toString(), source.id, source.id, job.request().sourceOrigin().name(), "{}", job.createdAt().toEpochMilli(), job.createdAt().toEpochMilli(), com.smartcloset.security.ClosetIdentity.username());
        }
        row(id, true);
        if (job.request().steps().stream().anyMatch(s -> s.type() == Type.RECOGNIZE))
            jdbc.update("UPDATE garment SET latest_recognition_job_id=?,revision=revision+1,updated_at_ms=? WHERE id=?", job.id().toString(), System.currentTimeMillis(), id.toString());
        if (job.request().steps().stream().anyMatch(s -> s.type() != Type.RECOGNIZE))
            jdbc.update("UPDATE garment SET latest_image_job_id=?,revision=revision+1,updated_at_ms=? WHERE id=?", job.id().toString(), System.currentTimeMillis(), id.toString());
        jdbc.update("UPDATE workflow_job SET garment_id=?,owner_username=? WHERE id=?", id.toString(), com.smartcloset.security.ClosetIdentity.username(), job.id().toString());
    }
    /** A stale run cannot replace a newer request. User fields always live in a separate map. */
    public void progress(Job job, AssetRow asset) {
        UUID id = job.request().garmentId(); if (id == null) return;
        var r = row(id, true);
        for (var s : job.steps()) if (s.recognition() != null && job.id().toString().equals(r.latest()) && !job.id().toString().equals(r.applied()) && !s.recognition().blocksImages()) {
            jdbc.update("UPDATE garment SET automatic_json=?,applied_recognition_job_id=?,revision=revision+1,updated_at_ms=? WHERE id=?",
                json.writeValueAsString(s.recognition()), job.id().toString(), System.currentTimeMillis(), id.toString());
        }
        if (asset != null && !"INPUT".equals(asset.kind))
            jdbc.update("UPDATE garment SET display_asset_id=?,revision=revision+1,updated_at_ms=? WHERE id=? AND latest_image_job_id=? AND display_user_selected=0",
                asset.id, System.currentTimeMillis(), id.toString(), job.id().toString());
    }
    public View edit(UUID id, Edit request) {
        if (request == null || request.revision() == null) throw ProcessingException.invalid("修改元信息需要当前 revision。");
        tx.executeWithoutResult(t -> {
            var r = row(id, true); checkRevision(r, request.revision()); var values = overrides(r.overrides());
            if (request.fields() != null) for (var e : request.fields().entrySet()) {
                var f = RecognitionSchema.field(e.getKey()); RecognitionSchema.validateValues(f, e.getValue()); values.put(e.getKey(), List.copyOf(e.getValue()));
            }
            if (request.resetFields() != null) for (String key : request.resetFields()) {
                RecognitionSchema.field(key);
                if (request.fields() != null && request.fields().containsKey(key)) throw ProcessingException.invalid("同一字段不能同时修改和恢复自动值。");
                values.remove(key);
            }
            jdbc.update("UPDATE garment SET overrides_json=?,revision=revision+1,updated_at_ms=? WHERE id=?", json.writeValueAsString(values), System.currentTimeMillis(), id.toString());
        });
        return get(id);
    }
    public View select(UUID id, SelectImage request) {
        if (request == null || request.revision() == null || request.assetId() == null) throw ProcessingException.invalid("选择图片需要 revision 和 assetId。");
        tx.executeWithoutResult(t -> {
            var r = row(id, true); checkRevision(r, request.revision());
            int count = jdbc.queryForObject("SELECT COUNT(*) FROM image_asset a JOIN workflow_job j ON a.job_id=j.id WHERE a.id=? AND j.garment_id=?", Integer.class, request.assetId().toString(), id.toString());
            if (count != 1) throw ProcessingException.invalid("图片不属于此衣物。");
            jdbc.update("UPDATE garment SET display_asset_id=?,display_user_selected=1,revision=revision+1,updated_at_ms=? WHERE id=?", request.assetId().toString(), System.currentTimeMillis(), id.toString());
        });
        return get(id);
    }

    public View confirm(UUID id, Confirm request) {
        if (request == null || request.revision() == null || request.assetId() == null)
            throw ProcessingException.invalid("确认入柜需要 revision 和 assetId。");
        tx.executeWithoutResult(t -> {
            var current = row(id, true); checkRevision(current, request.revision());
            if ("TRASHED".equals(current.status())) throw ProcessingException.invalid("回收站中的衣物不能直接确认入柜。");
            int blocked = jdbc.queryForObject("SELECT COUNT(*) FROM workflow_job WHERE garment_id=? AND error_json LIKE '%GARMENT_TARGET_UNCLEAR%'", Integer.class, id.toString());
            if (blocked > 0) throw new ProcessingException(HttpStatus.CONFLICT, "GARMENT_TARGET_UNCLEAR", "照片中的衣物数量或清晰度不适合确认入柜，请更换照片后重新录入。");
            int active = jdbc.queryForObject("SELECT COUNT(*) FROM workflow_job WHERE garment_id=? AND status IN ('QUEUED','RUNNING','CANCEL_REQUESTED')", Integer.class, id.toString());
            if (active > 0) throw new ProcessingException(HttpStatus.CONFLICT, "WORKFLOW_ACTIVE", "图片仍在处理中，请等待完成或取消任务后再确认。");
            var values = overrides(current.overrides());
            if (request.fields() != null) for (var entry : request.fields().entrySet()) {
                RecognitionSchema.validateValues(RecognitionSchema.field(entry.getKey()), entry.getValue());
                values.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            if (request.resetFields() != null) for (String key : request.resetFields()) {
                RecognitionSchema.field(key);
                if (request.fields() != null && request.fields().containsKey(key)) throw ProcessingException.invalid("同一字段不能同时修改和恢复自动值。");
                values.remove(key);
            }
            var asset = jdbc.query("SELECT a.kind,a.job_id FROM image_asset a JOIN workflow_job j ON a.job_id=j.id WHERE a.id=? AND j.garment_id=?",
                    (r,n) -> Map.entry(r.getString("kind"), r.getString("job_id")), request.assetId().toString(), id.toString());
            if (asset.isEmpty()) throw ProcessingException.invalid("图片不属于此衣物。");
            var chosen = asset.getFirst();
            if (!"INPUT".equals(chosen.getKey())) {
                jdbc.update("UPDATE image_asset SET review_status='APPROVED',review_note=NULL,reviewed_at_ms=? WHERE id=?",
                        System.currentTimeMillis(), request.assetId().toString());
                jdbc.update("UPDATE workflow_job SET selected_asset_id=?,version=version+1,updated_at_ms=? WHERE id=?",
                        request.assetId().toString(), System.currentTimeMillis(), chosen.getValue());
            }
            long now = System.currentTimeMillis();
            jdbc.update("UPDATE garment SET overrides_json=?,display_asset_id=?,display_user_selected=1,closet_status='ACTIVE',restore_status=NULL,revision=revision+1,updated_at_ms=? WHERE id=?",
                    json.writeValueAsString(values), request.assetId().toString(), now, id.toString());
        });
        return get(id);
    }

    public View trash(UUID id, Long revision) {
        tx.executeWithoutResult(t -> {
            var current = row(id, true); checkRevision(current, revision == null ? -1 : revision);
            if ("TRASHED".equals(current.status())) return;
            int active = jdbc.queryForObject("SELECT COUNT(*) FROM workflow_job WHERE garment_id=? AND status IN ('QUEUED','RUNNING','CANCEL_REQUESTED')", Integer.class, id.toString());
            if (active > 0) throw new ProcessingException(HttpStatus.CONFLICT, "WORKFLOW_ACTIVE", "仍有图片任务在运行，请等待任务停止后再移入回收站。");
            jdbc.update("UPDATE garment SET restore_status=closet_status,closet_status='TRASHED',revision=revision+1,updated_at_ms=? WHERE id=?", System.currentTimeMillis(), id.toString());
        });
        return get(id);
    }

    public View restore(UUID id, Long revision) {
        tx.executeWithoutResult(t -> {
            var current = row(id, true); checkRevision(current, revision == null ? -1 : revision);
            if (!"TRASHED".equals(current.status())) throw ProcessingException.invalid("只有回收站中的衣物可以恢复。");
            String target = current.restoreStatus() == null ? "ACTIVE" : current.restoreStatus();
            jdbc.update("UPDATE garment SET closet_status=?,restore_status=NULL,revision=revision+1,updated_at_ms=? WHERE id=?", target, System.currentTimeMillis(), id.toString());
        });
        return get(id);
    }
    private void checkRevision(Row r, long version) {
        if (r.revision() != version) throw new ProcessingException(HttpStatus.CONFLICT, "GARMENT_VERSION_CONFLICT", "衣物信息已更新，请刷新后再保存；你的修改尚未提交。");
    }
    private Map<String, List<String>> overrides(String value) {
        var out = new LinkedHashMap<String, List<String>>();
        json.readTree(value).properties().forEach(e -> { var list = new ArrayList<String>(); e.getValue().forEach(v -> list.add(v.asText())); out.put(e.getKey(), list); });
        return out;
    }
    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
}
