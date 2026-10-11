package com.smartcloset.api;
import com.smartcloset.intake.QuickIntakeService;
import java.util.*;
import java.io.IOException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;
@RestController
@RequestMapping("/api/closet/quick-intakes")
public class QuickIntakeController {
 @org.springframework.beans.factory.annotation.Value("${closet.quick-intake-enabled:true}") private boolean enabled;
 private final QuickIntakeService service;private final ObjectMapper json;
 public QuickIntakeController(QuickIntakeService service,ObjectMapper json){this.service=service;this.json=json;}
 @PostMapping public Object create(@RequestHeader("Idempotency-Key") UUID key,@RequestHeader(value="X-Upload-Owner",required=false) String uploadOwner,@RequestPart("file") MultipartFile file,@RequestPart(value="options",required=false) String options)throws IOException{if(!enabled)throw new com.smartcloset.image.ProcessingException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"QUICK_INTAKE_DISABLED","快速入柜尚未开放，请使用其他整理工具。");if(uploadOwner!=null&&!uploadOwner.equals(com.smartcloset.security.ClosetIdentity.username()))throw com.smartcloset.image.ProcessingException.invalid("上传账户已切换，请返回原账户继续。");return service.create(key,parseOptions(options),file.getBytes());}
 @GetMapping("/{id}") public Object get(@PathVariable UUID id){return service.get(id);}
 private QuickIntakeService.Options parseOptions(String options){if(options==null)return null;try{return json.readValue(options,QuickIntakeService.Options.class);}catch(Exception e){throw com.smartcloset.image.ProcessingException.invalid("录入参数格式无效，请刷新后重试。");}}
 public record HintEdit(QuickIntakeService.Hint targetHint,long revision){}
 @PutMapping("/{id}/target-hint") public Object hint(@PathVariable UUID id,@RequestBody HintEdit edit){return service.hint(id,edit.targetHint(),edit.revision());}
 @PostMapping("/{id}/start") public Object start(@PathVariable UUID id){return service.start(id);}
 @PostMapping("/{id}/enhance") public Object enhance(@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@RequestBody(required=false) QuickIntakeService.Enhance request)throws IOException{return service.enhance(id,key,request);}
 @GetMapping("/garment/{id}") public Object provenance(@PathVariable UUID id){return service.provenance(id);}
}
