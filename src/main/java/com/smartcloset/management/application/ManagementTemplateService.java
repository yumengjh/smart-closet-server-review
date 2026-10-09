package com.smartcloset.management.application;

import com.smartcloset.image.ProcessingException;
import com.smartcloset.management.ConditionalOnManagementEnabled;
import com.smartcloset.management.ManagementException;
import com.smartcloset.recommend.OutfitTemplates;
import com.smartcloset.recommend.TemplateCatalog;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Outfit template review for a management platform (design 管理接口二期 §8, M10): same storage as the in-app review page. */
@Service
@ConditionalOnManagementEnabled
public class ManagementTemplateService {
    private final TemplateCatalog catalog;
    private final ManagementOperationLog log;
    private final JdbcTemplate jdbc;
    public ManagementTemplateService(TemplateCatalog catalog, ManagementOperationLog log, JdbcTemplate jdbc) { this.catalog = catalog; this.log = log; this.jdbc = jdbc; }

    public Map<String, Object> list() {
        var t = catalog.current();
        var custom = new HashSet<>(jdbc.queryForList("SELECT template_id FROM outfit_template_custom", String.class));
        var items = t.entries().stream().map(e -> view(e, custom)).toList();
        return Map.of("version", t.version(), "maxBonus", OutfitTemplates.MAX_BONUS, "active", t.active().size(), "items", items);
    }

    public Map<String, Object> review(String id, String status, Boolean enabled, Double bonus) {
        var principal = ManagementOperationLog.current();
        String by = ("manage:" + (principal == null ? "unknown" : principal.keyId()) + (principal == null || principal.operator() == null ? "" : ":" + principal.operator()));
        by = by.substring(0, Math.min(80, by.length()));
        try {
            var entry = catalog.review(id, status, enabled, bonus, by);
            log.record("TEMPLATE_REVIEW", null, "SUCCESS", "template=" + id + " status=" + entry.reviewStatus() + " enabled=" + entry.enabled() + " bonus=" + entry.bonus());
            return view(entry, new HashSet<>(jdbc.queryForList("SELECT template_id FROM outfit_template_custom", String.class)));
        } catch (ProcessingException e) {
            log.record("TEMPLATE_REVIEW", null, "FAILURE", "template=" + id + " " + e.code());
            if (e.status() == HttpStatus.NOT_FOUND) throw new ManagementException(HttpStatus.NOT_FOUND, "TEMPLATE_NOT_FOUND", "模板不存在", "没有找到模板 " + id + "。");
            throw ManagementException.invalid(e.getMessage());
        }
    }

    private static Map<String, Object> view(OutfitTemplates.Entry e, Set<String> custom) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", e.id()); m.put("text", e.text()); m.put("source", custom.contains(e.id()) ? "SAMPLE" : "FILE");
        m.put("reviewStatus", e.reviewStatus()); m.put("enabled", e.enabled()); m.put("active", e.active());
        m.put("bonus", e.bonus()); m.put("defaultBonus", e.defaultBonus()); m.put("reviewedBy", e.reviewedBy()); m.put("reviewedAtMs", e.reviewedAtMs());
        m.put("kind", e.meta().kind()); m.put("audience", e.meta().audience()); m.put("seasons", e.meta().seasons()); m.put("occasions", e.meta().occasions());
        m.put("validFrom", e.meta().validFrom() == null ? null : e.meta().validFrom().toString()); m.put("validUntil", e.meta().validUntil() == null ? null : e.meta().validUntil().toString());
        m.put("reference", e.meta().reference()); m.put("slots", e.definition());
        return m;
    }
}
