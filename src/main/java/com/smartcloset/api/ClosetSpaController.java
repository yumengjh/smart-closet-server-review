package com.smartcloset.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Keeps Vue Router history URLs working when the production bundle is served by Spring Boot. */
@Controller
public class ClosetSpaController {
    @GetMapping({"/quick-add", "/admin/processing", "/help", "/ootd", "/login", "/wardrobe", "/pending", "/tasks", "/settings", "/add", "/trash", "/garment/{id}", "/batches/{id}", "/outfits", "/outfits/{id}"})
    public String app() { return "forward:/index.html"; }
}
