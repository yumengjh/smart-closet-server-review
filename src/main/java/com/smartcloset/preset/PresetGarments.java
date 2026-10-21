package com.smartcloset.preset;

import com.smartcloset.agent.OutfitRules;
import com.smartcloset.garment.GarmentRepository;
import com.smartcloset.garment.ImageStyles;
import com.smartcloset.image.ProcessingException;
import com.smartcloset.recognition.RecognitionSchema;
import com.smartcloset.recommend.StyleLooks;
import com.smartcloset.security.ClosetIdentity;
import com.smartcloset.security.PresetOwner;
import com.smartcloset.workflow.Workflow;
import com.smartcloset.workflow.WorkflowFiles;
import com.smartcloset.workflow.persistence.WorkflowRepository;
import java.io.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Reviewed library snapshots and opt-in, account-owned copies. No recognition or generation calls. */
@Service
public class PresetGarments {
    private static final Set<String> PRIVATE_FIELDS = Set.of("visibleText", "size", "careInstructions", "brand", "materialComposition");
    private static final List<String> GENDERS = List.of("男款", "女款", "中性", "未标注");
    private static final List<String> ROLES = List.of("top", "bottom", "outer", "one-piece", "shoes", "accessory");
    private static final Set<String> STATUSES = Set.of("DRAFT", "LISTED", "UNLISTED");
    public record AdminImport(String username, List<UUID> garmentIds) {}
    public record Edit(String status, String gender, List<String> styles, List<String> seasons, Integer sort) {}
    public record Import(List<UUID> ids) {}
    public record Imported(List<UUID> garmentIds, List<UUID> skipped) {}
    public record Preset(UUID id, UUID sourceGarmentId, String sourceOwner, UUID displayAssetId, String displayUrl,
                         String name, Map<String,List<String>> fields, String gender, List<String> styles,
                         List<String> seasons, String status, int sort, long createdAtMs, long updatedAtMs) {}
    public record Item(UUID id, UUID displayAssetId, String displayUrl, String name, Map<String,List<String>> fields,
                       String gender, List<String> styles, List<String> seasons, boolean alreadyImported) {}
    public record Coverage(String gender, String role, String style, long count, boolean missing) {}
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final GarmentRepository garments;
    private final WorkflowRepository workflows;
    private final WorkflowFiles files;
    private final ImageStyles imageStyles;
    private final TransactionTemplate tx;

    public PresetGarments(JdbcTemplate db, ObjectMapper json, GarmentRepository garments, WorkflowRepository workflows,
                          WorkflowFiles files, ImageStyles imageStyles, PlatformTransactionManager manager) {
        this.db=db; this.json=json; this.garments=garments; this.workflows=workflows; this.files=files;
        this.imageStyles=imageStyles; this.tx=new TransactionTemplate(manager);
    }
    private static void admin() {
        if (!ClosetIdentity.administrator()) throw new ProcessingException(HttpStatus.FORBIDDEN,"ACCESS_DENIED","只有管理员可以维护预设衣物。");
    }
    private static String owner() {
        String owner=ClosetIdentity.username();
        if (owner==null || PresetOwner.reserved(owner)) throw new ProcessingException(HttpStatus.UNAUTHORIZED,"AUTHENTICATION_REQUIRED","请先登录。");
        return owner;
    }
    public List<Preset> importDrafts(AdminImport request) throws IOException {
        admin();
        if (request==null || request.username()==null || request.username().isBlank() || PresetOwner.reserved(request.username()))
            throw ProcessingException.invalid("请选择来源账号。");
        if (request.garmentIds()!=null && request.garmentIds().stream().anyMatch(Objects::isNull)) throw ProcessingException.invalid("衣物编号不能为空。");
        try {
            return tx.execute(t -> {
                db.queryForObject("SELECT value_ms FROM app_marker WHERE name='preset-import-lock' FOR UPDATE", Long.class);
                var source=garments.projection(request.username()).stream().filter(g -> "ACTIVE".equals(g.status())).toList();
                var selected=request.garmentIds()==null?null:new LinkedHashSet<>(request.garmentIds());
                if (selected!=null && !source.stream().map(GarmentRepository.Projection::id).toList().containsAll(selected))
                    throw ProcessingException.invalid("只能导入来源账号已入柜的衣物。");
                var styles=imageStyles.current(request.username());
                var result=new ArrayList<Preset>();
                for (var g:source) {
                    if (selected!=null && !selected.contains(g.id())) continue;
                    var existing=db.query("SELECT * FROM preset_garment WHERE source_garment_id=?", this::read, g.id().toString());
                    if (!existing.isEmpty() && "LISTED".equals(existing.getFirst().status())) { result.add(existing.getFirst()); continue; }
                    var view=garments.get(g.id());
                    UUID asset;
                    try { asset=sharedImage(view.displayAssetId(), styles.get(g.id().toString())); }
                    catch (IOException e) { throw new UncheckedIOException(e); }
                    // Only schema values survive: no recognition evidence, target descriptions or internal helper fields.
                    var snapshot=new LinkedHashMap<String,List<String>>();
                    for (var field:RecognitionSchema.FIELDS) if (!PRIVATE_FIELDS.contains(field.key()) && view.effective().containsKey(field.key()))
                        snapshot.put(field.key(), List.copyOf(view.effective().get(field.key())));
                    String gender=snapshot.getOrDefault("genderFit",List.of()).stream().filter(GENDERS::contains).findFirst().orElse("未标注");
                    var directions=snapshot.getOrDefault("styles",List.of()).stream().map(PresetGarments::direction)
                            .flatMap(Optional::stream).distinct().toList();
                    var seasons=snapshot.getOrDefault("seasons",List.of()).stream().filter(Set.of("春","夏","秋","冬")::contains).toList();
                    UUID id=existing.isEmpty()?UUID.randomUUID():existing.getFirst().id(); long now=System.currentTimeMillis();
                    if (existing.isEmpty()) db.update("INSERT INTO preset_garment(id,source_garment_id,source_owner,display_asset_id,name,fields_json,gender,styles_json,seasons_json,status,sort_order,created_at_ms,updated_at_ms) VALUES(?,?,?,?,?,?,?,?,?,'DRAFT',0,?,?)",
                            id.toString(),g.id().toString(),request.username(),asset.toString(),view.name(),json.writeValueAsString(snapshot),gender,json.writeValueAsString(directions),json.writeValueAsString(seasons),now,now);
                    else db.update("UPDATE preset_garment SET display_asset_id=?,name=?,fields_json=?,gender=?,styles_json=?,seasons_json=?,status='DRAFT',updated_at_ms=? WHERE id=?",
                            asset.toString(),view.name(),json.writeValueAsString(snapshot),gender,json.writeValueAsString(directions),json.writeValueAsString(seasons),now,id.toString());
                    result.add(get(id,false));
                }
                return result;
            });
        } catch (UncheckedIOException e) { throw e.getCause(); }
    }
    private UUID sharedImage(UUID sourceId, ImageStyles.Entry style) throws IOException {
        var source=workflows.asset(sourceId);
        var found=db.query("SELECT asset_id FROM preset_image WHERE sha256=?",(r,n)->UUID.fromString(r.getString(1)),source.sha256);
        if (!found.isEmpty()) return found.getFirst();
        UUID job=UUID.randomUUID(); byte[] bytes=files.readVerified(source);
        // Keep exactly the display bytes; original uploads, receipts and recognition JSON never enter this job.
        var asset=files.write(job,source.fileName.endsWith(".jpg")?"original.jpg":"input.png","PRESET",bytes);
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status!=STATUS_COMMITTED) try { files.store().delete(files.key(job,asset.fileName)); }
                catch (IOException e) { org.slf4j.LoggerFactory.getLogger(PresetGarments.class).warn("预设图片回滚清理失败: {}",job); }
            }
        });
        String url=files.url(job,asset.fileName); long now=System.currentTimeMillis();
        var request=new Workflow.Request(0d,null,null,List.of(),null,Workflow.Origin.PRESET);
        db.update("INSERT INTO workflow_job(id,fingerprint,status,created_at_ms,updated_at_ms,request_json,input_url,output_url,requires_review,selected_asset_id,version,owner_username) VALUES(?,?,'SUCCEEDED',?,?,?,?,?,0,?,0,?)",
                job.toString(),asset.sha256,now,now,json.writeValueAsString(request),url,url,asset.id,PresetOwner.USERNAME);
        db.update("INSERT INTO image_asset(id,job_id,file_name,sha256,byte_size,width,height,kind,review_status,created_at_ms) VALUES(?,?,?,?,?,?,?,'PRESET','APPROVED',?)",
                asset.id,job.toString(),asset.fileName,asset.sha256,asset.byteSize,asset.width,asset.height,now);
        db.update("INSERT INTO preset_image(sha256,asset_id,image_style) VALUES(?,?,?)",asset.sha256,asset.id,style==null?null:style.style().name());
        return UUID.fromString(asset.id);
    }
    public List<Preset> listAdmin(String status) {
        admin(); if (status!=null && !STATUSES.contains(status)) throw ProcessingException.invalid("预设状态无效。");
        return db.query("SELECT * FROM preset_garment"+(status==null?"":" WHERE status=?")+" ORDER BY sort_order,id",this::read,status==null?new Object[]{}:new Object[]{status});
    }
    public Preset edit(UUID id, Edit request) {
        admin(); if (request==null) throw ProcessingException.invalid("请输入修改内容。");
        if (request.status()!=null && !STATUSES.contains(request.status())) throw ProcessingException.invalid("预设状态无效。");
        if (request.gender()!=null && !GENDERS.contains(request.gender())) throw ProcessingException.invalid("性别只能是男款、女款、中性或未标注。");
        var styles=request.styles()==null?null:directions(request.styles());
        if (request.seasons()!=null && !request.seasons().stream().allMatch(s -> s!=null && Set.of("春","夏","秋","冬").contains(s)))
            throw ProcessingException.invalid("季节只能是春、夏、秋、冬。");
        return tx.execute(t -> {
            var p=get(id,true); var fields=new LinkedHashMap<>(p.fields());
            String gender=request.gender()==null?p.gender():request.gender(); var seasons=request.seasons()==null?p.seasons():request.seasons().stream().distinct().toList();
            // Reviewed gender/season tags also apply to new user copies. Existing copies remain independent.
            if (request.gender()!=null) fields.put("genderFit",gender.equals("未标注")?List.of():List.of(gender));
            if (request.seasons()!=null) fields.put("seasons",seasons);
            db.update("UPDATE preset_garment SET status=?,gender=?,styles_json=?,seasons_json=?,sort_order=?,fields_json=?,updated_at_ms=? WHERE id=?",
                    request.status()==null?p.status():request.status(),gender,json.writeValueAsString(styles==null?p.styles():styles),json.writeValueAsString(seasons),
                    request.sort()==null?p.sort():request.sort(),json.writeValueAsString(fields),System.currentTimeMillis(),id.toString());
            return get(id,false);
        });
    }
    private static Optional<String> direction(String word) {
        if (word==null) return Optional.empty();
        if (Set.of("都市通勤","通勤").contains(word)) return Optional.of("都市通勤");
        if (Set.of("潮流运动","运动").contains(word)) return Optional.of("潮流运动");
        return StyleLooks.defaults().find(word).map(StyleLooks.Look::label);
    }
    private List<String> directions(List<String> words) {
        return words.stream().map(w -> direction(w).orElseThrow(() -> ProcessingException.invalid("未知风格方向："+w))).distinct().toList();
    }
    public List<Coverage> coverage() {
        admin(); var presets=listAdmin("LISTED"); var labels=new ArrayList<>(StyleLooks.defaults().looks.stream().map(StyleLooks.Look::label).toList()); labels.addAll(List.of("都市通勤","潮流运动","未标注"));
        var result=new ArrayList<Coverage>();
        for (String gender:GENDERS) for (String role:ROLES) for (String style:labels) {
            long count=presets.stream().filter(p -> p.gender().equals(gender) && role(p.fields()).equals(role)
                    && (style.equals("未标注")?p.styles().isEmpty():p.styles().contains(style))).count();
            result.add(new Coverage(gender,role,style,count,count==0));
        }
        return result;
    }
    private static String role(Map<String,List<String>> fields) {
        return switch (OutfitRules.role(fields)) {
            case TOP, INNER -> "top"; case BOTTOM -> "bottom"; case OUTER -> "outer";
            case ONE_PIECE, OVERALL -> "one-piece"; case SHOES -> "shoes"; case OTHER -> "other"; default -> "accessory";
        };
    }
    public List<Item> list(String gender, List<String> styles, String season) {
        String owner=owner();
        var selectedStyles=styles==null || styles.isEmpty()?List.<String>of():directions(styles);
        var imported=new HashSet<>(db.query("SELECT preset_id FROM garment WHERE owner_username=? AND preset_id IS NOT NULL",(r,n)->r.getString(1),owner));
        return db.query("SELECT * FROM preset_garment WHERE status='LISTED' ORDER BY sort_order,id",this::read).stream()
                .filter(p -> gender==null || gender.isBlank() || p.gender().equals(gender))
                .filter(p -> selectedStyles.isEmpty() || p.styles().stream().anyMatch(selectedStyles::contains))
                .filter(p -> season==null || season.isBlank() || p.seasons().contains(season))
                .map(p -> new Item(p.id(),p.displayAssetId(),p.displayUrl(),p.name(),p.fields(),p.gender(),p.styles(),p.seasons(),imported.contains(p.id().toString()))).toList();
    }
    public Imported importIntoCloset(Import request) {
        String owner=owner();
        if (request==null || request.ids()==null || request.ids().isEmpty() || request.ids().size()>100 || request.ids().stream().anyMatch(Objects::isNull))
            throw ProcessingException.invalid("请勾选 1 到 100 件预设衣物。");
        return tx.execute(t -> {
            // Lock the account, so simultaneous imports (even on different application instances) cannot create two copies.
            if (db.query("SELECT username FROM closet_user WHERE username=? FOR UPDATE",(r,n)->r.getString(1),owner).isEmpty())
                throw ProcessingException.invalid("账号不存在。");
            var created=new ArrayList<UUID>(); var skipped=new ArrayList<UUID>();
            for (UUID id:new LinkedHashSet<>(request.ids())) {
                if (db.queryForObject("SELECT COUNT(*) FROM garment WHERE owner_username=? AND preset_id=?",Integer.class,owner,id.toString())>0) { skipped.add(id); continue; }
                var p=get(id,true);
                if (!p.status().equals("LISTED")) throw ProcessingException.invalid("只能导入已上架的预设衣物。");
                UUID garment=UUID.randomUUID(); long now=System.currentTimeMillis();
                db.update("INSERT INTO garment(id,source_asset_id,display_asset_id,source_origin,preset_id,overrides_json,display_user_selected,revision,created_at_ms,updated_at_ms,owner_username,closet_status,name) VALUES(?,?,?,'PRESET',?,?,0,0,?,?,?,'ACTIVE',?)",
                        garment.toString(),p.displayAssetId().toString(),p.displayAssetId().toString(),id.toString(),json.writeValueAsString(p.fields()),now,now,owner,p.name());
                var style=db.query("SELECT image_style FROM preset_image WHERE asset_id=?",(r,n)->r.getString(1),p.displayAssetId().toString());
                if (!style.isEmpty() && style.getFirst()!=null) db.update("INSERT INTO garment_image_style(garment_id,owner_username,display_asset_id,style,source,evidence,updated_at_ms) VALUES(?,?,?,?,'MODEL','预设展示图的图片风格快照',?)",
                        garment.toString(),owner,p.displayAssetId().toString(),style.getFirst(),now);
                created.add(garment);
            }
            return new Imported(created,skipped);
        });
    }
    private Preset get(UUID id, boolean lock) {
        var rows=db.query("SELECT * FROM preset_garment WHERE id=?"+(lock?" FOR UPDATE":""),this::read,id.toString());
        if (rows.isEmpty()) throw new ProcessingException(HttpStatus.NOT_FOUND,"PRESET_NOT_FOUND","没有找到该预设衣物。");
        return rows.getFirst();
    }
    private Preset read(ResultSet r,int n) throws SQLException {
        return new Preset(UUID.fromString(r.getString("id")),UUID.fromString(r.getString("source_garment_id")),r.getString("source_owner"),UUID.fromString(r.getString("display_asset_id")),
                garments.assetUrl(r.getString("display_asset_id")),r.getString("name"),json.readValue(r.getString("fields_json"),new TypeReference<Map<String,List<String>>>(){}),r.getString("gender"),
                json.readValue(r.getString("styles_json"),new TypeReference<List<String>>(){}),json.readValue(r.getString("seasons_json"),new TypeReference<List<String>>(){}),r.getString("status"),r.getInt("sort_order"),r.getLong("created_at_ms"),r.getLong("updated_at_ms"));
    }
}
