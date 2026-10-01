package com.smartcloset.api;

import com.smartcloset.outfit.OutfitRepository;
import com.smartcloset.security.ClosetIdentity;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/outfits")
public class OutfitController {
    private final OutfitRepository outfits;

    public OutfitController(OutfitRepository outfits) {
        this.outfits = outfits;
    }

    @GetMapping
    public List<OutfitRepository.View> list() {
        return outfits.list(ClosetIdentity.username());
    }

    @GetMapping("/{id}")
    public OutfitRepository.View get(@PathVariable UUID id) {
        return outfits.get(id, ClosetIdentity.username());
    }

    @PostMapping
    public OutfitRepository.View create(@RequestBody OutfitRepository.SaveRequest request) {
        return outfits.create(request, ClosetIdentity.username());
    }

    @PutMapping("/{id}")
    public OutfitRepository.View update(@PathVariable UUID id, @RequestBody OutfitRepository.SaveRequest request) {
        return outfits.update(id, request, ClosetIdentity.username());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @RequestParam Long revision) {
        outfits.delete(id, revision, ClosetIdentity.username());
        return ResponseEntity.noContent().build();
    }
}
