package com.smartcloset.agent;

import com.smartcloset.image.ProcessingException;
import com.smartcloset.outfit.OutfitRepository;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Ephemeral execution grants. Persistent outfits are only written through the existing save API. */
@Service
public class AgentCanvasRuns {
    public record Ack(String operationId, int sequence, boolean success, Integer version, JsonNode scene, Integer boardCount, String message) {}
    private static final class Run {
        final String id = UUID.randomUUID().toString();
        final String owner, session, workspace;
        final BiConsumer<String,Object> emit;
        final long deadline;
        final List<String> boardIds = new ArrayList<>();
        final Map<String,Integer> versions = new HashMap<>();
        final Map<String,Set<String>> garments = new HashMap<>();
        final Map<String,JsonNode> scenes = new HashMap<>();
        final Map<String,Long> garmentVersions = new HashMap<>();
        boolean edit;
        volatile boolean cancelled;
        volatile String failureMessage;
        volatile Command pending;
        int sequence;
        Run(String owner,String session,BiConsumer<String,Object> emit,long deadline) { this.owner=owner;this.session=session;this.workspace="agent:"+session;this.emit=emit;this.deadline=deadline; }
    }
    private static final class Command {
        final String id=UUID.randomUUID().toString(), board, kind, label;
        final int sequence, expected;
        JsonNode payload;
        final CompletableFuture<Ack> result=new CompletableFuture<>();
        Command(String board,String kind,String label,int sequence,int expected){this.board=board;this.kind=kind;this.label=label;this.sequence=sequence;this.expected=expected;}
    }
    private final ConcurrentHashMap<String,Run> runs=new ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired private com.smartcloset.garment.GarmentRepository garmentRepository;
    private final OutfitRepository outfits;
    private final ObjectMapper json;
    private final AgentTraceService traces;
    private final AgentProperties config;
    public AgentCanvasRuns(OutfitRepository outfits,ObjectMapper json,AgentProperties config,AgentTraceService traces){this.outfits=outfits;this.json=json;this.config=config;this.traces=traces;}

    public Object build(String owner,String session,JsonNode source,JsonNode args,boolean edit,BiConsumer<String,Object> emit) {
        var run=new Run(owner,session,emit,System.currentTimeMillis()+config.timeoutSeconds()*1000L);run.edit=edit;
        if(runs.values().stream().anyMatch(r->r.owner.equals(owner)&&r.workspace.equals(run.workspace)&&!r.cancelled))throw AgentRepository.busy();
        for(var item:garmentRepository.projection(owner))if(item.status().equals("ACTIVE"))run.garmentVersions.put(item.id().toString(),item.revision());
        runs.put(run.id,run);
        String status="FAILED",message="搭台操作未完成，已执行的内容仍保留为本机草稿。";
        try {
            boolean reuse=edit&&run.workspace.equals(source.path("workspaceId").asText())&&!source.path("boardId").asText().isBlank();
            var opening=new LinkedHashMap<String,Object>(); opening.put("requiredBoards",reuse?0:edit?1:args.path("candidates").size());
            if(edit)opening.put("source",source);
            command(run,"","open","正在打开临时搭台",opening);
            if(edit) {
                String board=reuse?source.path("boardId").asText():UUID.randomUUID().toString();
                run.boardIds.add(board);run.versions.put(board,reuse?source.path("contentVersion").asInt():0);
                run.garments.put(board,garmentIds(source.path("scene")));run.scenes.put(board,source.path("scene"));
                if(reuse)command(run,board,"begin","正在接管临时画板",Map.of());
                else {
                    var payload=new LinkedHashMap<String,Object>();payload.put("name",source.path("name").asText("搭配调整"));
                    payload.put("scene",outfits.previewSnapshot(json.treeToValue(source.path("scene"),OutfitRepository.SceneWrite.class),owner));
                    String original=source.path("outfitId").asText("");if(!original.isBlank()&&!original.equals("new")){payload.put("sourceOutfitId",original);payload.put("sourceRevision",source.path("revision"));}
                    command(run,board,"create","正在复制当前搭配到临时画板",payload);
                }
                for(var op:args.path("operations")) {
                    String kind=op.path("op").asText();String garment=op.path("garmentId").asText();
                    if(kind.equals("add"))run.garments.get(board).add(garment);
                    if(kind.equals("remove")||kind.equals("replace")) {
                        var remaining=new HashSet<String>();boolean found=false;
                        for(var item:run.scenes.get(board).path("elements")) {
                            if(item.path("elementId").asText().equals(op.path("elementId").asText()))found=true;
                            else if(!"PERSON_PART".equals(item.path("kind").asText()))remaining.add(item.path("garmentId").asText());
                        }
                        if(!found)throw AgentRepository.invalid("目标贴纸已变化。");
                        if(kind.equals("replace"))remaining.add(garment);
                        run.garments.put(board,remaining);
                    }
                    command(run,board,kind,"正在"+switch(kind){case "add"->"添加衣物";case "replace"->"替换衣物";case "remove"->"移除贴纸";case "adjust"->"微调衣物";default->"整理穿搭比例";},op);
                }
                command(run,board,"finish","这套调整已完成，等待你确认",Map.of());
            } else for(var candidate:args.path("candidates")) {
                String board=UUID.randomUUID().toString();run.boardIds.add(board);run.versions.put(board,0);run.garments.put(board,new HashSet<>());
                command(run,board,"create","正在创建「"+candidate.path("name").asText()+"」",Map.of("name",candidate.path("name").asText(),"reason",candidate.path("reason").asText()));
                for(var id:candidate.path("garmentIds")){run.garments.get(board).add(id.asText());command(run,board,"add","正在添加搭配衣物",Map.of("garmentId",id.asText()));}
                command(run,board,"layout","正在对齐上下装并整理叠穿比例",Map.of());
                command(run,board,"finish","「"+candidate.path("name").asText()+"」已搭好",Map.of());
            }
            command(run,"","complete","临时搭台已完成",Map.of());status="COMPLETE";message="搭配已经实际放上临时搭台，尚未保存到搭配库。";
        } catch(Exception e) { if(run.failureMessage!=null)message=run.failureMessage+" 已完成的内容保留为草稿。";else if(run.cancelled)message="操作已停止，已完成的内容保留为草稿。"; }
        finally {
            run.cancelled=true;runs.remove(run.id);
            run.emit.accept("workspace_result",Map.of("runId",run.id,"workspaceId",run.workspace,"sessionId",session,"boardIds",List.copyOf(run.boardIds),"status",status,"message",message));
        }
        var result=new LinkedHashMap<String,Object>();result.put("status",status);result.put("message",message);result.put("workspaceId",run.workspace);result.put("boardIds",run.boardIds);
        if(status.equals("COMPLETE")&&!run.boardIds.isEmpty()){String last=run.boardIds.getLast();result.put("latestContext",Map.of("route","/outfits/workspace","workspaceId",run.workspace,"boardId",last,"contentVersion",run.versions.getOrDefault(last,0),"scene",run.scenes.get(last),"selectedIds",List.of()));}
        return result;
    }

    private Ack command(Run run,String board,String kind,String label,Object payload) throws Exception {
        if(run.cancelled||System.currentTimeMillis()>run.deadline)throw new CancellationException();
        int version=run.versions.getOrDefault(board,0);
        var command=new Command(board,kind,label,++run.sequence,version);command.payload=json.valueToTree(payload);run.pending=command;
        var event=new LinkedHashMap<String,Object>();event.put("runId",run.id);event.put("sessionId",run.session);event.put("workspaceId",run.workspace);event.put("boardId",board);
        event.put("operationId",command.id);event.put("sequence",command.sequence);event.put("expectedVersion",version);event.put("kind",kind);event.put("label",label);event.put("payload",payload);
        run.emit.accept("canvas_operation",event);
        Ack receipt;
        try { receipt=command.result.get(Math.max(1,Math.min(30_000,run.deadline-System.currentTimeMillis())),TimeUnit.MILLISECONDS); }
        catch(Exception e){run.cancelled=true;run.emit.accept("operation_result",Map.of("operationId",command.id,"boardId",board,"label",label,"success",false,"message","操作未获得有效回执，已停止。"));throw e;}
        run.emit.accept("operation_result",Map.of("operationId",command.id,"boardId",board,"label",label,"success",receipt.success(),"message",receipt.success()?"":safeMessage(receipt.message())));
        if(!receipt.success()){run.failureMessage=safeMessage(receipt.message());run.cancelled=true;throw new CancellationException();}
        if(!board.isBlank())run.versions.put(board,receipt.version());
        run.pending=null;return receipt;
    }

    public void ack(String owner,String id,Ack ack) {
        try { acceptAck(owner,id,ack); }
        catch(RuntimeException e) {
            var run=runs.get(id);
            if(run!=null&&run.owner.equals(owner))run.emit.accept("canvas_receipt",Map.of("runId",id,"operationId",ack==null?"":Objects.toString(ack.operationId(),""),"success",false,"message",Objects.toString(e.getMessage(),"无效回执"),"evidenceSource","SERVER_REJECTED"));
            throw e;
        }
    }
    private void acceptAck(String owner,String id,Ack ack) {
        if(ack==null)throw AgentRepository.invalid("缺少操作回执。");
        Run run=owned(owner,id);Command command=run.pending;
        if(command==null||!command.id.equals(ack.operationId())||command.sequence!=ack.sequence())throw AgentRepository.invalid("操作回执不匹配。");
        synchronized(command) {
            if(command.result.isDone())return;
            if(ack.success()) {
                boolean changes=Set.of("add","replace","remove","adjust","layout").contains(command.kind);
                int expected=command.expected+(changes?1:0);
                if(ack.version()==null||ack.version()!=expected)throw AgentRepository.invalid("画板版本已变化。");
                if(command.kind.equals("open")){if(ack.boardCount()==null||ack.boardCount()<0||ack.boardCount()>12)throw AgentRepository.invalid("工作区容量无效。");}
                if(!command.board.isBlank()) {
                    if(ack.scene()==null||!ack.scene().path("elements").isArray())throw AgentRepository.invalid("缺少画板执行结果。");
                    var scene=json.treeToValue(ack.scene(),OutfitRepository.SceneWrite.class);
                    outfits.validatePreview(scene,owner);
                    var ids=garmentIds(ack.scene());
                    var clothes=new ArrayList<Map<String,List<String>>>();
                    for(String garmentId:ids){var garment=garmentRepository.get(UUID.fromString(garmentId));if(!Objects.equals(run.garmentVersions.get(garmentId),garment.revision()))throw AgentRepository.invalid("衣物资料在执行期间变化，请重新核对搭配。");var fields=new LinkedHashMap<>(garment.effective());fields.put("_layeringConfirmed",List.of("fit","thickness","wearingModes").stream().allMatch(k -> garment.overrides().containsKey(k)&&!garment.overrides().get(k).isEmpty())?List.of("yes"):List.of());clothes.add(fields);}
                    if(!run.edit||command.kind.equals("finish"))OutfitRules.validate(clothes,!run.edit&&command.kind.equals("finish"));
                    if(!ids.equals(run.garments.get(command.board)))throw AgentRepository.invalid("画板衣物与授权操作不符。");
                    JsonNode previous=run.scenes.get(command.board);
                    if(previous!=null) {
                        var expectedElements=new HashMap<String,JsonNode>();
                        for(var item:previous.path("elements"))expectedElements.put(item.path("elementId").asText(),item);
                        String target=command.payload.path("elementId").asText();
                        if(command.kind.equals("remove"))expectedElements.remove(target);
                        var actualElements=new HashMap<String,JsonNode>();
                        for(var item:ack.scene().path("elements"))actualElements.put(item.path("elementId").asText(),item);
                        int expectedCount=previous.path("elements").size()+(command.kind.equals("add")?1:command.kind.equals("remove")?-1:0);
                        if(actualElements.size()!=expectedCount||!actualElements.keySet().containsAll(expectedElements.keySet()))throw AgentRepository.invalid("操作不能增删无关贴纸。");
                        for(var entry:expectedElements.entrySet()) {
                            var before=entry.getValue();var after=actualElements.get(entry.getKey());
                            if(command.kind.equals("replace")&&entry.getKey().equals(target)) {
                                if(!after.path("garmentId").asText().equals(command.payload.path("garmentId").asText())||"PERSON_PART".equals(after.path("kind").asText()))throw AgentRepository.invalid("替换衣物与授权不符。");
                            } else for(String key:List.of("garmentId","kind","assetId","personPartId","cropX","cropY","cropWidth","cropHeight")) {
                                if(!before.path(key).equals(after.path(key)))throw AgentRepository.invalid("操作不能更换无关贴图或裁剪。");
                            }
                        }
                        if(command.kind.equals("add"))for(var entry:actualElements.entrySet())if(!expectedElements.containsKey(entry.getKey())) {
                            if(!entry.getValue().path("garmentId").asText().equals(command.payload.path("garmentId").asText())||"PERSON_PART".equals(entry.getValue().path("kind").asText()))throw AgentRepository.invalid("新增衣物与授权不符。");
                        }
                        if(command.kind.equals("begin")&&!json.valueToTree(json.treeToValue(previous,OutfitRepository.SceneWrite.class)).equals(json.valueToTree(scene)))throw AgentRepository.invalid("临时画板已变化，请重新发起操作。");
                    }
                    run.scenes.put(command.board,ack.scene());
                }
            }
            run.emit.accept("canvas_receipt",Map.of("runId",id,"operationId",command.id,"boardId",command.board,"sequence",command.sequence,"kind",command.kind,"success",ack.success(),"expectedVersion",command.expected,"version",ack.version()==null?-1:ack.version(),"scene",ack.scene()==null?json.createObjectNode():traces.safeScene(ack.scene()),"message",ack.message()==null?"":safeMessage(ack.message())));
            command.result.complete(ack);
        }
    }
    public void cancel(String owner,String id){Run run=owned(owner,id);run.cancelled=true;Command command=run.pending;if(command!=null)command.result.completeExceptionally(new CancellationException());}
    private Run owned(String owner,String id){var run=runs.get(id);if(run==null||!run.owner.equals(owner)||run.cancelled||System.currentTimeMillis()>run.deadline)throw new ProcessingException(HttpStatus.CONFLICT,"AGENT_CANVAS_STOPPED","搭台操作已停止或授权失效。");return run;}
    private Set<String> garmentIds(JsonNode scene){var ids=new HashSet<String>();for(var item:scene.path("elements"))if(!"PERSON_PART".equals(item.path("kind").asText()))ids.add(item.path("garmentId").asText());return ids;}
    private String safeMessage(String text){return text==null?"操作未完成。":text.substring(0,Math.min(300,text.length()));}
}
