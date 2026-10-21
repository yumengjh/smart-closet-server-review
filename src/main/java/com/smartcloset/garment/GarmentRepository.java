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
                       List<ImageOption> images, ImageProcessing imageProcessing, String name, boolean favorite) {}
    public record ImageProcessing(UUID jobId, String status, boolean stickerRequired, boolean stickerReady, UUID stickerAssetId) {}
    public record ImageOption(UUID id, String url, String kind, String reviewStatus, boolean aiGenerated) { public ImageOption(UUID id,String url,String kind,String reviewStatus){this(id,url,kind,reviewStatus,false);} }
    public record ClosetPage(List<View> items, long total, int page, int size) {}
    public record Run(UUID id, String status, long createdAtMs, String outputUrl) {}
    public record Edit(Long revision, Map<String, List<String>> fields, List<String> resetFields) {}
    public record SelectImage(Long revision, UUID assetId) {}
    public record Confirm(Long revision, UUID assetId, Map<String, List<String>> fields, List<String> resetFields, Boolean allowIncompleteSticker) {
        public Confirm(Long revision, UUID assetId, Map<String, List<String>> fields, List<String> resetFields) {
            this(revision, assetId, fields, resetFields, false);
        }
    }
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
            var effective = effective(automatic, overrides);
            var runs = jdbc.query("SELECT id,status,created_at_ms,output_url FROM workflow_job WHERE garment_id=? ORDER BY created_at_ms DESC,id DESC",
                (rs, n) -> new Run(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3), rs.getString(4)), id.toString());
            var images = jdbc.query("SELECT a.id,j.id AS job_id,a.file_name,a.kind,a.review_status,(SELECT MIN(step_no) FROM workflow_step s WHERE s.job_id=j.id AND s.step_type='GENERATE' AND s.status='SUCCEEDED') AS generated_step FROM image_asset a JOIN workflow_job j ON a.job_id=j.id WHERE j.garment_id=? ORDER BY j.created_at_ms,a.created_at_ms,a.file_name",
                    (rs,n) -> new ImageOption(UUID.fromString(rs.getString(1)), "/api/workflows/" + rs.getString(2) + "/files/" + rs.getString(3), rs.getString(4), rs.getString(5), "AI_ENHANCED".equals(rs.getString(4)) || "GENERATE".equals(rs.getString(4)) || rs.getObject("generated_step")!=null && rs.getString(3).matches("step-[0-5]\\.png") && Integer.parseInt(rs.getString(3).substring(5,6))>=rs.getInt("generated_step")), id.toString());
            return new View(id, r.revision(), UUID.fromString(r.source()), assetUrl(r.source()), r.origin(), UUID.fromString(r.display()), assetUrl(r.display()),
                automatic, overrides, effective, uuid(r.latest()), uuid(r.applied()), runs,
                r.status(), r.restoreStatus(), r.created(), r.updated(), uuid(r.latestImage()), images, imageProcessing(r), jdbc.queryForObject("SELECT name FROM garment WHERE id=?", String.class, id.toString()), Boolean.TRUE.equals(jdbc.queryForObject("SELECT favorite FROM garment WHERE id=?", Boolean.class, id.toString())));
        });
    }
    private ImageProcessing imageProcessing(Row row) {
        if (row.latestImage() == null) return null;
        String status = jdbc.queryForObject("SELECT status FROM workflow_job WHERE id=?", String.class, row.latestImage());
        var steps = jdbc.query("SELECT result_json FROM workflow_step WHERE job_id=? AND step_type='STICKER' ORDER BY step_no DESC",
                (rs,n) -> json.readValue(rs.getString(1), StepResult.class), row.latestImage());
        if (steps.isEmpty()) return new ImageProcessing(uuid(row.latestImage()), status, false, false, null);
        var step = steps.getFirst();
        var assets = step.status() == Status.SUCCEEDED && step.outputUrl() != null
                ? jdbc.query("SELECT id FROM image_asset WHERE job_id=? AND kind IN ('STICKER','AI_ENHANCED','BASE') AND file_name=?",
                    (rs,n) -> UUID.fromString(rs.getString(1)), row.latestImage(), step.outputUrl().substring(step.outputUrl().lastIndexOf('/') + 1))
                : List.<UUID>of();
        if (assets.isEmpty()) {
            assets = jdbc.query("SELECT a.id FROM image_asset a JOIN workflow_job j ON a.job_id=j.id WHERE j.garment_id=? AND a.kind='BASE_STICKER' ORDER BY a.created_at_ms DESC",
                    (rs,n) -> UUID.fromString(rs.getString(1)), row.id());
        }
        return new ImageProcessing(uuid(row.latestImage()), status, true, !assets.isEmpty(), assets.isEmpty() ? null : assets.getFirst());
    }
    /** Closet browsing orders; the projection already arrives newest-updated first, which is {@code updated}. */
    public static final Set<String> CLOSET_SORTS = Set.of("updated", "created", "created_asc", "favorite", "category", "color", "name");
    public ClosetPage listCloset(String owner, String status, String query, String category, String color, String season, int page, int size) {
        return listCloset(owner, status, query, category, color, season, page, size, "updated");
    }
    public ClosetPage listCloset(String owner, String status, String query, String category, String color, String season, int page, int size, String sort) {
        if (page < 1 || size < 1 || size > 50) throw ProcessingException.invalid("page 从 1 开始，size 为 1 到 50。");
        if (!CLOSET_SORTS.contains(sort)) throw ProcessingException.invalid("sort 只能是 " + String.join("、", new TreeSet<>(CLOSET_SORTS)) + "。");
        var rows = sorted(owner, projection(owner).stream().filter(g -> g.status().equals(status))
            .filter(g -> matches(g, query, category, color, season)).toList(), sort);
        int from = (int)Math.min(rows.size(), (long)(page - 1) * size), to = Math.min(rows.size(), from + size);
        return new ClosetPage(rows.subList(from, to).stream().map(g -> get(g.id())).toList(), rows.size(), page, size);
    }
    private List<Projection> sorted(String owner, List<Projection> rows, String sort) {
        if (sort.equals("updated")) return rows;
        if (sort.equals("favorite")) return rows.stream().sorted(Comparator.comparing((Projection g) -> !g.favorite())).toList();
        if (sort.startsWith("created")) {
            Map<UUID,Long> created = new HashMap<>();
            jdbc.query("SELECT id,created_at_ms FROM garment WHERE owner_username=?", rs -> { created.put(UUID.fromString(rs.getString(1)), rs.getLong(2)); }, owner);
            Comparator<Projection> order = Comparator.comparingLong(g -> created.getOrDefault(g.id(), 0L));
            return rows.stream().sorted(sort.equals("created") ? order.reversed() : order).toList();
        }
        // Text orders: pinyin collation, items without the value go last, ties keep the newest-updated order (stable sort).
        var collator = java.text.Collator.getInstance(java.util.Locale.CHINA);
        return rows.stream().sorted(Comparator.comparing((Projection g) -> sortKey(g, sort).isEmpty()).thenComparing(g -> sortKey(g, sort), collator)).toList();
    }
    private static String sortKey(Projection g, String sort) {
        if (sort.equals("name")) return g.name() == null ? "" : g.name().trim();
        var v = g.fields().getOrDefault(sort.equals("color") ? "primaryColor" : "category", List.of());
        return v.isEmpty() ? "" : v.getFirst();
    }
    public record Projection(UUID id, long revision, String status, String name, boolean favorite,
            Map<String,List<String>> fields, String family, boolean wearingConfirmed) {}
    /** Lightweight authoritative projection; no images, tasks or model calls. */
    public List<Projection> projection(String owner) {
        return jdbc.query("SELECT id,revision,closet_status,name,favorite,automatic_json,overrides_json FROM garment WHERE owner_username=? ORDER BY updated_at_ms DESC,id DESC", (rs,n) -> {
            var automatic=rs.getString("automatic_json")==null?null:json.readValue(rs.getString("automatic_json"),Recognition.Result.class);
            var userValues=overrides(rs.getString("overrides_json"));var fields=effective(automatic,userValues);
            return new Projection(UUID.fromString(rs.getString("id")),rs.getLong("revision"),rs.getString("closet_status"),rs.getString("name"),rs.getBoolean("favorite"),fields,ClosetClassification.family(fields.getOrDefault("category",List.of())),List.of("fit","thickness","wearingModes").stream().allMatch(k -> userValues.containsKey(k)&&!userValues.get(k).isEmpty()));
        },owner);
    }
    /** Projection plus where each effective field came from: USER for owner edits, otherwise the recognition state. */
    public record Sourced(Projection projection, Map<String,String> fieldStatus) {}
    public List<Sourced> sourcedProjection(String owner) {
        return jdbc.query("SELECT id,revision,closet_status,name,favorite,automatic_json,overrides_json FROM garment WHERE owner_username=? AND closet_status='ACTIVE' ORDER BY updated_at_ms DESC,id DESC", (rs,n) -> {
            var automatic=rs.getString("automatic_json")==null?null:json.readValue(rs.getString("automatic_json"),Recognition.Result.class);
            var userValues=overrides(rs.getString("overrides_json"));var fields=effective(automatic,userValues);
            var status=new LinkedHashMap<String,String>();
            for(var key:fields.keySet())status.put(key,userValues.containsKey(key)?"USER":automatic==null||automatic.attributes().get(key)==null?"UNKNOWN":automatic.attributes().get(key).status().name());
            var projection=new Projection(UUID.fromString(rs.getString("id")),rs.getLong("revision"),rs.getString("closet_status"),rs.getString("name"),rs.getBoolean("favorite"),fields,ClosetClassification.family(fields.getOrDefault("category",List.of())),List.of("fit","thickness","wearingModes").stream().allMatch(k -> userValues.containsKey(k)&&!userValues.get(k).isEmpty()));
            return new Sourced(projection,status);
        },owner);
    }
    /** Text sources for the local semantic index: effective fields plus recognition evidence. No images, no model calls. */
    public record CardSource(String id,long revision,String name,Map<String,List<String>> fields,String targetDescription,Map<String,String> evidence) {}
    public List<CardSource> cardSources(String owner,Collection<String> ids) {
        if(ids.isEmpty())return List.of();
        var placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));var args=new ArrayList<Object>();args.add(owner);args.addAll(ids);
        return jdbc.query("SELECT id,revision,name,automatic_json,overrides_json FROM garment WHERE owner_username=? AND id IN ("+placeholders+")",(rs,n) -> {
            var automatic=rs.getString("automatic_json")==null?null:json.readValue(rs.getString("automatic_json"),Recognition.Result.class);
            var userValues=overrides(rs.getString("overrides_json"));var evidence=new LinkedHashMap<String,String>();
            if(automatic!=null)automatic.attributes().forEach((key,attribute) -> {if(!userValues.containsKey(key)&&attribute!=null&&attribute.evidence()!=null&&!attribute.evidence().isBlank())evidence.put(key,attribute.evidence());});
            return new CardSource(rs.getString("id"),rs.getLong("revision"),rs.getString("name"),effective(automatic,userValues),automatic==null||automatic.target()==null?null:automatic.target().description(),evidence);
        },args.toArray());
    }
    public static boolean matches(Projection item,String query,String category,String color,String season) {
        var f=item.fields();
        if(category!=null&&!category.isBlank()&&!ClosetClassification.matches(f.getOrDefault("category",List.of()),category))return false;
        if(color!=null&&!color.isBlank()&&!MetadataValues.matchesColor(f.getOrDefault("primaryColor",List.of()),color))return false;
        if(season!=null&&!season.isBlank()&&!f.getOrDefault("seasons",List.of()).contains(MetadataValues.canonical("seasons",season)))return false;
        String q=query==null?"":query.strip().toLowerCase(Locale.ROOT);
        return q.isBlank()||Objects.toString(item.name(),"").toLowerCase(Locale.ROOT).contains(q)||f.values().stream().flatMap(Collection::stream).anyMatch(v -> v.toLowerCase(Locale.ROOT).contains(q))||ClosetClassification.matches(f.getOrDefault("category",List.of()),q);
    }
    public record BasicEdit(Long revision,String name,Boolean favorite) {}
    public View editBasic(String owner,UUID id,BasicEdit request) {
        if(request==null||request.revision()==null)throw ProcessingException.invalid("需要当前 revision。");
        if(request.name()!=null&&(request.name().isBlank()||request.name().length()>100))throw ProcessingException.invalid("名称为 1 到 100 字。");
        tx.executeWithoutResult(t -> {
            var current=row(id,true);checkRevision(current,request.revision());
            if(jdbc.queryForObject("SELECT COUNT(*) FROM garment WHERE id=? AND owner_username=?",Integer.class,id.toString(),owner)!=1)throw new ProcessingException(HttpStatus.NOT_FOUND,"GARMENT_NOT_FOUND","没有找到该衣物。");
            if(request.name()!=null)jdbc.update("UPDATE garment SET name=? WHERE id=?",request.name().strip(),id.toString());
            if(request.favorite()!=null)jdbc.update("UPDATE garment SET favorite=? WHERE id=?",request.favorite(),id.toString());
            jdbc.update("UPDATE garment SET revision=revision+1,updated_at_ms=? WHERE id=?",System.currentTimeMillis(),id.toString());
        });return get(id);
    }
    /** Only confirmed, currently visible clothes contribute reusable account-specific choices. */
    public Map<String, List<String>> metadataOptions(String owner) {
        var choices = new LinkedHashMap<String, SortedSet<String>>();
        for (var field : RecognitionSchema.FIELDS) choices.put(field.key(), new TreeSet<>());
        jdbc.query("SELECT automatic_json,overrides_json FROM garment WHERE owner_username=? AND closet_status='ACTIVE'", (rs, n) -> {
            var automatic = rs.getString(1) == null ? null : json.readValue(rs.getString(1), Recognition.Result.class);
            effective(automatic, overrides(rs.getString(2))).forEach((key, values) -> {
                choices.get(key).addAll(values);
                if (key.equals("primaryColor")) for (String value : values) choices.get(key).add(MetadataValues.colorFamily(value));
            });
            return null;
        }, owner);
        var result = new LinkedHashMap<String, List<String>>();
        choices.forEach((key, values) -> result.put(key, List.copyOf(values)));
        return result;
    }
    public List<RecognitionSchema.Field> metadataFields(String owner) {
        var choices = metadataOptions(owner);
        return RecognitionSchema.FIELDS.stream().map(f -> {
            var values = new LinkedHashSet<>(f.options()); values.addAll(choices.get(f.key()));
            return new RecognitionSchema.Field(f.key(), f.label(), List.copyOf(values), f.multiple(), f.suggestion());
        }).toList();
    }
    /** Category family of one stored row (closet-families-v1), for aggregate exports that must not load whole wardrobes. */
    public String family(String automaticJson, String overridesJson) {
        var automatic = automaticJson == null ? null : json.readValue(automaticJson, Recognition.Result.class);
        return ClosetClassification.family(effective(automatic, overrides(overridesJson == null ? "{}" : overridesJson)).getOrDefault("category", List.of()));
    }
    private Map<String, List<String>> effective(Recognition.Result automatic, Map<String, List<String>> overrides) {
        var values = new LinkedHashMap<String, List<String>>();
        for (var field : RecognitionSchema.FIELDS) values.put(field.key(), MetadataValues.canonical(field.key(), overrides.containsKey(field.key())
            ? overrides.get(field.key()) : automatic == null || !automatic.attributes().containsKey(field.key()) ? List.of() : automatic.attributes().get(field.key()).values()));
        return values;
    }
    public List<UUID> list(int page, int size) {
        if (page < 1 || size < 1 || size > 50) throw ProcessingException.invalid("page 从 1 开始，size 为 1 到 50。");
        return jdbc.query("SELECT id FROM garment ORDER BY created_at_ms DESC,id DESC LIMIT ? OFFSET ?", (r, n) -> UUID.fromString(r.getString(1)), size, (long)(page - 1) * size);
    }
    /**
     * Display image paths of the garments among the ids, in one query; for cards that only need a picture. With an
     * owner only that owner's garments answer; null is for ids already taken from the owner's own inventory.
     */
    public Map<String, String> displayUrls(String owner, Collection<String> ids) {
        if (ids.isEmpty()) return Map.of();
        var urls = new HashMap<String, String>();
        var args = new ArrayList<Object>(ids); if (owner != null) args.add(owner);
        jdbc.query("SELECT g.id,a.job_id,a.file_name FROM garment g JOIN image_asset a ON a.id=g.display_asset_id WHERE g.id IN ("
                + String.join(",", Collections.nCopies(ids.size(), "?")) + ")" + (owner != null ? " AND g.owner_username=?" : ""),
                rs -> { urls.put(rs.getString(1), "/api/workflows/" + rs.getString(2) + "/files/" + rs.getString(3)); }, args.toArray());
        return urls;
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
        // A replacement upload belongs to the caller; future reruns must use that photo, never the shared source.
        if (count > 0 && job.request().sourceOrigin() == Origin.USER_UPLOAD && job.parentJobId() == null
                && jdbc.queryForObject("SELECT COUNT(*) FROM garment WHERE id=? AND preset_id IS NOT NULL", Integer.class, id.toString()) > 0) {
            assets.stream().filter(a -> "INPUT".equals(a.kind)).findFirst().ifPresent(a ->
                jdbc.update("UPDATE garment SET source_asset_id=? WHERE id=?", a.id, id.toString()));
        }
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
            jdbc.update("UPDATE garment SET display_asset_id=?,source_origin=CASE WHEN source_origin='PRESET' THEN 'USER_UPLOAD' ELSE source_origin END,revision=revision+1,updated_at_ms=? WHERE id=? AND latest_image_job_id=? AND display_user_selected=0 AND closet_status!='TRASHED'",
                asset.id, System.currentTimeMillis(), id.toString(), job.id().toString());
    }
    /** The garment belongs to the signed-in user and was imported from the preset library. */
    public boolean presetCopyOfCaller(UUID id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM garment WHERE id=? AND owner_username=? AND preset_id IS NOT NULL AND closet_status!='TRASHED'", Integer.class,
            id.toString(), com.smartcloset.security.ClosetIdentity.username()) == 1;
    }
    public View edit(UUID id, Edit request) {
        if (request == null || request.revision() == null) throw ProcessingException.invalid("修改元信息需要当前 revision。");
        tx.executeWithoutResult(t -> {
            var r = row(id, true); checkRevision(r, request.revision()); var values = overrides(r.overrides());
            if (request.fields() != null) for (var e : request.fields().entrySet()) {
                var f = RecognitionSchema.field(e.getKey()); values.put(e.getKey(), RecognitionSchema.normalizeValues(f, e.getValue()));
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
            jdbc.update("UPDATE garment SET display_asset_id=?,source_origin=CASE WHEN source_origin='PRESET' THEN 'USER_UPLOAD' ELSE source_origin END,display_user_selected=1,revision=revision+1,updated_at_ms=? WHERE id=?", request.assetId().toString(), System.currentTimeMillis(), id.toString());
            bumpQuickSelection(id);
        });
        return get(id);
    }

    private void bumpQuickSelection(UUID id){jdbc.update("UPDATE quick_intake q SET revision=revision+1 WHERE EXISTS(SELECT 1 FROM workflow_job j WHERE j.id=q.id AND j.garment_id=?)",id.toString());}
    public View confirm(UUID id, Confirm request) {
        if (request == null || request.revision() == null || request.assetId() == null)
            throw ProcessingException.invalid("确认入柜需要 revision 和 assetId。");
        tx.executeWithoutResult(t -> {
            var current = row(id, true); checkRevision(current, request.revision());
            if ("TRASHED".equals(current.status())) throw ProcessingException.invalid("回收站中的衣物不能直接确认入柜。");
            int blocked = jdbc.queryForObject("SELECT COUNT(*) FROM workflow_job WHERE garment_id=? AND error_json LIKE '%GARMENT_TARGET_UNCLEAR%' AND created_at_ms >= COALESCE((SELECT created_at_ms FROM workflow_job WHERE id=?),0)", Integer.class, id.toString(), current.applied());
            var assessment = current.automatic() == null ? null : json.readValue(current.automatic(), Recognition.Result.class);
            if (assessment != null && assessment.blocksImages()) blocked++;
            if (blocked > 0) throw new ProcessingException(HttpStatus.CONFLICT, "GARMENT_TARGET_UNCLEAR", "照片中的衣物数量或清晰度不适合确认入柜，请更换照片后重新录入。");
            int active = jdbc.queryForObject("SELECT COUNT(*) FROM workflow_job WHERE garment_id=? AND status IN ('QUEUED','RUNNING','CANCEL_REQUESTED')", Integer.class, id.toString());
            if (active > 0) throw new ProcessingException(HttpStatus.CONFLICT, "WORKFLOW_ACTIVE", "图片仍在处理中，请等待完成或取消任务后再确认。");
            var image = imageProcessing(current);
            if (image != null && image.stickerRequired() && !image.stickerReady()
                    && !(Boolean.TRUE.equals(request.allowIncompleteSticker()) && request.assetId().toString().equals(current.source())))
                throw new ProcessingException(HttpStatus.CONFLICT, "STICKER_NOT_READY", "贴纸尚未制作完成，请继续处理；如需先入柜，请明确选择暂用上传原图。");
            var values = overrides(current.overrides());
            if (request.fields() != null) for (var entry : request.fields().entrySet()) {
                values.put(entry.getKey(), RecognitionSchema.normalizeValues(RecognitionSchema.field(entry.getKey()), entry.getValue()));
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
            jdbc.update("UPDATE garment SET overrides_json=?,display_asset_id=?,source_origin=CASE WHEN source_origin='PRESET' THEN 'USER_UPLOAD' ELSE source_origin END,display_user_selected=1,closet_status='ACTIVE',restore_status=NULL,revision=revision+1,updated_at_ms=? WHERE id=?",
                    json.writeValueAsString(values), request.assetId().toString(), now, id.toString());
            bumpQuickSelection(id);
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
