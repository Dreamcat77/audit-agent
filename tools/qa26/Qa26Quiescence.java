import java.sql.*;
import java.util.*;

/** Read-only guard for the independent full QA schema, including both new workflows. */
public final class Qa26Quiescence {
    public static void main(String[] args)throws Exception {
        String url=System.getenv("AUDIT_DB_URL"),user=System.getenv("AUDIT_DB_USERNAME");
        if(!"QA26_FULL2".equals(user)||!"jdbc:dm://127.0.0.1:15287".equals(url))throw new IllegalStateException("ISOLATED_DATABASE_REQUIRED");
        Class.forName("dm.jdbc.driver.DmDriver");DriverManager.setLoginTimeout(15);
        try(var c=DriverManager.getConnection(url,user,System.getenv("AUDIT_DB_PASSWORD"))) {
            c.setReadOnly(true);
            for(String table:List.of("AUDIT_CASE","CONTRACT_ANALYSIS_CASE","BUDGET_MATERIAL_JOB","BUSINESS_MATERIAL_INTAKE",
                    "PROJECT_FILE_PROCESSING_JOB","CONTRACT_REVIEW_RUN","CONTRACT_REVIEW_STEP_ATTEMPT","MATERIAL_EXTRACTION_LEDGER")) {
                try(var s=c.createStatement()) {s.setQueryTimeout(15);
                    try(var r=s.executeQuery("SELECT COUNT(*) FROM "+table+" WHERE STATUS IN ('UPLOADING','QUEUED','PENDING','PROCESSING','RUNNING','IN_PROGRESS','CALLING')")){
                        if(!r.next())throw new IllegalStateException("ACTIVE_STATE_UNKNOWN");long count=r.getLong(1);
                        System.out.println("QA26_ACTIVE table="+table+" count="+count);
                        if(count!=0)throw new IllegalStateException("ACTIVE_JOBS_DEFER_SWITCH");
                    }
                }
            }
        }
    }
}
