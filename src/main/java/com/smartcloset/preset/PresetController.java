package com.smartcloset.preset;

import java.io.IOException;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class PresetController {
    private final PresetGarments presets;
    public PresetController(PresetGarments presets) { this.presets=presets; }
    @PostMapping("/admin/presets/import") public Object drafts(@RequestBody PresetGarments.AdminImport request) throws IOException { return presets.importDrafts(request); }
    @GetMapping("/admin/presets") public Object adminList(@RequestParam(required=false) String status) { return presets.listAdmin(status); }
    @PatchMapping("/admin/presets/{id}") public Object edit(@PathVariable UUID id,@RequestBody PresetGarments.Edit request) { return presets.edit(id,request); }
    @GetMapping("/admin/presets/coverage") public Object coverage() { return presets.coverage(); }
    @GetMapping("/presets") public Object list(@RequestParam(required=false) String gender,@RequestParam(required=false) List<String> styles,@RequestParam(required=false) String season) { return presets.list(gender,styles,season); }
    @PostMapping("/presets/import") public Object importIntoCloset(@RequestBody PresetGarments.Import request) { return presets.importIntoCloset(request); }
}
