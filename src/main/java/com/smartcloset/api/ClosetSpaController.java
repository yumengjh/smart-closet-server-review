package com.smartcloset.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Keeps Vue Router history URLs working when the production bundle is served by Spring Boot. */
@Controller
public class ClosetSpaController {
    @GetMapping({"/login", "/wardrobe", "/pending", "/tasks", "/settings", "/add", "/trash", "/garment/{id}"})
    public String app() { return "forward:/index.html"; }
}
