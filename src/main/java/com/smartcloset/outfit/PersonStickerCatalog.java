package com.smartcloset.outfit;

import com.smartcloset.image.ProcessingException;
import java.io.IOException;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class PersonStickerCatalog {
    public record Part(String id, String label, int version, int width, int height, String url, boolean loose) {}
    public record SetView(String id, String label, int version, List<Part> parts) {}
    public record Catalog(int version, List<SetView> sets) {}
    private final Catalog catalog;
    public PersonStickerCatalog(ObjectMapper json) throws IOException {
        try (var input = new ClassPathResource("person-stickers/catalog.json").getInputStream()) {
            catalog = json.readValue(input, Catalog.class);
        }
    }
    public Catalog catalog() { return catalog; }
    public Part part(String id, int version) {
        return catalog.sets().stream().flatMap(set -> set.parts().stream())
                .filter(part -> part.id().equals(id) && part.version() == version).findFirst()
                .orElseThrow(() -> new ProcessingException(HttpStatus.BAD_REQUEST, "PERSON_STICKER_UNKNOWN", "人物贴纸或素材版本不存在，请重新选择。"));
    }
    public Resource image(String id, int version) {
        Part part = part(id, version);
        return new ClassPathResource("person-stickers/v" + part.version() + "/" + part.id() + ".png");
    }
}
