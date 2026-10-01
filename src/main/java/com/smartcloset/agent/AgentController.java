package com.smartcloset.agent;

import com.smartcloset.security.ClosetIdentity;
import java.io.IOException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
    public record SessionEdit(String title,String model) {}
    public record ModelEdit(String model) {}
    public record MemoryEdit(String content,String category) {}
    public record Accept(JsonNode context,Boolean current) {}
    private final AgentRepository repo;
    private final AgentService service;
    private final HelpService help;
    public AgentController(AgentRepository repo,AgentService service,HelpService help){this.repo=repo;this.service=service;this.help=help;}
    @GetMapping("/models") public Object models(){return service.models();}
    @GetMapping("/quota") public Object quota(){return repo.quota(ClosetIdentity.username());}
    @GetMapping("/preferences") public Object preference(){return Map.of("model",repo.preference(ClosetIdentity.username()));}
    @PutMapping("/preferences") public Object preference(@RequestBody ModelEdit edit){repo.preference(ClosetIdentity.username(),edit.model());return preference();}
    @GetMapping("/sessions") public Object sessions(){return repo.sessions(ClosetIdentity.username());}
    @PostMapping("/sessions") public Object create(){return repo.create(ClosetIdentity.username());}
    @DeleteMapping("/sessions") public ResponseEntity<Void> clear(){repo.clear(ClosetIdentity.username());return ResponseEntity.noContent().build();}
    @PutMapping("/sessions/{id}") public Object update(@PathVariable String id,@RequestBody SessionEdit edit){return repo.update(ClosetIdentity.username(),id,edit.title(),edit.model());}
    @DeleteMapping("/sessions/{id}") public ResponseEntity<Void> delete(@PathVariable String id){repo.delete(ClosetIdentity.username(),id);return ResponseEntity.noContent().build();}
    @GetMapping("/sessions/{id}/messages") public Object messages(@PathVariable String id){return repo.messages(ClosetIdentity.username(),id);}
    @PostMapping(value="/sessions/{id}/messages",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> stream(@PathVariable String id,@RequestBody AgentService.Request request) throws IOException {
        var body=service.stream(ClosetIdentity.username(),id,request);
        return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Accel-Buffering","no").contentType(MediaType.TEXT_EVENT_STREAM).body(body);
    }
    @GetMapping("/memories") public Object memories(){return repo.memories(ClosetIdentity.username());}
    @PostMapping("/memories") public Object memory(@RequestBody MemoryEdit edit){return repo.memory(ClosetIdentity.username(),null,edit.content(),edit.category());}
    @PutMapping("/memories/{id}") public Object memory(@PathVariable String id,@RequestBody MemoryEdit edit){return repo.memory(ClosetIdentity.username(),id,edit.content(),edit.category());}
    @DeleteMapping("/memories/{id}") public ResponseEntity<Void> deleteMemory(@PathVariable String id){repo.deleteMemory(ClosetIdentity.username(),id);return ResponseEntity.noContent().build();}
    @PostMapping("/proposals/{id}/accept") public Object accept(@PathVariable String id,@RequestBody Accept input){return service.accept(ClosetIdentity.username(),id,input.context(),Boolean.TRUE.equals(input.current()));}
    @GetMapping("/help") public Object help(){return help.articles();}
    @PostMapping("/internal/tool") public Object tool(@RequestHeader(value="Authorization",defaultValue="") String authorization,@RequestBody AgentService.ToolRequest call) {
        return service.tool(authorization.startsWith("Bearer ")?authorization.substring(7):"",call);
    }
}
