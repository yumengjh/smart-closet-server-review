package com.smartcloset.api;

import com.smartcloset.garment.GarmentRepository;
import com.smartcloset.image.ProcessingException;
import com.smartcloset.image.SegmentationEngine;
import com.smartcloset.security.ClosetIdentity;
import com.smartcloset.semantic.GarmentSearchService;
import com.smartcloset.similar.ImageFingerprint;
import com.smartcloset.similar.SimilarGarments;
import java.io.IOException;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 超级搜索 (design 模型内存与图像向量 §9, G15/G16): one search box for words, a picture, or both. Words alone are the
 * hybrid wardrobe search (now also matching the pictures themselves); a picture ranks the closet by look, and words next
 * to it filter by colour/category/position and re-rank by description. Same response shape as the text search.
 */
@RestController
public class SuperSearchController {
    static final int PAGE_SIZE = GarmentSearchService.PAGE_SIZE, MAX_RESULTS = 60;
    private final GarmentSearchService search;
    private final SimilarGarments similar;
    private final GarmentRepository garments;
    private final List<SegmentationEngine> engines;

    public SuperSearchController(GarmentSearchService search, SimilarGarments similar, GarmentRepository garments, List<SegmentationEngine> engines) {
        this.search = search; this.similar = similar; this.garments = garments; this.engines = engines;
    }

    @PostMapping(value = "/api/garments/search", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> search(@RequestPart(value = "image", required = false) MultipartFile image, @RequestParam(defaultValue = "") String q,
                                      @RequestParam(value = "role", required = false) String role, @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(required = false) String imageStyle) throws IOException {
        String owner = ClosetIdentity.username();
        if (image == null || image.isEmpty()) return search.page(owner, q, page, imageStyle);
        if (page < 1) throw ProcessingException.invalid("page 从 1 开始。");
        var picture = QueryPicture.prepare(image, engines);
        var vector = QueryPicture.vector(picture, similar.images().models());
        var refine = q.isBlank() ? null : search.refine(owner, q);
        // Words with a colour/category condition decide what is eligible, the picture only orders it.
        boolean filtered = refine != null && refine.allowed() != null;
        var found = similar.searchByImage(owner, ImageFingerprint.of(picture.garment()), vector, QueryPicture.role(role), filtered ? 500 : MAX_RESULTS, filtered ? 0 : 0.5);
        var hits = new ArrayList<Map<String, Object>>();
        for (var item : found) {
            if (refine != null && refine.allowed() != null && !refine.allowed().contains(item.id())) continue;
            double score = item.score();
            var reasons = new ArrayList<>(item.reasons());
            if (refine != null && !refine.parsed().freeText().isBlank()) {
                // Words re-rank within what looks alike: picture match counts most, the description a little.
                double text = Math.max(clamp((refine.pictures().getOrDefault(item.id(), 0.0) - 0.15) / 0.25), clamp((refine.descriptions().getOrDefault(item.id(), 0.0) - 0.30) / 0.50));
                score = 0.75 * score + 0.25 * text;
                Double pic = refine.pictures().get(item.id());
                if (pic != null && pic >= refine.pictureThreshold()) reasons.add("图片像“" + refine.parsed().freeText() + "” " + String.format(Locale.ROOT, "%.2f", pic));
            }
            var hit = new LinkedHashMap<String, Object>();
            hit.put("id", item.id()); hit.put("score", Math.round(score * 1000) / 1000.0); hit.put("level", item.level()); hit.put("reasons", reasons);
            hits.add(hit);
        }
        hits.sort(Comparator.comparingDouble((Map<String, Object> h) -> -(double) h.get("score")));
        int from = Math.min(hits.size(), (page - 1) * PAGE_SIZE), to = Math.min(hits.size(), from + PAGE_SIZE);
        var items = hits.subList(from, to).stream().map(h -> {
            var view = new LinkedHashMap<String, Object>(h); view.put("garment", garments.get(UUID.fromString((String) h.get("id")))); return (Map<String, Object>) view;
        }).toList();
        var status = similar.images().status(owner);
        // The model state is reported in imageModel (the front end words it); notice is only about this result.
        String notice = refine != null && hits.isEmpty() && !found.isEmpty() ? "和图片相近的衣物里，没有符合“" + q.strip() + "”的" : "";
        var out = new LinkedHashMap<String, Object>();
        out.put("query", q.strip()); out.put("mode", refine == null ? "IMAGE" : "IMAGE_TEXT"); out.put("items", items); out.put("total", hits.size()); out.put("page", page);
        out.put("hasMore", to < hits.size()); out.put("parsed", refine == null ? null : refine.parsed().view()); out.put("imageStyle", null);
        out.put("emptyHint", hits.isEmpty() ? "衣柜里没有和这张图足够像的衣物。" : ""); out.put("notice", notice); out.put("matting", picture.method());
        out.put("note", vector == null ? SimilarGarmentController.SEARCH_NOTE : SimilarGarmentController.MODEL_NOTE); out.put("imageModel", status);
        return out;
    }

    private static double clamp(double v) { return Math.max(0, Math.min(1, v)); }
}
