package com.smartcloset.api;

import com.smartcloset.image.StickerService;
import com.smartcloset.security.ClosetOwnership;
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
    private final ClosetOwnership ownership;
    public StickerController(StickerService stickers, ClosetOwnership ownership) { this.stickers = stickers; this.ownership = ownership; }

    @PostMapping
    public ResponseEntity<StickerService.Result> create(@PathVariable UUID id) throws IOException {
        ownership.segmentation(id);
        var result = stickers.create(id);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .location(URI.create("/api/segmentations/" + id + "/sticker")).body(result.result());
    }

    @GetMapping
    public StickerService.Result get(@PathVariable UUID id) throws IOException { ownership.segmentation(id); return stickers.get(id); }

    @GetMapping("/image")
    public ResponseEntity<FileSystemResource> image(@PathVariable UUID id) {
        ownership.segmentation(id);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(new FileSystemResource(stickers.image(id)));
    }
}
