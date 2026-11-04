import java.sql.*;
import java.util.*;

/** Metadata/counts only. Never exports file names, document data or credentials. */
public final class CustomerReadiness {
    static void need(boolean ok,String code){if(!ok)throw new IllegalStateException(code);}
    static boolean exists(Connection c,String table)throws Exception {return CustomerContractSchema.ownTable(c,table);}
    static void idle(Connection c,String table,String condition)throws Exception {
        if(!exists(c,table)){System.out.println("ACTIVE_JOBS table="+table+" state=NOT_APPLIED");return;}
        var result=CustomerContractSchema.rows(c,"SELECT COUNT(*) CNT FROM "+table+" WHERE "+condition);long count=((Number)result.get(0).get("CNT")).longValue();
        System.out.println("ACTIVE_JOBS table="+table+" count="+count);need(count==0,"ACTIVE_OR_UNCERTAIN_WORK_REQUIRES_INVESTIGATION_"+table);
    }
    public static void main(String[] args){try {
        Class.forName("dm.jdbc.driver.DmDriver");DriverManager.setLoginTimeout(15);
        try(var c=DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"))){
            need(c.getMetaData().getDatabaseProductName().toUpperCase(Locale.ROOT).contains("DM"),"NOT_DM8");
            if(args[0].equals("deadline")){
                if(!exists(c,"MATERIAL_EXTRACTION_LEDGER")){need(args[1].equals("check"),"LEDGER_MISSING");System.out.println("V057=PLANNED_AFTER_V055");return;}
                boolean present=MaterialDeadlineMigration.migrate(c,"MATERIAL_EXTRACTION_LEDGER",args[1].equals("apply"));need(!args[1].equals("verify")||present,"DEADLINE_NOT_APPLIED");System.out.println("V057="+(present?"SCHEMA_MATCHING":"NOT_APPLIED"));return;
            }
            idle(c,"BUDGET_MATERIAL_JOB","STATUS IN ('QUEUED','PROCESSING')");
            idle(c,"BUSINESS_MATERIAL_INTAKE","STATUS IN ('QUEUED','PROCESSING')");
            idle(c,"PROJECT_FILE_PROCESSING_JOB","STATUS IN ('QUEUED','PROCESSING')");
            idle(c,"CONTRACT_REVIEW_RUN","STATUS IN ('PENDING','RUNNING')");
            idle(c,"CONTRACT_REVIEW_STEP_ATTEMPT","STATUS IN ('PENDING','RUNNING')");
            idle(c,"MATERIAL_EXTRACTION_LEDGER","STATUS='CALLING'");
            idle(c,"MATERIAL_TRANSFER","STATUS='ASSEMBLING'");
            idle(c,"AUDIT_CASE","STATUS IN ('UPLOADING','QUEUED','RUNNING','UNKNOWN')");
            idle(c,"CONTRACT_ANALYSIS_CASE","STATUS IN ('QUEUED','RUNNING','UNKNOWN')");
            // An ACTIVE plan on a terminal parent is historical and is not itself a running model.
            if(exists(c,"CONTRACT_SEMANTIC_UNIT"))idle(c,"CONTRACT_SEMANTIC_UNIT","STATUS IN ('CLAIMED','SENT')");
            System.out.println("DEPLOYMENT_QUIESCENCE=PASS");
        }
    }catch(Exception e){SQLException sql=null;for(Throwable t=e;t!=null;t=t.getCause())if(t instanceof SQLException s)sql=s;String reason=e instanceof IllegalStateException&&Objects.toString(e.getMessage(),"").matches("[A-Z0-9_]+")?e.getMessage():"DATABASE_OR_PLATFORM_ERROR";System.err.println("READINESS_BLOCKED reasonCode="+reason+" dbCode="+(sql==null?0:sql.getErrorCode()));System.exit(1);}}
}
