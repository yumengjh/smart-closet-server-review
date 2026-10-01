package com.smartcloset.api;

import com.smartcloset.outfit.PersonStickerCatalog;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/person-stickers")
public class PersonStickerController {
    private final PersonStickerCatalog stickers;
    public PersonStickerController(PersonStickerCatalog stickers) { this.stickers = stickers; }
    @GetMapping public PersonStickerCatalog.Catalog catalog() { return stickers.catalog(); }
    @GetMapping("/v{version}/{set}/{part}.png")
    public ResponseEntity<Resource> image(@PathVariable int version, @PathVariable String set, @PathVariable String part) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(stickers.image(set + "/" + part, version));
    }
}
