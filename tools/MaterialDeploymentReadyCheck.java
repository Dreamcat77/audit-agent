import java.sql.*;

/** Read-only deployment gate; never prints connection configuration or document data. */
public final class MaterialDeploymentReadyCheck {
    public static void main(String[] args) throws Exception {
        try (var c=DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),
                System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"))) {
            for(String table:new String[]{"BUDGET_MATERIAL_JOB","BUSINESS_MATERIAL_INTAKE"}) {
                try(var s=c.createStatement()) {
                    s.setQueryTimeout(15);
                    try(var r=s.executeQuery("SELECT COUNT(*) FROM "+table+" WHERE STATUS IN ('QUEUED','PROCESSING')")) {
                        r.next();int n=r.getInt(1);
                        System.out.println("DEPLOYMENT_ACTIVE_JOBS table="+table+" count="+n);
                        if(n!=0)throw new IllegalStateException("ACTIVE_MATERIAL_JOBS");
                    }
                }
            }
        } catch(SQLException failure) {
            System.err.println("DEPLOYMENT_DB_CHECK_FAILED dbCode="+failure.getErrorCode());System.exit(1);
        }
    }
}
