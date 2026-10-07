package com.smartcloset.api;
import java.util.*;
import java.io.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import com.smartcloset.person.*;
import com.smartcloset.security.ClosetIdentity;
import tools.jackson.databind.ObjectMapper;
@RestController
public class PersonalRenderController {
    private final WearerProfiles profiles;private final PrivateMedia media;private final PersonalRenders renders;private final RenderCapabilities capabilities;private final ObjectMapper json;
    public PersonalRenderController(WearerProfiles profiles,PrivateMedia media,PersonalRenders renders,RenderCapabilities capabilities,ObjectMapper json){this.profiles=profiles;this.media=media;this.renders=renders;this.capabilities=capabilities;this.json=json;}
    @GetMapping("/api/me/wearer-profile") public Object profile(){return profiles.get(ClosetIdentity.username());}
    @PutMapping("/api/me/wearer-profile") public Object profile(@RequestBody WearerProfiles.Profile request){return profiles.update(ClosetIdentity.username(),request);}
    @GetMapping("/api/me/wearer-profile/render-preview") public Object preview(){return WearerProfiles.snapshot(profiles.get(ClosetIdentity.username()));}
    @GetMapping("/api/me/person-baselines") public Object baselines(){return renders.baselines(ClosetIdentity.username());}
    @GetMapping("/api/me/person-baselines/{id}") public Object baseline(@PathVariable String id){return renders.baseline(ClosetIdentity.username(),id);}
    @PostMapping("/api/me/person-baselines") public Object generate(@RequestHeader("Idempotency-Key") String key,@RequestBody PersonalRenders.BaselineRequest request){return renders.generateBaseline(ClosetIdentity.username(),key,request);}
    @PostMapping(value="/api/me/person-baselines/upload-and-generate",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public Object uploadGenerate(@RequestHeader("Idempotency-Key") String key,@RequestPart("file") MultipartFile file,@RequestPart("request") PersonalRenders.BaselineRequest request)throws IOException {
        return renders.uploadAndGenerate(ClosetIdentity.username(),key,request,file.getBytes());
    }
    public record Submission(boolean found,PersonalRenders.Job job) {}
    @GetMapping("/api/me/image-render-submissions/{key}") public ResponseEntity<Submission> submission(@PathVariable String key){var job=renders.existingJob(ClosetIdentity.username(),key);return ResponseEntity.ok().header("Cache-Control","no-store").body(new Submission(job!=null,job));}
    public record Revision(long revision) {}
    @PostMapping("/api/me/person-baselines/{id}/confirm") public Object confirm(@PathVariable String id,@RequestBody Revision request){return renders.activate(ClosetIdentity.username(),id,request.revision());}
    @DeleteMapping("/api/me/person-baselines/{id}") public ResponseEntity<Void> deleteBaseline(@PathVariable String id,@RequestBody Revision request){renders.deleteBaseline(ClosetIdentity.username(),id,request.revision());return ResponseEntity.noContent().build();}
    @GetMapping("/api/me/reference-photos") public Object photos(){return media.list(ClosetIdentity.username(),"PHOTO");}
    @GetMapping("/api/me/private-media/{id}") public ResponseEntity<byte[]> image(@PathVariable UUID id)throws IOException{return ResponseEntity.ok().header("Cache-Control","private, no-store, max-age=0").header("Pragma","no-cache").header("X-Content-Type-Options","nosniff").contentType(MediaType.IMAGE_PNG).body(media.read(ClosetIdentity.username(),id));}
    @DeleteMapping("/api/me/reference-photos/{id}") public Object deletePhoto(@PathVariable UUID id){var m=media.get(ClosetIdentity.username(),id);if(!m.purpose().equals("PHOTO"))throw com.smartcloset.image.ProcessingException.invalid("请选择全身照；人物和 OOTD 从各自页面删除。");return media.delete(ClosetIdentity.username(),id);}
    @GetMapping("/api/render-capabilities") public Object capabilities(){return capabilities.list();}
    @PutMapping("/api/admin/render-capabilities") public Object capability(@RequestBody RenderCapabilities.Capability request){return capabilities.update(request);}
    @PostMapping("/api/ootd/preparations") public Object preparation(@RequestBody PersonalRenders.PreparationRequest request)throws IOException{return renders.prepare(ClosetIdentity.username(),request);}
    public record Submit(String preparationId) {}
    @PostMapping("/api/ootd/jobs") public Object submit(@RequestHeader("Idempotency-Key") String key,@RequestBody Submit request){return renders.submitOotd(ClosetIdentity.username(),key,request.preparationId());}
    @GetMapping("/api/me/image-render-jobs") public Object renderJobs(){renders.sync();String owner=ClosetIdentity.username();return java.util.stream.Stream.concat(renders.jobs(owner,"BASELINE").stream(),renders.jobs(owner,"OOTD").stream()).sorted(java.util.Comparator.comparingLong(PersonalRenders.Job::createdAt).reversed()).toList();}
    @PostMapping("/api/me/image-render-jobs/{id}/cancel") public Object cancelRender(@PathVariable String id){renders.cancel(ClosetIdentity.username(),id);return renders.job(ClosetIdentity.username(),id);}
    @GetMapping("/api/ootd/jobs") public Object jobs(){renders.sync();return renders.jobs(ClosetIdentity.username(),"OOTD");}
    private PersonalRenders.Job ootd(String id){var j=renders.job(ClosetIdentity.username(),id);if(!j.purpose().equals("OOTD"))throw com.smartcloset.image.ProcessingException.invalid("请从基准人物页面管理此任务。");return j;}
    @GetMapping("/api/ootd/jobs/{id}") public Object job(@PathVariable String id){renders.sync();return ootd(id);}
    @PostMapping("/api/ootd/jobs/{id}/cancel") public Object cancel(@PathVariable String id){ootd(id);renders.cancel(ClosetIdentity.username(),id);return renders.job(ClosetIdentity.username(),id);}
    @DeleteMapping("/api/ootd/jobs/{id}") public Object delete(@PathVariable String id){ootd(id);return renders.delete(ClosetIdentity.username(),id);}
    public record Feedback(String content) {}
    @PutMapping("/api/ootd/jobs/{id}/feedback") public Object feedback(@PathVariable String id,@RequestBody Feedback request){ootd(id);return renders.feedback(ClosetIdentity.username(),id,request.content());}
}
