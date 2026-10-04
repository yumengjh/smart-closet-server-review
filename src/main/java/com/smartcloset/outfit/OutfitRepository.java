package com.smartcloset.outfit;

import com.smartcloset.image.ProcessingException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
public class OutfitRepository {
    public static final int SCENE_VERSION = 2;
    private static final double BOARD_WIDTH = 1080;
    private static final double BOARD_HEIGHT = 1350;
    private static final Set<String> ROLES = Set.of("TOP", "OUTER", "BOTTOM", "ONE_PIECE", "SHOES", "HAT", "BAG", "ACCESSORY", "OTHER");

    public record ItemWrite(UUID garmentId, UUID assetId, String role, double x, double y,
                            double width, double height, double rotation, int zIndex, String garmentLabel,
                            double cropX, double cropY, double cropWidth, double cropHeight,
                            UUID elementId, String kind, String stickerId, Integer stickerVersion, Boolean flipX, Boolean freeStretch) {}
    public record SceneWrite(int version, List<ItemWrite> elements) {}
    public record Item(UUID garmentId, UUID assetId, String role, double x, double y, double width,
                       double height, double rotation, int zIndex, String garmentLabel, long garmentRevision,
                       String assetUrl, int imageWidth, int imageHeight, String assetKind,
                       String garmentStatus, boolean garmentChanged, boolean imageChanged,
                       double cropX, double cropY, double cropWidth, double cropHeight,
                            UUID elementId, String kind, String stickerId, Integer stickerVersion, Boolean flipX, Boolean freeStretch) {}
    public record Scene(int version, List<Item> elements) {}
    public record View(UUID id, String name, long revision, long createdAtMs, long updatedAtMs, Scene scene) {}
    public record SaveRequest(Long revision, String name, SceneWrite scene) {}
    private record OutfitRow(UUID id, String name, long revision, long created, long updated, int sceneVersion, String sceneJson) {}
    private record AssetRow(String owner, String status, String displayAssetId, long garmentRevision,
                            String jobId, String fileName, int width, int height, String kind) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final PersonStickerCatalog stickers;

    public OutfitRepository(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager, PersonStickerCatalog stickers) {
        this.jdbc = jdbc;
        this.stickers = stickers;
        this.json = json;
        this.tx = new TransactionTemplate(manager);
    }

    public List<View> list(String owner) {
        return jdbc.query("SELECT id,name,revision,created_at_ms,updated_at_ms,scene_version,scene_json FROM outfit WHERE owner_username=? ORDER BY updated_at_ms DESC,id DESC",
                (rs, n) -> view(new OutfitRow(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3),
                        rs.getLong(4), rs.getLong(5), rs.getInt(6), rs.getString(7)), owner), owner);
    }

    public View get(UUID id, String owner) {
        return find(id, owner, false);
    }

    public void validatePreview(SceneWrite scene, String owner) {
        if (scene == null || scene.elements() == null) throw ProcessingException.invalid("缺少画板内容。");
        if (scene.elements().isEmpty() && scene.version() == SCENE_VERSION) return;
        validateRequest(new SaveRequest(null, "临时搭台", scene), true);
        snapshot(scene, owner);
    }

    public Scene previewSnapshot(SceneWrite scene, String owner) {
        validatePreview(scene, owner);
        return scene.elements().isEmpty() ? new Scene(SCENE_VERSION, List.of()) : snapshot(scene, owner);
    }

    public View create(SaveRequest request, String owner) {
        validateRequest(request, true);
        return tx.execute(status -> {
            UUID id = UUID.randomUUID();
            long now = System.currentTimeMillis();
            String name = cleanName(request.name());
            Scene scene = snapshot(request.scene(), owner);
            jdbc.update("INSERT INTO outfit(id,owner_username,name,scene_version,scene_json,revision,created_at_ms,updated_at_ms) VALUES(?,?,?,?,?,0,?,?)",
                    id.toString(), owner, name, SCENE_VERSION, json.writeValueAsString(scene), now, now);
            return new View(id, name, 0, now, now, scene);
        });
    }

    public View update(UUID id, SaveRequest request, String owner) {
        validateRequest(request, false);
        return tx.execute(status -> {
            OutfitRow current = row(id, owner, true);
            if (!Objects.equals(request.revision(), current.revision())) throw conflict();
            Scene scene = snapshot(request.scene(), owner);
            String name = cleanName(request.name());
            long now = System.currentTimeMillis();
            int changed = jdbc.update("UPDATE outfit SET name=?,scene_version=?,scene_json=?,revision=revision+1,updated_at_ms=? WHERE id=? AND owner_username=? AND revision=?",
                    name, SCENE_VERSION, json.writeValueAsString(scene), now, id.toString(), owner, current.revision());
            if (changed != 1) throw conflict();
            return new View(id, name, current.revision() + 1, current.created(), now, scene);
        });
    }

    public void delete(UUID id, Long revision, String owner) {
        tx.executeWithoutResult(status -> {
            OutfitRow current = row(id, owner, true);
            if (revision == null || revision != current.revision()) throw conflict();
            jdbc.update("DELETE FROM outfit WHERE id=? AND owner_username=? AND revision=?", id.toString(), owner, revision);
        });
    }

    private View find(UUID id, String owner, boolean lock) {
        return view(row(id, owner, lock), owner);
    }

    private OutfitRow row(UUID id, String owner, boolean lock) {
        String sql = "SELECT id,name,revision,created_at_ms,updated_at_ms,scene_version,scene_json FROM outfit WHERE id=? AND owner_username=?"
                + (lock ? " FOR UPDATE" : "");
        var rows = jdbc.query(sql, (rs, n) -> new OutfitRow(UUID.fromString(rs.getString(1)), rs.getString(2),
                rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getInt(6), rs.getString(7)), id.toString(), owner);
        if (rows.isEmpty()) throw new ProcessingException(HttpStatus.NOT_FOUND, "OUTFIT_NOT_FOUND", "没有找到这套搭配。");
        return rows.getFirst();
    }

    private View view(OutfitRow row, String owner) {
        Scene stored;
        try {
            stored = json.readValue(row.sceneJson(), Scene.class);
        } catch (JacksonException exception) {
            throw new ProcessingException(HttpStatus.INTERNAL_SERVER_ERROR, "OUTFIT_DATA_INVALID", "搭配数据暂时无法读取。");
        }
        return new View(row.id(), row.name(), row.revision(), row.created(), row.updated(), refreshStatus(stored, owner));
    }

    private Scene snapshot(SceneWrite scene, String owner) {
        var result = new ArrayList<Item>(scene.elements().size());
        for (ItemWrite item : scene.elements()) {
            if ("PERSON_PART".equals(item.kind())) {
                var part = stickers.part(item.stickerId(), item.stickerVersion());
                result.add(new Item(null, null, "OTHER", item.x(), item.y(), item.width(), item.height(), item.rotation(), item.zIndex(),
                        part.label(), 0, part.url(), part.width(), part.height(), "PERSON_PART", null, false, false,
                        item.cropX(), item.cropY(), item.cropWidth(), item.cropHeight(), item.elementId(), "PERSON_PART", part.id(), part.version(), Boolean.TRUE.equals(item.flipX()), Boolean.TRUE.equals(item.freeStretch())));
                continue;
            }
            AssetRow asset = jdbc.query("SELECT g.owner_username,g.closet_status,g.display_asset_id,g.revision,j.id,a.file_name,a.width,a.height,a.kind "
                            + "FROM garment g JOIN workflow_job j ON j.garment_id=g.id JOIN image_asset a ON a.job_id=j.id "
                            + "WHERE g.id=? AND a.id=? AND g.owner_username=?",
                    (rs, n) -> new AssetRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                            rs.getString(5), rs.getString(6), rs.getInt(7), rs.getInt(8), rs.getString(9)),
                    item.garmentId().toString(), item.assetId().toString(), owner).stream().findFirst()
                    .orElseThrow(() -> new ProcessingException(HttpStatus.NOT_FOUND, "OUTFIT_ASSET_NOT_FOUND", "搭配中的衣物图片已不可用，请重新选择。"));
            if (!"ACTIVE".equals(asset.status())) throw new ProcessingException(HttpStatus.CONFLICT, "OUTFIT_GARMENT_NOT_ACTIVE", "只能把已入柜的衣物加入搭配。");
            String assetUrl = "/api/workflows/" + asset.jobId() + "/files/" + asset.fileName();
            result.add(new Item(item.garmentId(), item.assetId(), item.role(), item.x(), item.y(), item.width(), item.height(),
                    item.rotation(), item.zIndex(), item.garmentLabel().strip(), asset.garmentRevision(), assetUrl,
                    asset.width(), asset.height(), asset.kind(), asset.status(), false, false,
                    item.cropX(), item.cropY(), item.cropWidth(), item.cropHeight(),
                    item.elementId() == null ? item.garmentId() : item.elementId(), "GARMENT", null, 0, Boolean.TRUE.equals(item.flipX()), Boolean.TRUE.equals(item.freeStretch())));
        }
        return new Scene(SCENE_VERSION, List.copyOf(result));
    }

    private Scene refreshStatus(Scene stored, String owner) {
        var result = new ArrayList<Item>(stored.elements().size());
        for (Item item : stored.elements()) {
            if ("PERSON_PART".equals(item.kind())) {
                var part = stickers.part(item.stickerId(), item.stickerVersion());
                result.add(new Item(null, null, "OTHER", item.x(), item.y(), item.width(), item.height(), item.rotation(), item.zIndex(),
                        part.label(), 0, part.url(), part.width(), part.height(), "PERSON_PART", null, false, false,
                        item.cropX(), item.cropY(), item.cropWidth(), item.cropHeight(), item.elementId(), "PERSON_PART", part.id(), part.version(), Boolean.TRUE.equals(item.flipX()), Boolean.TRUE.equals(item.freeStretch())));
                continue;
            }
            AssetRow current = jdbc.query("SELECT g.owner_username,g.closet_status,g.display_asset_id,g.revision,j.id,a.file_name,a.width,a.height,a.kind "
                            + "FROM garment g JOIN workflow_job j ON j.garment_id=g.id JOIN image_asset a ON a.job_id=j.id "
                            + "WHERE g.id=? AND a.id=? AND g.owner_username=?",
                    (rs, n) -> new AssetRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                            rs.getString(5), rs.getString(6), rs.getInt(7), rs.getInt(8), rs.getString(9)),
                    item.garmentId().toString(), item.assetId().toString(), owner).stream().findFirst()
                    .orElseThrow(() -> new ProcessingException(HttpStatus.NOT_FOUND, "OUTFIT_ASSET_NOT_FOUND", "搭配中的衣物图片已不可用，请重新选择。"));
            result.add(new Item(item.garmentId(), item.assetId(), item.role(), item.x(), item.y(), item.width(), item.height(),
                    item.rotation(), item.zIndex(), item.garmentLabel(), item.garmentRevision(), item.assetUrl(),
                    item.imageWidth(), item.imageHeight(), item.assetKind(), current.status(),
                    current.garmentRevision() != item.garmentRevision(),
                    current.garmentRevision() != item.garmentRevision() && !current.displayAssetId().equals(item.assetId().toString()),
                    item.cropX(), item.cropY(), item.cropWidth(), item.cropHeight(),
                    item.elementId() == null ? item.garmentId() : item.elementId(), "GARMENT", null, 0, Boolean.TRUE.equals(item.flipX()), Boolean.TRUE.equals(item.freeStretch())));
        }
        return new Scene(stored.version(), List.copyOf(result));
    }

    private void validateRequest(SaveRequest request, boolean create) {
        if (request == null || request.scene() == null || request.scene().elements() == null)
            throw ProcessingException.invalid("搭配需要名称和画板内容。");
        if (create && request.revision() != null || !create && request.revision() == null)
            throw ProcessingException.invalid("搭配版本信息不正确，请刷新后重试。");
        String name = request.name() == null ? "" : request.name().strip();
        if (name.isBlank() || name.length() > 100) throw ProcessingException.invalid("搭配名称需为 1 到 100 个字符。");
        SceneWrite scene = request.scene();
        if ((scene.version() != 1 && scene.version() != SCENE_VERSION) || scene.elements().isEmpty() || scene.elements().size() > 64)
            throw ProcessingException.invalid("画板需包含 1 到 64 张贴纸，最多 32 件衣物。");
        var garmentIds = new HashSet<UUID>();
        var elementIds = new HashSet<UUID>();
        var layers = new HashSet<Integer>();
        int garmentCount = 0;
        for (ItemWrite item : scene.elements()) {
            if (item == null) throw ProcessingException.invalid("贴纸内容不正确。");
            boolean person = "PERSON_PART".equals(item.kind());
            if (scene.version() == 2) {
                if (!(person || "GARMENT".equals(item.kind())) || item.elementId() == null || !elementIds.add(item.elementId()))
                    throw ProcessingException.invalid("贴纸类型或实例编号不正确。");
                if (item.zIndex() < 0 || item.zIndex() >= scene.elements().size() || !layers.add(item.zIndex()))
                    throw ProcessingException.invalid("贴纸图层顺序不正确。");
            } else if (item.kind() != null && !"GARMENT".equals(item.kind())) throw ProcessingException.invalid("旧版画板只支持衣物。");
            if (person) {
                if (item.garmentId() != null || item.assetId() != null) throw ProcessingException.invalid("人物贴纸不能关联衣物。");
                if (item.stickerVersion() == null) throw ProcessingException.invalid("人物素材版本不能为空。先重新选择素材。");
                stickers.part(item.stickerId(), item.stickerVersion());
            } else {
                if (item.garmentId() == null || item.assetId() == null || (scene.version() == 1 && !garmentIds.add(item.garmentId())))
                    throw ProcessingException.invalid("衣物资源或编号不正确。");
                if (++garmentCount > 32) throw ProcessingException.invalid("最多加入 32 件衣物。");
                if (item.stickerId() != null || (item.stickerVersion() != null && item.stickerVersion() != 0)) throw ProcessingException.invalid("衣物不能关联人物素材。");
            }
            if (item.role() == null || !ROLES.contains(item.role())) throw ProcessingException.invalid("衣物分组不正确。");
            if (!Double.isFinite(item.x()) || !Double.isFinite(item.y()) || !Double.isFinite(item.width())
                    || !Double.isFinite(item.height()) || !Double.isFinite(item.rotation())
                    || !Double.isFinite(item.cropX()) || !Double.isFinite(item.cropY())
                    || !Double.isFinite(item.cropWidth()) || !Double.isFinite(item.cropHeight())
                    || item.x() < 0 || item.x() > BOARD_WIDTH || item.y() < 0 || item.y() > BOARD_HEIGHT
                    || item.width() < 24 || item.width() > BOARD_WIDTH * 2
                    || item.height() < 24 || item.height() > BOARD_HEIGHT * 2
                    || item.cropX() < 0 || item.cropY() < 0 || item.cropWidth() <= 0 || item.cropHeight() <= 0
                    || item.cropX() + item.cropWidth() > 1.000001 || item.cropY() + item.cropHeight() > 1.000001
                    || Math.abs(item.rotation()) > 360
                    || item.garmentLabel() == null || item.garmentLabel().isBlank() || item.garmentLabel().length() > 120)
                throw ProcessingException.invalid("画板中的贴纸位置或尺寸不正确。");
        }
    }

    private static String cleanName(String name) {
        return name.strip();
    }

    private static ProcessingException conflict() {
        return new ProcessingException(HttpStatus.CONFLICT, "OUTFIT_VERSION_CONFLICT", "这套搭配已在另一处更新；你的修改尚未提交。请重新加载或另存副本。");
    }
}
