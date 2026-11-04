import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.budget.application.BudgetMaterialRisk;
import com.fuquan.audit.integration.dm8.Dm8BudgetMaterialRiskRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Additive V053 deployment guard; never repairs an existing schema or prints SQL values. */
public class BudgetMaterialRiskPlatformCheck {
    private static final String TABLE="BUDGET_MATERIAL_RISK_RESULT";
    private static String stage="ARGUMENTS";
    static void require(boolean condition,String reason){if(!condition)throw new IllegalStateException(reason);}
    public static void main(String[] args) {
        try{run(args);}catch(Exception e){
            SQLException sql=null;for(Throwable c=e;c!=null;c=c.getCause())if(c instanceof SQLException s)sql=s;
            String reason=e instanceof IllegalStateException&&e.getMessage()!=null&&e.getMessage().matches("[A-Z0-9_]+")?e.getMessage():"DATABASE_OR_PLATFORM_ERROR";
            System.err.println("BUDGET_RISK_PLATFORM_FAILED table="+TABLE+" stage="+stage+" reasonCode="+reason+" type="+e.getClass().getSimpleName()+" dbCode="+(sql==null?"NONE":sql.getErrorCode()));System.exit(1);
        }
    }
    static void run(String[] args)throws Exception {
        require(args.length==2&&Set.of("check","apply","probe","audit").contains(args[0]),"ARGUMENTS");
        var ds=new DriverManagerDataSource(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"));
        var jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(20);
        if(args[0].equals("audit")){
            stage="AUDIT_EXACTLY_ONCE";String id=UUID.fromString(args[1]).toString();
            require(jdbc.queryForObject("SELECT COUNT(*) FROM BUDGET_MATERIAL_RISK_RESULT WHERE ID=?",Integer.class,id)==1,"RESULT_COUNT");
            require(jdbc.queryForObject("SELECT COUNT(*) FROM AUDIT_OPERATION_LOG WHERE RESOURCE_TYPE='BUDGET_MATERIAL_RISK_RESULT' AND RESOURCE_ID=? AND ACTION='BUDGET_MATERIAL_RISK_ANALYZED' AND MODULE='BUDGET' AND RESULT_STATUS='SUCCESS'",Integer.class,id)==1,"AUDIT_COUNT");
            System.out.println("RISK_REAL_DM8_RESULT_AND_DURABLE_AUDIT_EXACTLY_ONCE=PASS");return;
        }
        stage="OBJECTS";
        int objects=jdbc.queryForObject("SELECT COUNT(*) FROM USER_OBJECTS WHERE OBJECT_NAME=?",Integer.class,TABLE);
        if(objects==0){
            System.out.println("V053=NOT_APPLIED");if(!args[0].equals("apply")){require(args[0].equals("check"),"SCHEMA_NOT_APPLIED");return;}
            String source=Files.readString(Path.of(args[1])).replaceAll("(?m)^--.*$","").replaceAll("\\s+","").toUpperCase(Locale.ROOT);
            String expected="CREATETABLEBUDGET_MATERIAL_RISK_RESULT(IDVARCHAR(36)NOTNULLPRIMARYKEY,ACTOR_IDVARCHAR(64)NOTNULL,CREATED_ATTIMESTAMPNOTNULL,RESULT_JSONCLOBNOTNULL);CREATEINDEXIX_BUDGET_MATERIAL_RISK_ACTORONBUDGET_MATERIAL_RISK_RESULT(ACTOR_ID,CREATED_AT,ID);";
            require(source.equals(expected),"UNEXPECTED_MIGRATION_SQL");
            stage="CREATE";
            for(String statement:Files.readString(Path.of(args[1])).replaceAll("(?m)^--.*$","").split(";"))if(!statement.isBlank())jdbc.execute(statement);
        }
        stage="SCHEMA";verify(jdbc);System.out.println("V053=ALREADY_MATCHING");
        if(args[0].equals("probe")){stage="REPOSITORY_PROBE";probe(jdbc,ds);}
    }
    static void verify(JdbcTemplate jdbc){
        require(jdbc.queryForObject("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME=?",Integer.class,TABLE)==1,"SYNONYM_OR_NON_TABLE");
        var columns=jdbc.queryForList("SELECT COLUMN_NAME,DATA_TYPE,DATA_LENGTH,NULLABLE,DATA_DEFAULT FROM USER_TAB_COLUMNS WHERE TABLE_NAME=?",TABLE);
        var expected=Map.of("ID","VARCHAR:36:N","ACTOR_ID","VARCHAR:64:N","CREATED_AT","TIMESTAMP:0:N","RESULT_JSON","CLOB:0:N");
        require(columns.size()==4,"COLUMN_COUNT_DRIFT");
        for(var column:columns){String type=column.get("DATA_TYPE").toString();String observed=type+":"+(type.equals("VARCHAR")?column.get("DATA_LENGTH"):0)+":"+column.get("NULLABLE");
            require(observed.equals(expected.get(column.get("COLUMN_NAME"))),"COLUMN_DRIFT_"+column.get("COLUMN_NAME"));require(column.get("DATA_DEFAULT")==null,"DEFAULT_DRIFT");}
        var keys=jdbc.queryForList("SELECT CONSTRAINT_NAME,STATUS FROM USER_CONSTRAINTS WHERE TABLE_NAME=? AND CONSTRAINT_TYPE='P'",TABLE);
        require(keys.size()==1&&keys.get(0).get("STATUS").equals("ENABLED"),"PRIMARY_KEY_DRIFT");
        require(jdbc.queryForList("SELECT COLUMN_NAME FROM USER_CONS_COLUMNS WHERE CONSTRAINT_NAME=? ORDER BY POSITION",String.class,keys.get(0).get("CONSTRAINT_NAME")).equals(List.of("ID")),"PRIMARY_KEY_COLUMNS_DRIFT");
        for(var constraint:jdbc.queryForList("SELECT CONSTRAINT_TYPE,SEARCH_CONDITION,STATUS FROM USER_CONSTRAINTS WHERE TABLE_NAME=? AND CONSTRAINT_TYPE<>'P'",TABLE)){
            String type=constraint.get("CONSTRAINT_TYPE").toString();
            require(type.equals("C")&&constraint.get("STATUS").equals("ENABLED"),"EXTRA_CONSTRAINT_DRIFT");
            String check=Objects.toString(constraint.get("SEARCH_CONDITION"),"").replaceAll("[\\s\\\"()]","").toUpperCase(Locale.ROOT);
            require(Set.of("IDISNOTNULL","ACTOR_IDISNOTNULL","CREATED_ATISNOTNULL","RESULT_JSONISNOTNULL").contains(check),"EXTRA_CHECK_DRIFT");
        }
        require(jdbc.queryForList("SELECT COLUMN_NAME FROM USER_IND_COLUMNS WHERE INDEX_NAME='IX_BUDGET_MATERIAL_RISK_ACTOR' AND TABLE_NAME=? ORDER BY COLUMN_POSITION",String.class,TABLE).equals(List.of("ACTOR_ID","CREATED_AT","ID")),"INDEX_ORDER_DRIFT");
        require(jdbc.queryForObject("SELECT COUNT(*) FROM USER_INDEXES WHERE TABLE_NAME=? AND INDEX_NAME='IX_BUDGET_MATERIAL_RISK_ACTOR' AND UNIQUENESS='NONUNIQUE'",Integer.class,TABLE)==1,"INDEX_UNIQUENESS_DRIFT");
    }
    static void probe(JdbcTemplate jdbc,DriverManagerDataSource ds){
        var repo=new Dm8BudgetMaterialRiskRepository(jdbc,new ObjectMapper());var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        UUID id=UUID.randomUUID(),other=UUID.randomUUID();String actor="synthetic-risk-"+UUID.randomUUID();
        var result=new BudgetMaterialRisk.Result(id,actor,java.time.Instant.now().toString(),List.of(),List.of("合成测试".repeat(25000)));
        tx.executeWithoutResult(status->{
            stage="PROBE_CLOB_INSERT";repo.insert(result);stage="PROBE_CLOB_READ";require(repo.find(id,actor).orElseThrow().equals(result),"CLOB_ROUNDTRIP");
            require(repo.find(id,"other-actor").isEmpty(),"ACTOR_ISOLATION");
            require(repo.history(actor,0,1).equals(List.of(result))&&repo.history(actor,1,1).isEmpty(),"PAGINATION");
            stage="PROBE_DUPLICATE";boolean duplicate=false;try{repo.insert(result);}catch(org.springframework.dao.DuplicateKeyException expected){duplicate=true;}require(duplicate,"DUPLICATE_ID_NOT_REJECTED");
            Object save=status.createSavepoint();repo.insert(new BudgetMaterialRisk.Result(other,actor,result.createdAt(),List.of(),List.of()));status.rollbackToSavepoint(save);status.releaseSavepoint(save);
            require(repo.find(other,actor).isEmpty(),"SAVEPOINT_ROLLBACK");status.setRollbackOnly();
        });
        require(repo.find(id,actor).isEmpty()&&repo.history(actor,0,20).isEmpty(),"TRANSACTION_ROLLBACK");
        System.out.println("DM8_RISK_CLOB_100KB_ACTOR_ISOLATION_PAGINATION_DUPLICATE_SAVEPOINT_ROLLBACK=PASS; SYNTHETIC_ROWS=ROLLED_BACK");
    }
}
