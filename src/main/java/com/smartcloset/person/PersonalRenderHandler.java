package com.smartcloset.person;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import com.smartcloset.processing.*;
@Component
public class PersonalRenderHandler implements ProcessingHandler {
    private final PersonalRenders renders;private final JdbcTemplate db;
    public PersonalRenderHandler(PersonalRenders renders,JdbcTemplate db){this.renders=renders;this.db=db;}
    public String kind(){return "RENDER";}
    public List<Candidate> candidates(){renders.sync();return db.query("SELECT id,owner_username FROM image_render_job WHERE status='QUEUED'",(r,n)->Map.entry(r.getString(1),r.getString(2))).stream().map(job->new Candidate(kind(),job.getKey(),0,job.getValue(),"GENERATION",renders.input(job.getKey()).generation().provider(),50)).toList();}
    public String frozenInput(Candidate c){return db.queryForObject("SELECT input_json FROM image_render_job WHERE id=?",String.class,c.businessId());}
    public String result(ProcessingStore.Task t){return null;}
    public int quotaUnits(ProcessingStore.Task t){return 1;}
    public boolean autoRetryRejected(){return false;}
    public void quotaUnavailable(ProcessingStore.Task t){db.update("UPDATE image_render_job SET status='FAILED',error_message='额度不足，请主动发起新任务；不会隔天自动生成。' WHERE id=? AND status='QUEUED'",t.businessId());}
    public void execute(ProcessingStore.Task t)throws Exception{renders.execute(t.businessId());}
    public String outcome(ProcessingStore.Task t){String status=renders.job(t.owner(),t.businessId()).status();return Set.of("DELETED","CANCELLED","CANCEL_REQUESTED").contains(status)?"CANCELLED":status;}
    public void cancel(ProcessingStore.Task t){renders.cancel(t.owner(),t.businessId());}
}
