package com.smartcloset.agent;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import com.smartcloset.image.ProcessingException;

@Service
public class AgentMemoryDocuments {
    public record Document(String kind,String content,long revision,long updatedAt) {}
    public record Edit(long revision,String content) {}
    public record Permission(String purpose,long revision,long expiresAt) {}
    private final JdbcTemplate db;private final TransactionTemplate tx;
    public AgentMemoryDocuments(JdbcTemplate db,PlatformTransactionManager manager){this.db=db;tx=new TransactionTemplate(manager);}
    private void ensure(String owner) {
        tx.executeWithoutResult(t -> {
            db.queryForObject("SELECT username FROM closet_user WHERE username=? FOR UPDATE",String.class,owner);
            for(String kind:List.of("GENERAL","SENSITIVE"))if(db.queryForObject("SELECT COUNT(*) FROM agent_memory_document WHERE owner_username=? AND kind=?",Integer.class,owner,kind)==0) {
                var rows=db.query("SELECT content FROM agent_memory WHERE owner_username=? AND "+(kind.equals("SENSITIVE")?"category='PERSONAL'":"category!='PERSONAL'")+" ORDER BY created_at",(r,n)->r.getString(1),owner);
                db.update("INSERT INTO agent_memory_document VALUES(?,?,?,0,?)",owner,kind,String.join("\n\n",rows),System.currentTimeMillis());
            }
        });
    }
    public Document get(String owner,String kind){if(!Set.of("GENERAL","SENSITIVE").contains(kind))throw AgentRepository.invalid("记忆文档类型无效。");ensure(owner);return db.query("SELECT * FROM agent_memory_document WHERE owner_username=? AND kind=?",(r,n)->new Document(kind,r.getString("content"),r.getLong("revision"),r.getLong("updated_at")),owner,kind).getFirst();}
    public List<Document> list(String owner){return List.of(get(owner,"GENERAL"),get(owner,"SENSITIVE"));}
    public Document save(String owner,String kind,Edit input) {
        get(owner,kind);if(input==null||input.content()==null||input.content().length()>20000)throw AgentRepository.invalid("记忆文档最多 20000 字。");
        tx.executeWithoutResult(t -> {
            db.queryForObject("SELECT username FROM closet_user WHERE username=? FOR UPDATE",String.class,owner);
            if(db.update("UPDATE agent_memory_document SET content=?,revision=revision+1,updated_at=? WHERE owner_username=? AND kind=? AND revision=?",input.content(),System.currentTimeMillis(),owner,kind,input.revision())!=1)throw new ProcessingException(HttpStatus.CONFLICT,"MEMORY_REVISION_CONFLICT","记忆已经变化，请保留输入并重新核对。");
            // Conservative epoch barrier: raw chat remains visible, old model projections are excluded.
            db.update("UPDATE agent_session SET context_epoch=context_epoch+1 WHERE owner_username=?",owner);
            if(kind.equals("SENSITIVE"))db.update("DELETE FROM agent_memory_use_grant WHERE owner_username=?",owner);
        });return get(owner,kind);
    }
    public void legacyChange(String owner,String category,String before,String after) {
        String kind=category.equals("PERSONAL")?"SENSITIVE":"GENERAL";var doc=get(owner,kind);String content=doc.content();
        if(before!=null&&!before.isBlank()){if(content.indexOf(before)<0||content.indexOf(before)!=content.lastIndexOf(before))throw new ProcessingException(HttpStatus.CONFLICT,"MEMORY_REVISION_CONFLICT","原始文档已修改，请直接核对最新文档；旧记忆不能覆盖或恢复它。");content=content.replace(before,"").strip();}
        if(after!=null&&!after.isBlank())content=(content.isBlank()?"":content+"\n\n")+after;
        save(owner,kind,new Edit(doc.revision(),content));
    }
    public List<Permission> permissions(String owner){return db.query("SELECT * FROM agent_memory_use_grant WHERE owner_username=?",(r,n)->new Permission(r.getString("purpose"),r.getLong("document_revision"),r.getLong("expires_at")),owner);}
    public Permission authorize(String owner,String purpose,long revision) {return tx.execute(t->{
        db.queryForObject("SELECT username FROM closet_user WHERE username=? FOR UPDATE",String.class,owner);
        if(!Set.of("OUTFIT","BASELINE","OOTD").contains(purpose))throw AgentRepository.invalid("用途无效。");var doc=get(owner,"SENSITIVE");if(doc.revision()!=revision)throw new ProcessingException(HttpStatus.CONFLICT,"MEMORY_REVISION_CONFLICT","请先核对最新敏感记忆。");
        long expires=System.currentTimeMillis()+365L*86400000;
        db.update("MERGE INTO agent_memory_use_grant(owner_username,purpose,document_revision,expires_at) KEY(owner_username,purpose) VALUES(?,?,?,?)",owner,purpose,revision,expires);return new Permission(purpose,revision,expires);
    });}
    public void revoke(String owner,String purpose){tx.executeWithoutResult(t->{db.queryForObject("SELECT username FROM closet_user WHERE username=? FOR UPDATE",String.class,owner);db.update("DELETE FROM agent_memory_use_grant WHERE owner_username=? AND purpose=?",owner,purpose);db.update("UPDATE agent_session SET context_epoch=context_epoch+1 WHERE owner_username=?",owner);});}
    public Map<String,Object> modelInput(String owner,Set<String> purposes) {
        // Read grants, revisions and content under the same short owner transaction as edits/revocations.
        return tx.execute(t -> {
            db.queryForObject("SELECT username FROM closet_user WHERE username=? FOR UPDATE",String.class,owner);
            return modelSnapshot(owner,purposes);
        });
    }
    private Map<String,Object> modelSnapshot(String owner,Set<String> purposes) {
        var general=get(owner,"GENERAL");
        String safe=Arrays.stream(general.content().split("\\n\\s*\\n")).filter(p -> !p.matches("(?s).*(身高|体重|体型|身材|围度|病史|病情|手机号|身份证|地址|胸围|腰围|臀围).*" )).reduce("",(a,b)->a+"\n\n"+b);
        var fragments=new LinkedHashMap<String,String>();
        long sensitiveRevision=db.queryForObject("SELECT revision FROM agent_memory_document WHERE owner_username=? AND kind='SENSITIVE'",Long.class,owner);
        var permitted=permissions(owner).stream().filter(p -> purposes.contains(p.purpose())&&p.revision()==sensitiveRevision&&p.expiresAt()>System.currentTimeMillis()).toList();
        // Ordinary queries never load the sensitive document body.
        if(!permitted.isEmpty()){var sensitive=get(owner,"SENSITIVE");
        for(var permission:permitted) {
            String allowed=Arrays.stream(sensitive.content().split("[\\n。；;，,！？!?]+")).filter(p -> !p.matches("(?s).*(身份证|手机号|电话|地址|病史|病情).*" )&&p.matches("(?s).*(身高|体重|体型|身材|围度|合身|怕冷|怕热|尺码|穿搭偏好).*" )).reduce("",(a,b)->a+"\n\n"+b);
            fragments.put(permission.purpose(),allowed);
        }
        }
        return Map.of("general",safe,"sensitiveFragments",fragments,"documentRevision",sensitiveRevision,"generalRevision",general.revision());
    }
    public long epoch(String owner,String session){return db.queryForObject("SELECT context_epoch FROM agent_session WHERE id=? AND owner_username=?",Long.class,session,owner);}
    public boolean current(String owner,String session,long epoch){return epoch(owner,session)==epoch;}
}
