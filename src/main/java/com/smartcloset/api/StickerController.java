package com.smartcloset.api;

import com.smartcloset.image.StickerService;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/segmentations/{id}/sticker")
public class StickerController {
    private final StickerService stickers;
    public StickerController(StickerService stickers) { this.stickers = stickers; }

    @PostMapping
    public ResponseEntity<StickerService.Result> create(@PathVariable UUID id) throws IOException {
        var result = stickers.create(id);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .location(URI.create("/api/segmentations/" + id + "/sticker")).body(result.result());
    }

    @GetMapping
    public StickerService.Result get(@PathVariable UUID id) throws IOException { return stickers.get(id); }

    @GetMapping("/image")
    public ResponseEntity<FileSystemResource> image(@PathVariable UUID id) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(new FileSystemResource(stickers.image(id)));
    }
}
