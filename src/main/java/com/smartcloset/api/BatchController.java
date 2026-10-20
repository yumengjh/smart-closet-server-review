package com.smartcloset.api;

import com.smartcloset.batch.*;
import java.io.IOException;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;

@RestController
@RequestMapping("/api/closet/batches")
public class BatchController {
    private final BatchService batches;
    private final com.smartcloset.storage.StoredMedia media;
    public BatchController(BatchService batches,com.smartcloset.storage.StoredMedia media) { this.batches=batches;this.media=media; }
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Batch.View> upload(@RequestHeader("Idempotency-Key") UUID key,@RequestPart("options") Batch.Upload options,@RequestPart("file") MultipartFile file) throws IOException {
        return ResponseEntity.accepted().body(batches.upload(key,options,file));
    }
    @PostMapping(value="/{id}/sources",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Batch.View source(@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@RequestPart("file") MultipartFile file) throws IOException {return batches.addSource(id,key,file);}
    @GetMapping public List<Batch.View> history() { return batches.history(); }
    @GetMapping("/garments/{garmentId}/source") public Map<String,Object> provenance(@PathVariable UUID garmentId) {return batches.provenance(garmentId);}
    @GetMapping("/{id}") public Batch.View get(@PathVariable UUID id) {return batches.get(id);}
    @PostMapping("/{id}/recognize") public ResponseEntity<Batch.View> detect(@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@RequestBody Batch.Detect request) {return ResponseEntity.accepted().body(batches.detect(id,key,request));}
    @PostMapping("/{id}/start") public ResponseEntity<Batch.View> start(@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@RequestBody Batch.Start request) {return ResponseEntity.accepted().body(batches.start(id,key,request));}
    @PostMapping("/{id}/generate") public ResponseEntity<Batch.View> generate(@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@RequestBody Batch.Generate request) {return ResponseEntity.accepted().body(batches.generate(id,key,request));}
    @PostMapping("/{id}/calls/{callId}/resume-local") public Batch.View resumeLocal(@PathVariable UUID id,@PathVariable UUID callId) throws IOException {return batches.resumeLocal(id,callId);}
    @GetMapping("/{id}/files/{name}") public ResponseEntity<?> file(@PathVariable UUID id,@PathVariable String name,@RequestParam(required=false) Integer w) throws IOException {
        return media.shared(batches.file(id,name),w);
    }
}
