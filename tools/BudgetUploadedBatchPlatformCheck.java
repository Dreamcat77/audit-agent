import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.budget.domain.BudgetMaterialBatch;
import com.fuquan.audit.budget.application.BudgetUploadedFacts;
import com.fuquan.audit.budget.application.BudgetUploadedMaterialAnalyzer;
import com.fuquan.audit.integration.dm8.Dm8BudgetMaterialBatchRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Controlled V052 additive migration and rollback-only representative DM8 probe.
 * Credentials only from the existing process environment; output never contains SQL values. */
public class BudgetUploadedBatchPlatformCheck {
    public static void main(String[] args) {
        try {run(args);}catch(Exception e) {
            SQLException sql=null;for(Throwable c=e;c!=null;c=c.getCause())if(c instanceof SQLException s)sql=s;
            String reason=e.getMessage()!=null&&e.getMessage().matches("[A-Za-z0-9_]+")?e.getMessage():"DATABASE_OR_PLATFORM_ERROR";
            System.err.println("BUDGET_BATCH_PLATFORM_FAILED stage=V052 reason="+reason+" type="+e.getClass().getSimpleName()+" dbCode="+(sql==null?"NONE":sql.getErrorCode()));System.exit(1);
        }
    }
    static void require(boolean value,String code) {if(!value)throw new IllegalStateException(code);}
    static void run(String[] args)throws Exception {
        require(args.length==2&&Set.of("check","apply","probe","status","analyze-saved").contains(args[0]),"arguments");
        var ds=new DriverManagerDataSource(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"));
        var jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(20);
        if(args[0].equals("analyze-saved")) {
            var batchId=jdbc.queryForObject("SELECT ID FROM (SELECT ID,ROW_NUMBER() OVER(ORDER BY CREATED_AT DESC) RN FROM BUDGET_MATERIAL_BATCH WHERE MEMBER_COUNT=17) WHERE RN=1",String.class);
            var inputs=new ArrayList<BudgetUploadedFacts.Input>();var json=new ObjectMapper();
            for(var row:jdbc.queryForList("SELECT S.TASK_ID,T.FILE_NAME,J.STATUS,S.FACTS_JSON FROM BUDGET_MATERIAL_SOURCE S JOIN BUDGET_REVIEW_TASK T ON T.ID=S.TASK_ID JOIN BUDGET_MATERIAL_JOB J ON J.ID=S.TASK_ID WHERE S.BATCH_ID=?",batchId)) {
                require(row.get("STATUS").equals("PARSED"),"format_not_parsed");
                Object raw=row.get("FACTS_JSON");String text=raw instanceof Clob c?c.getSubString(1,(int)c.length()):raw.toString();
                inputs.add(new BudgetUploadedFacts.Input(UUID.fromString(row.get("TASK_ID").toString()),row.get("FILE_NAME").toString(),"PARSED",json.readValue(text,BudgetUploadedFacts.Snapshot.class)));
            }
            for(var input:inputs)for(var sourceRow:input.snapshot().rows())for(var fact:sourceRow.facts())if(fact.field().endsWith("Amount"))
                System.out.println("SYNTHETIC_AMOUNT file="+input.fileName()+" field="+fact.field()+" value="+fact.value()+" DECLARED_LABEL="+fact.excerpt().matches("(?s).*(申报|申请)金额.*")+" BUDGET_LABEL="+fact.excerpt().contains("预算金额"));
            require(inputs.size()==17,"format_members_missing");
            var results=new BudgetUploadedMaterialAnalyzer().analyze(inputs);
            int matchedFiles=0;int isolatedFiles=0;
            for(var input:inputs) {
                var lines=results.get(input.taskId()).lines();
                long matched=lines.stream().filter(l->l.declaredAmount()!=null&&l.approvalAmount()!=null&&l.declaredAmount().compareTo(new java.math.BigDecimal("31200"))==0&&l.approvalAmount().compareTo(new java.math.BigDecimal("32760"))==0&&l.status().equals("WITHIN_APPROVAL")).count();
                System.out.println("SYNTHETIC_FORMAT="+input.fileName()+" ROWS="+lines.size()+" MATCHED_AMOUNTS="+matched);
                for(var line:lines)System.out.println("SYNTHETIC_RESULT status="+line.status()+" declared="+line.declaredAmount()+" approved="+line.approvalAmount()+" sources="+line.sources().stream().map(BudgetUploadedFacts.Evidence::taskId).distinct().count());
                require(lines.stream().noneMatch(l->l.declaredAmount()!=null&&l.declaredAmount().compareTo(new java.math.BigDecimal("200000"))==0),"project_total_contaminated_declaration");
                if(matched>0)matchedFiles++;
                else {
                    require(Set.of("sample.png","sample.bmp").contains(input.fileName()),"unexpected_unmatched_format");
                    require(!lines.isEmpty()&&lines.stream().allMatch(l->l.status().equals("CONFLICT")&&l.declaredAmount()==null&&l.approvalAmount()==null),"ocr_identity_conflict_not_isolated");
                    isolatedFiles++;
                }
            }
            require(matchedFiles==15&&isolatedFiles==2,"unexpected_format_outcomes");
            System.out.println("FINAL_ANALYZER_ON_REAL_DM8_SAVED_17_FORMAT_SNAPSHOTS=PASS; MATCHED_FILES=15; OCR_IDENTITY_CONFLICT_FILES=2; DATABASE=READ_ONLY");
            return;
        }
        if(args[0].equals("status")) {
            for(var row:jdbc.queryForList("SELECT J.STATUS,COUNT(*) CNT FROM BUDGET_MATERIAL_JOB J JOIN BUDGET_MATERIAL_SOURCE S ON S.TASK_ID=J.ID JOIN BUDGET_MATERIAL_BATCH B ON B.ID=S.BATCH_ID GROUP BY J.STATUS"))System.out.println("BATCH_JOB_COUNTS="+row);
            for(var row:jdbc.queryForList("SELECT B.STATUS,B.MEMBER_COUNT,COUNT(S.TASK_ID) ATTACHED,COUNT(S.RESULT_JSON) RESULTS FROM BUDGET_MATERIAL_BATCH B LEFT JOIN BUDGET_MATERIAL_SOURCE S ON S.BATCH_ID=B.ID GROUP BY B.STATUS,B.MEMBER_COUNT"))System.out.println("BATCH_COUNTS="+row);
            return;
        }
        int exists=jdbc.queryForObject("SELECT COUNT(*) FROM USER_OBJECTS WHERE OBJECT_NAME IN ('BUDGET_MATERIAL_BATCH','BUDGET_MATERIAL_SOURCE')",Integer.class);
        require(exists==0||exists==2,"partial_or_foreign_objects");
        if(exists==0) {
            System.out.println("V052=NOT_APPLIED");
            if(!args[0].equals("apply"))return;
            require(jdbc.queryForObject("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME IN ('BUDGET_REVIEW_TASK','BUDGET_MATERIAL_JOB')",Integer.class)==2,"missing_dependency");
            String source=Files.readString(Path.of(args[1])).replaceAll("(?m)^--.*$","");
            for(String statement:source.split(";"))if(!statement.isBlank()) {
                require(statement.strip().matches("(?s)CREATE (?:TABLE BUDGET_MATERIAL_(?:BATCH|SOURCE)|INDEX IX_BUDGET_(?:BATCH_PENDING|SOURCE_BATCH)).*"),"unapproved_ddl");
                jdbc.execute(statement);
            }
        }
        verify(jdbc);
        System.out.println("V052=ALREADY_MATCHING");
        if(args[0].equals("probe"))probe(jdbc,ds);
    }
    static void verify(JdbcTemplate jdbc) {
        Map<String,String> batch=Map.ofEntries(Map.entry("ID","VARCHAR:36:N"),Map.entry("ACTOR_ID","VARCHAR:64:N"),Map.entry("BUDGET_YEAR","VARCHAR:16:Y"),Map.entry("DEPARTMENT_CODE","VARCHAR:64:Y"),
            Map.entry("MEMBERS_JSON","CLOB:0:N"),Map.entry("MEMBER_COUNT","INT:0:N"),Map.entry("STATUS","VARCHAR:20:N"),Map.entry("LEASE_TOKEN","VARCHAR:36:Y"),Map.entry("LEASE_UNTIL","TIMESTAMP:0:Y"),Map.entry("CREATED_AT","TIMESTAMP:0:N"));
        Map<String,String> source=Map.of("TASK_ID","VARCHAR:36:N","BATCH_ID","VARCHAR:36:N","FACTS_JSON","CLOB:0:Y","RESULT_JSON","CLOB:0:Y");
        for(var entry:Map.of("BUDGET_MATERIAL_BATCH",batch,"BUDGET_MATERIAL_SOURCE",source).entrySet()) {
            var columns=jdbc.queryForList("SELECT COLUMN_NAME,DATA_TYPE,DATA_LENGTH,NULLABLE FROM USER_TAB_COLUMNS WHERE TABLE_NAME=?",entry.getKey());
            require(columns.size()==entry.getValue().size(),"column_count");
            for(var column:columns) {
                String name=column.get("COLUMN_NAME").toString(),type=column.get("DATA_TYPE").toString();
                if(type.equals("INTEGER"))type="INT";
                String observed=type+":"+(type.equals("VARCHAR")?column.get("DATA_LENGTH").toString():"0")+":"+column.get("NULLABLE");
                require(observed.equals(entry.getValue().get(name)),"column_drift_"+entry.getKey()+"_"+name);
            }
            require(jdbc.queryForObject("SELECT COUNT(*) FROM USER_CONSTRAINTS WHERE TABLE_NAME=? AND CONSTRAINT_TYPE='P' AND STATUS='ENABLED'",Integer.class,entry.getKey())==1,"primary_key");
        }
        for(String name:List.of("FK_BUDGET_SOURCE_TASK","FK_BUDGET_SOURCE_BATCH","CK_BUDGET_BATCH_STATUS"))
            require(jdbc.queryForObject("SELECT COUNT(*) FROM USER_CONSTRAINTS WHERE CONSTRAINT_NAME=? AND STATUS='ENABLED'",Integer.class,name)==1,"constraint");
        for(var index:Map.of("IX_BUDGET_BATCH_PENDING",List.of("STATUS","LEASE_UNTIL","CREATED_AT"),"IX_BUDGET_SOURCE_BATCH",List.of("BATCH_ID","TASK_ID")).entrySet())
            require(jdbc.queryForList("SELECT COLUMN_NAME FROM USER_IND_COLUMNS WHERE INDEX_NAME=? ORDER BY COLUMN_POSITION",String.class,index.getKey()).equals(index.getValue()),"index_drift");
        // Verify exact FK endpoints, never trust just a similarly named constraint.
        for(var fk:Map.of("FK_BUDGET_SOURCE_TASK",List.of("TASK_ID","BUDGET_REVIEW_TASK","ID"),"FK_BUDGET_SOURCE_BATCH",List.of("BATCH_ID","BUDGET_MATERIAL_BATCH","ID")).entrySet()) {
            var actual=jdbc.queryForList("SELECT C.COLUMN_NAME CHILD_COL,P.TABLE_NAME PARENT_TABLE,P.COLUMN_NAME PARENT_COL FROM USER_CONSTRAINTS F JOIN USER_CONS_COLUMNS C ON C.CONSTRAINT_NAME=F.CONSTRAINT_NAME JOIN USER_CONS_COLUMNS P ON P.CONSTRAINT_NAME=F.R_CONSTRAINT_NAME AND P.POSITION=C.POSITION WHERE F.CONSTRAINT_NAME=?",fk.getKey());
            require(actual.size()==1,"fk_columns");var r=actual.get(0);require(List.of(r.get("CHILD_COL").toString(),r.get("PARENT_TABLE").toString(),r.get("PARENT_COL").toString()).equals(fk.getValue()),"fk_target_drift");
        }
        String check=jdbc.queryForObject("SELECT SEARCH_CONDITION FROM USER_CONSTRAINTS WHERE CONSTRAINT_NAME='CK_BUDGET_BATCH_STATUS'",String.class).replaceAll("[\\s\\\"()]","").toUpperCase(Locale.ROOT);
        require(check.equals("STATUSIN'OPEN','PROCESSING','REVIEWED'"),"status_check_drift");
    }
    static void probe(JdbcTemplate jdbc,DriverManagerDataSource ds) {
        var repo=new Dm8BudgetMaterialBatchRepository(jdbc,new ObjectMapper());
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        UUID batch=UUID.randomUUID(),task=UUID.randomUUID(),key=UUID.randomUUID();Instant now=Instant.now();
        tx.executeWithoutResult(status->{
            repo.insert(new BudgetMaterialBatch(batch,"synthetic-platform",null,null,List.of(new BudgetMaterialBatch.Member(key,"synthetic.txt",1)),"OPEN",null,null,now));
            require(repo.pending(now,10000).stream().noneMatch(b->b.id().equals(batch)),"not_uploaded_must_wait");
            jdbc.update("INSERT INTO BUDGET_REVIEW_TASK(ID,FILE_NAME,FILE_SIZE,STATUS,PARSED_LINE_COUNT) VALUES(?,'synthetic-platform.txt',1,'PARSED',1)",task.toString());
            jdbc.update("INSERT INTO BUDGET_MATERIAL_JOB(ID,ACTOR_ID,OPERATION_KEY,SHA256,STATUS,UPDATED_AT) VALUES(?,'synthetic-platform',?,?,'PARSED',CURRENT_TIMESTAMP)",task.toString(),key.toString(),"a".repeat(64));
            repo.attach(new BudgetMaterialBatch.Source(task,batch,null,null));repo.extracted(task,"{\"rows\":[]}");
            require(repo.pending(now.plusSeconds(1),10000).stream().anyMatch(b->b.id().equals(batch)),"ready_discovery");
            require(repo.claim(batch,"old",now.minusSeconds(2),now.minusSeconds(1)),"first_claim");
            require(repo.claim(batch,"new",now,now.plusSeconds(1200)),"expired_claim");
            boolean rejected=false;try{repo.requireLease(batch,"old");}catch(IllegalStateException stale){rejected=true;}require(rejected,"stale_token");
            repo.requireLease(batch,"new");
            Object savepoint=status.createSavepoint();repo.result(task,"{\"status\":\"COMPLETED\"}");repo.complete(batch,"new");
            status.rollbackToSavepoint(savepoint);status.releaseSavepoint(savepoint);
            require(repo.source(task).orElseThrow().resultJson()==null&&repo.find(batch).orElseThrow().status().equals("PROCESSING"),"atomic_rollback");
            repo.requireLease(batch,"new");repo.result(task,"{\"status\":\"COMPLETED\"}");repo.complete(batch,"new");
            require(repo.find(batch).orElseThrow().status().equals("REVIEWED"),"complete");
            status.setRollbackOnly();
        });
        require(repo.find(batch).isEmpty()&&repo.source(task).isEmpty(),"synthetic_rollback");
        System.out.println("DM8_REAL_REPOSITORY_CLOB_MEMBERSHIP_PENDING_LEASE_FENCING_ATOMIC_ROLLBACK=PASS; SYNTHETIC_ROWS=ROLLED_BACK");
    }
}
