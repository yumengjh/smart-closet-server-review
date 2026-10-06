package com.smartcloset.api;
import com.smartcloset.processing.*;
import com.smartcloset.security.ClosetIdentity;
import com.smartcloset.image.ProcessingException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
public class ProcessingController {
 @org.springframework.beans.factory.annotation.Value("${closet.quick-intake-enabled:false}") private boolean quickEnabled;
 @GetMapping("/api/processing/capabilities") public Object capabilities(){return Map.of("quickIntake",quickEnabled);}
 private final ProcessingStore store;private final ProcessingExecutor executor;private final com.smartcloset.intake.QuickIntakeService quick;
 public ProcessingController(ProcessingStore store,ProcessingExecutor executor,com.smartcloset.intake.QuickIntakeService quick){this.store=store;this.executor=executor;this.quick=quick;}
 private ProcessingStore.Task own(String id){var task=store.get(id);if(!Objects.equals(task.owner(),ClosetIdentity.username()))throw new ProcessingException(HttpStatus.NOT_FOUND,"TASK_NOT_FOUND","任务不存在。");return task;}
 @GetMapping("/api/processing/tasks") public Object tasks(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="100") int size){return Map.of("items",store.list(ClosetIdentity.username(),page,size));}
 @GetMapping("/api/processing/tasks/{id}") public Object task(@PathVariable String id){var task=own(id);return Map.of("task",task,"attempts",store.db().queryForList("SELECT attempt_no,status,dispatched,quota_date,quota_units,started_at_ms,finished_at_ms,usage_json,error_message FROM execution_attempt WHERE task_id=? ORDER BY attempt_no",id),"input",store.db().queryForList("SELECT dependency_key,input_version,payload_json,result_json FROM execution_task WHERE id=?",id));}
 @PostMapping("/api/processing/tasks/{id}/cancel") public Object cancel(@PathVariable String id){own(id);executor.cancel(id);return own(id);}
 @PostMapping("/api/processing/tasks/{id}/retry") public Object retry(@PathVariable String id){var task=own(id);if((!task.pool().equals("CPU")&&!quick.canReconcileResult(task))||!Set.of("FAILED","UNKNOWN").contains(task.status()))throw ProcessingException.invalid("仅支持安全的本地阶段恢复；云端请从衣物详情明确发起新任务。");executor.retry(id);return own(id);}
 @GetMapping("/api/admin/processing") public Object admin(@RequestParam(defaultValue="1") int page){return Map.of("resources",executor.view(),"items",store.list(null,page,100));}
 public record ConfigEdit(ProcessingStore.Config limits,long revision){}
 @PutMapping("/api/admin/processing/config") public Object config(@RequestBody ConfigEdit request){return store.setConfig(request.limits(),request.revision(),ClosetIdentity.username());}
 @PostMapping("/api/admin/processing/tasks/{id}/cancel") public Object adminCancel(@PathVariable String id){executor.cancel(id);return store.get(id);}
}
