import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.integration.dm8.*;
import com.fuquan.audit.budget.domain.BudgetMaterialJob;
import com.fuquan.audit.common.material.domain.MaterialIntake;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Real mapper/DM8 probe. All synthetic rows roll back; workers cannot see them. */
public final class OcrQueuePlatformCheck {
    static void need(boolean ok,String code){if(!ok)throw new IllegalStateException(code);}
    public static void main(String[] args)throws Exception {
        var ds=new DriverManagerDataSource(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"));
        var jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(15);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);
        factory.setMapperLocations(new ClassPathResource("mappers/dm8/MaterialIntakeMapper.xml"));
        var template=new SqlSessionTemplate(factory.getObject());
        var intake=new Dm8MaterialIntakeRepository(template.getMapper(MaterialIntakeMapper.class),new ObjectMapper());
        var budget=new Dm8BudgetMaterialJobRepository(jdbc);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var bid=UUID.randomUUID();var pid=UUID.randomUUID();var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        try {
            tx.executeWithoutResult(state->{
                state.setRollbackOnly();
                var actor="SYNTHETIC_OCR_QUEUE";var key=UUID.randomUUID().toString();
                jdbc.update("INSERT INTO BUDGET_REVIEW_TASK(ID,FILE_NAME,CONTENT_TYPE,FILE_SIZE,STATUS,PARSED_LINE_COUNT,REVIEW_CURSOR,CREATED_TIME,UPDATED_TIME) VALUES(?,?,?,1,'QUEUED',0,0,?,?)",bid.toString(),"synthetic-queue.png","image/png",java.sql.Timestamp.from(now),java.sql.Timestamp.from(now));
                budget.insert(new BudgetMaterialJob(bid,actor,key,"a".repeat(64),"QUEUED",null,null,now));
                need(budget.claim(bid,"first",now,now.plusSeconds(60)),"BUDGET_CLAIM");
                need(!budget.claim(bid,"foreign",now,now.plusSeconds(60)),"BUDGET_EXCLUSIVE_LEASE");
                need(!budget.complete(bid,"foreign","QUEUED",now),"BUDGET_STALE_REQUEUE");
                need(budget.complete(bid,"first","QUEUED",now),"BUDGET_REQUEUE");
                need(budget.operation(actor,key).orElseThrow().id().equals(bid),"BUDGET_IDENTITY_CHANGED");
                need(budget.claim(bid,"second",now,now.plusSeconds(60)),"BUDGET_RECLAIM");
                need(!budget.complete(bid,"first","PARSED",now),"BUDGET_LATE_EXECUTOR");
                need(budget.complete(bid,"second","UNKNOWN",now),"BUDGET_UNKNOWN");
                need(!budget.claim(bid,"third",now.plusSeconds(120),now.plusSeconds(180)),"BUDGET_UNKNOWN_REPLAY");
                var original=new MaterialIntake(pid,actor,key,"synthetic-queue.png","image/png",1,"a".repeat(64),"synthetic-no-file","QUEUED","initial",now,null,null,null,now,now);
                intake.insert(original);
                var first=original.claim("first",now.plusSeconds(60),now);
                need(intake.claimPending(first,now),"INTAKE_CLAIM");
                need(!intake.claimPending(original.claim("foreign",now.plusSeconds(60),now),now),"INTAKE_EXCLUSIVE_LEASE");
                need(!intake.complete(original.claim("foreign",now.plusSeconds(60),now).finish("QUEUED",null,null,"OCR_CAPACITY_BUSY",now)),"INTAKE_STALE_REQUEUE");
                need(intake.complete(first.finish("QUEUED",null,null,"OCR_CAPACITY_BUSY",now)),"INTAKE_REQUEUE");
                need(intake.findOperation(actor,key).orElseThrow().id().equals(pid),"INTAKE_IDENTITY_CHANGED");
                var second=original.claim("second",now.plusSeconds(60),now);
                need(intake.claimPending(second,now),"INTAKE_RECLAIM");
                need(!intake.complete(first.finish("PARSED",null,null,null,now)),"INTAKE_LATE_EXECUTOR");
                need(intake.complete(second.finish("UNKNOWN",null,null,"OCR_OUTCOME_UNPROVEN",now)),"INTAKE_UNKNOWN");
                need(!intake.claimPending(original.claim("third",now.plusSeconds(180),now.plusSeconds(120)),now.plusSeconds(120)),"INTAKE_UNKNOWN_REPLAY");
                need(budget.pending(now.plusSeconds(120),10000).stream().noneMatch(j->j.id().equals(bid)),"BUDGET_UNKNOWN_DISCOVERY");
                need(intake.pending(now.plusSeconds(120),10000).stream().noneMatch(j->j.id().equals(pid)),"INTAKE_UNKNOWN_DISCOVERY");
            });
            need(budget.find(bid).isEmpty()&&intake.find(pid).isEmpty(),"PROBE_ROLLBACK");
            need(jdbc.queryForObject("SELECT COUNT(*) FROM BUDGET_REVIEW_TASK WHERE ID=?",Integer.class,bid.toString())==0,"TASK_ROLLBACK");
            System.out.println("DM8_OCR_QUEUE_REQUEUE_IDENTITY_STALE_LEASE_UNKNOWN_NO_REPLAY=PASS SYNTHETIC_ROWS_ROLLED_BACK=YES");
        } catch(Exception e){
            System.err.println("OCR_QUEUE_PLATFORM_FAILED type="+e.getClass().getSimpleName()+" reasonCode="+(e instanceof IllegalStateException?e.getMessage():"PLATFORM_ERROR"));
            System.exit(1);
        }
    }
}
