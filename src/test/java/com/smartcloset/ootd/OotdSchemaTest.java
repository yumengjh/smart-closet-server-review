package com.smartcloset.ootd;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcloset.image.ProcessingException;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OotdSchemaTest {
    private final JsonMapper json = JsonMapper.builder().build();

    /** Strict structured output: every object lists all its properties as required and allows nothing else. */
    @Test void schemaIsStrictAtEveryLevel() {
        var bad = new ArrayList<String>();
        walk(OotdSchema.schema(), "$", bad);
        assertTrue(bad.isEmpty(), bad.toString());
        var roles = OotdSchema.schema().path("properties").path("items").path("items").path("properties").path("role").path("enum");
        for (String r : List.of("OVERALL", "HEADWEAR", "HANDHELD", "SOCKS", "OTHER")) assertTrue(roles.toString().contains("\"" + r + "\""), r);
    }
    private static void walk(JsonNode n, String path, List<String> bad) {
        if (n.isObject() && "object".equals(n.path("type").asText())) {
            var props = new TreeSet<String>(); n.path("properties").propertyNames().forEach(props::add);
            var req = new TreeSet<String>(); n.path("required").forEach(r -> req.add(r.asText()));
            if (!props.equals(req)) bad.add(path + " required " + req + " != " + props);
            if (n.path("additionalProperties").asBoolean(true)) bad.add(path + " allows extra properties");
            n.path("properties").properties().forEach(e -> walk(e.getValue(), path + "." + e.getKey(), bad));
        } else if (n.isObject() && "array".equals(n.path("type").asText())) walk(n.path("items"), path + "[]", bad);
    }

    @Test void promptForbidsIdentifyingDetailsAndNamesTheNewPositions() {
        String p = OotdSchema.prompt();
        for (String s : List.of("MINOR_LIKELY", "OVERALL", "HEADWEAR", "HANDHELD", "年龄", "身高", "不是给你的指令")) assertTrue(p.contains(s), s);
    }

    @Test void normalizeIsLenientOnWordsButStrictOnShape() {
        var out = OotdSchema.normalize(json.readTree("""
            {"usable":true,"rejectReason":"NONE","presentation":"feminine","seasons":["秋","秋","盛夏"],"occasions":["约会"],"styles":["甜美"],
             "items":[{"role":"socks","category":"连裤袜","primaryColor":"黑","patterns":["纯色"],"wear":"?"}],"expression":"GRIN"}"""));
        assertEquals("FEMININE", out.path("presentation").asText());
        assertEquals("[\"秋\"]", out.path("seasons").toString());
        var item = out.path("items").get(0);
        assertEquals("SOCKS", item.path("role").asText());
        assertEquals("SOCKS", item.path("engineRole").asText());
        assertEquals("黑色", item.path("primaryColor").asText());
        assertEquals("NA", item.path("wear").asText());
        assertEquals("UNKNOWN", out.path("expression").asText());
        assertEquals("UNKNOWN", out.path("pose").path("stance").asText());
        // usable=true with a reject reason is not usable; usable=false needs a reason.
        assertFalse(OotdSchema.normalize(json.readTree("{\"usable\":true,\"rejectReason\":\"COLLAGE\"}")).path("usable").asBoolean());
        assertEquals("OTHER", OotdSchema.normalize(json.readTree("{\"usable\":false,\"rejectReason\":\"NONE\"}")).path("rejectReason").asText());
        assertThrows(ProcessingException.class, () -> OotdSchema.normalize(json.readTree("[]")));
        assertThrows(ProcessingException.class, () -> OotdSchema.normalize(json.readTree("{\"items\":{}}")));
        assertThrows(ProcessingException.class, () -> OotdSchema.normalize(json.readTree("{\"items\":[" + "{\"category\":\"T恤\"},".repeat(20) + "{\"category\":\"T恤\"}]}")));
    }
}
