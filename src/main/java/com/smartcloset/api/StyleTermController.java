package com.smartcloset.api;

import com.smartcloset.image.ProcessingException;
import com.smartcloset.security.ClosetIdentity;
import com.smartcloset.semantic.StyleResolver;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Style words learned from chat (global vocabulary aid). Anyone signed in can read; only administrators can switch one off. */
@RestController
public class StyleTermController {
    public record TermEdit(Boolean disabled) {}
    private final StyleResolver styles;
    public StyleTermController(StyleResolver styles) { this.styles = styles; }

    @GetMapping("/api/style-terms") public List<StyleResolver.Term> list() { return styles.terms(); }

    @GetMapping("/api/style-terms/resolve") public StyleResolver.Resolution resolve(@RequestParam String word) {
        if (word.length() > 40) throw ProcessingException.invalid("风格词最多 40 字。");
        return styles.resolve(word);
    }

    @PutMapping("/api/style-terms/{term}") public Map<String, Object> edit(@PathVariable String term, @RequestBody TermEdit edit) {
        if (!ClosetIdentity.administrator()) throw new ProcessingException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED", "只有管理员可以停用已学会的风格词。");
        styles.setDisabled(term, edit != null && Boolean.TRUE.equals(edit.disabled()));
        return Map.of("term", StyleResolver.normalize(term), "disabled", edit != null && Boolean.TRUE.equals(edit.disabled()));
    }
}
