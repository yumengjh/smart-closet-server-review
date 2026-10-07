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
    public record PreferenceEdit(String model,String avatarMode) {}
    public record MemoryEdit(String content,String category) {}
    public record Accept(JsonNode context,Boolean current) {}
    @org.springframework.beans.factory.annotation.Autowired private AgentMemoryDocuments documents;
    public record MemoryPermission(String purpose,long revision) {}
    @GetMapping("/memory-documents") public Object documents(){return documents.list(ClosetIdentity.username());}
    @PutMapping("/memory-documents/{kind}") public Object document(@PathVariable String kind,@RequestBody AgentMemoryDocuments.Edit edit){return documents.save(ClosetIdentity.username(),kind,edit);}
    @GetMapping("/memory-permissions") public Object permissions(){return documents.permissions(ClosetIdentity.username());}
    @PostMapping("/memory-permissions") public Object permission(@RequestBody MemoryPermission input){return documents.authorize(ClosetIdentity.username(),input.purpose(),input.revision());}
    @DeleteMapping("/memory-permissions/{purpose}") public ResponseEntity<Void> revoke(@PathVariable String purpose){documents.revoke(ClosetIdentity.username(),purpose);return ResponseEntity.noContent().build();}
    @org.springframework.beans.factory.annotation.Autowired private AgentCommands commands;
    @GetMapping("/commands/{id}") public Object command(@PathVariable String id){return commands.get(ClosetIdentity.username(),id);}
    @PostMapping("/commands/{id}/confirm") public Object confirmCommand(@PathVariable String id){return commands.execute(ClosetIdentity.username(),id);}
    @PostMapping("/commands/{id}/cancel") public Object cancelCommand(@PathVariable String id){return commands.cancel(ClosetIdentity.username(),id);}
    @PostMapping("/commands/{id}/undo") public Object undoCommand(@PathVariable String id){return commands.undo(ClosetIdentity.username(),id);}
    private final AgentRepository repo;
    private final AgentService service;
    private final HelpService help;
    private final AgentCanvasRuns canvasRuns;
    private final AgentTraceService traces;
    public AgentController(AgentRepository repo,AgentService service,HelpService help,AgentCanvasRuns canvasRuns,AgentTraceService traces){this.repo=repo;this.service=service;this.help=help;this.canvasRuns=canvasRuns;this.traces=traces;}
    @GetMapping("/models") public Object models(){return service.models();}
    @GetMapping("/quota") public Object quota(){return repo.quota(ClosetIdentity.username());}
    @GetMapping("/preferences") public Object preference(){String owner=ClosetIdentity.username();return Map.of("model",repo.preference(owner),"avatarMode",repo.avatarMode(owner));}
    @PutMapping("/preferences") public Object preference(@RequestBody PreferenceEdit edit){String owner=ClosetIdentity.username();if(edit.avatarMode()!=null&&!Set.of("static","basic","personal").contains(edit.avatarMode()))throw AgentRepository.invalid("助手动效模式无效。");if(edit.model()!=null)repo.preference(owner,edit.model());if(edit.avatarMode()!=null)repo.avatarMode(owner,edit.avatarMode());return preference();}
    @GetMapping("/sessions") public Object sessions(){return repo.sessions(ClosetIdentity.username());}
    @PostMapping("/sessions") public Object create(){return repo.create(ClosetIdentity.username());}
    @DeleteMapping("/sessions") public ResponseEntity<Void> clear(){repo.clear(ClosetIdentity.username());return ResponseEntity.noContent().build();}
    @PutMapping("/sessions/{id}") public Object update(@PathVariable String id,@RequestBody SessionEdit edit){return repo.update(ClosetIdentity.username(),id,edit.title(),edit.model());}
    @DeleteMapping("/sessions/{id}") public ResponseEntity<Void> delete(@PathVariable String id){repo.delete(ClosetIdentity.username(),id);return ResponseEntity.noContent().build();}
    @GetMapping("/sessions/{id}/messages") public Object messages(@PathVariable String id){return repo.messages(ClosetIdentity.username(),id);}
    @PostMapping(value="/sessions/{id}/messages",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> stream(@PathVariable String id,@RequestBody AgentService.Request request,jakarta.servlet.http.HttpServletResponse response) throws IOException {
        var body=service.stream(ClosetIdentity.username(),id,request);
        // Spring 7.0.6+ intentionally makes the response body stream non-flushing.
        // Flush the servlet response explicitly so commands arrive before awaiting their receipts.
        StreamingResponseBody flushed=output->body.writeTo(new java.io.FilterOutputStream(output){
            @Override public void write(byte[] bytes,int offset,int length)throws IOException{out.write(bytes,offset,length);}
            @Override public void flush()throws IOException{out.flush();response.flushBuffer();}
        });
        return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Accel-Buffering","no").contentType(MediaType.TEXT_EVENT_STREAM).body(flushed);
    }
    @GetMapping("/sessions/{session}/runs/{request}/trace") public Object trace(@PathVariable String session,@PathVariable String request,@RequestParam(defaultValue="0") long afterSeq,@RequestParam(defaultValue="100") int limit){return traces.list(ClosetIdentity.username(),session,request,afterSeq,limit);}
    @GetMapping("/sessions/{session}/runs/{request}/trace/{seq}") public Object traceDetail(@PathVariable String session,@PathVariable String request,@PathVariable long seq){return traces.detail(ClosetIdentity.username(),session,request,seq);}
    @PostMapping("/sessions/{session}/board-events") public Object boardEvent(@PathVariable String session,@RequestBody JsonNode input){return traces.boardEvent(ClosetIdentity.username(),session,input);}
    @GetMapping("/memories") public Object memories(){return repo.memories(ClosetIdentity.username());}
    @PostMapping("/memories") public Object memory(@RequestBody MemoryEdit edit){return repo.memory(ClosetIdentity.username(),null,edit.content(),edit.category());}
    @PutMapping("/memories/{id}") public Object memory(@PathVariable String id,@RequestBody MemoryEdit edit){return repo.memory(ClosetIdentity.username(),id,edit.content(),edit.category());}
    @DeleteMapping("/memories/{id}") public ResponseEntity<Void> deleteMemory(@PathVariable String id){repo.deleteMemory(ClosetIdentity.username(),id);return ResponseEntity.noContent().build();}
    @PostMapping("/proposals/{id}/accept") public Object accept(@PathVariable String id,@RequestBody Accept input){return service.accept(ClosetIdentity.username(),id,input.context(),Boolean.TRUE.equals(input.current()));}
    @PostMapping("/runs/{id}/ack") public ResponseEntity<Void> canvasAck(@PathVariable String id,@RequestBody AgentCanvasRuns.Ack ack){canvasRuns.ack(ClosetIdentity.username(),id,ack);return ResponseEntity.noContent().build();}
    @PostMapping("/runs/{id}/cancel") public ResponseEntity<Void> canvasCancel(@PathVariable String id){canvasRuns.cancel(ClosetIdentity.username(),id);return ResponseEntity.noContent().build();}
    @GetMapping("/help") public Object help(){return help.articles();}
    @PostMapping("/internal/permits") public Object permits(@RequestHeader(value="Authorization",defaultValue="") String authorization,@RequestBody JsonNode input){return service.permit(authorization.startsWith("Bearer ")?authorization.substring(7):"",input);}
    @PostMapping("/internal/tool") public Object tool(@RequestHeader(value="Authorization",defaultValue="") String authorization,@RequestBody AgentService.ToolRequest call) {
        return service.tool(authorization.startsWith("Bearer ")?authorization.substring(7):"",call);
    }
}
