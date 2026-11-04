import java.sql.*;
import java.util.*;

/** Read-only task quiescence check; no document text, configuration or business DML. */
public final class ThreeModuleDeploymentReadyCheck {
    public static void main(String[] args)throws Exception {
        Class.forName("dm.jdbc.driver.DmDriver");DriverManager.setLoginTimeout(15);
        try(var c=DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"))) {
            c.setReadOnly(true);
            for(String table:List.of("BUDGET_MATERIAL_JOB","BUSINESS_MATERIAL_INTAKE",
                    "PROJECT_FILE_PROCESSING_JOB","CONTRACT_REVIEW_RUN","CONTRACT_REVIEW_STEP_ATTEMPT",
                    "MATERIAL_EXTRACTION_LEDGER")) {
                try(var s=c.createStatement()) {
                    s.setQueryTimeout(15);
                    try(var r=s.executeQuery("SELECT COUNT(*) FROM "+table+" WHERE STATUS IN ('QUEUED','PENDING','PROCESSING','RUNNING','IN_PROGRESS','CALLING')")) {
                        if(!r.next())throw new IllegalStateException("TASK_STATE_UNKNOWN");
                        long count=r.getLong(1);System.out.println("DEPLOYMENT_ACTIVE_JOBS table="+table+" count="+count);
                        if(count!=0)throw new IllegalStateException("ACTIVE_JOBS_DEPLOYMENT_DEFERRED");
                    }
                }
            }
        } catch(SQLException failure) {
            System.err.println("DEPLOYMENT_DB_CHECK_FAILED dbCode="+failure.getErrorCode());System.exit(1);
        }
    }
}
