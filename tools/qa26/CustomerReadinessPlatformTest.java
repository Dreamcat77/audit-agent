import java.sql.*;
import java.util.*;

/** Real-DM8, rollback-only fixtures in the disposable migration-test schema. */
public final class CustomerReadinessPlatformTest {
    static final String CASE_BUSY="STATUS IN ('UPLOADING','QUEUED','RUNNING','UNKNOWN')";
    static final String CONTRACT_BUSY="STATUS IN ('QUEUED','RUNNING','UNKNOWN')";
    static void need(boolean condition,String reason){if(!condition)throw new IllegalStateException(reason);}
    static void fixture(Connection c,String table,String status)throws Exception {
        String sql=table.equals("AUDIT_CASE")
            ? "INSERT INTO AUDIT_CASE(ID,OWNER_ID,OPERATION_KEY,FINGERPRINT,STATUS,ROW_VERSION,LEASE_TOKEN,LEASE_UNTIL,CREATED_AT,SNAPSHOT_JSON) VALUES(?,'synthetic',?,'synthetic',?,0,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'{}')"
            : "INSERT INTO CONTRACT_ANALYSIS_CASE(ID,PROJECT_ID,OWNER_ID,INPUT_DIGEST,STATUS,ROW_VERSION,LEASE_TOKEN,LEASE_UNTIL,CREATED_AT,DEADLINE,SNAPSHOT_JSON) VALUES(?,?,'synthetic','synthetic',?,0,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'{}')";
        try(var p=c.prepareStatement(sql)){
            p.setString(1,UUID.randomUUID().toString());p.setString(2,UUID.randomUUID().toString());p.setString(3,status);
            p.setString(4,table.equals("AUDIT_CASE")||status.equals("RUNNING")?UUID.randomUUID().toString():null);
            if(table.equals("CONTRACT_ANALYSIS_CASE"))p.setTimestamp(5,status.equals("RUNNING")?Timestamp.from(java.time.Instant.now().plusSeconds(60)):null);
            need(p.executeUpdate()==1,"FIXTURE_NOT_CREATED");
        }
    }
    public static void main(String[] args)throws Exception {
        need("QA_UPDATE_V11".equals(System.getenv("AUDIT_DB_USERNAME"))&&System.getenv("AUDIT_DB_URL").equals("jdbc:dm://127.0.0.1:15287"),"DISPOSABLE_SCHEMA_REQUIRED");
        int rejected=0;
        try(var c=DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"))){
            need(c.getMetaData().getDatabaseProductName().toUpperCase(Locale.ROOT).contains("DM"),"NOT_DM8");c.setAutoCommit(false);
            for(String table:List.of("AUDIT_CASE","CONTRACT_ANALYSIS_CASE")){
                String condition=table.equals("AUDIT_CASE")?CASE_BUSY:CONTRACT_BUSY;
                for(String status:table.equals("AUDIT_CASE")?List.of("UPLOADING","QUEUED","RUNNING","UNKNOWN"):List.of("QUEUED","RUNNING","UNKNOWN")){
                    try{fixture(c,table,status);try{CustomerReadiness.idle(c,table,condition);throw new AssertionError("ACTIVE_OR_UNKNOWN_ACCEPTED");}
                    catch(IllegalStateException e){need(e.getMessage().equals("ACTIVE_OR_UNCERTAIN_WORK_REQUIRES_INVESTIGATION_"+table),"WRONG_FAILURE_REASON");rejected++;}}
                    finally{c.rollback();}
                }
                try{fixture(c,table,"COMPLETED");CustomerReadiness.idle(c,table,condition);}finally{c.rollback();}
                CustomerReadiness.idle(c,table,condition);
            }
        }
        System.out.println("CUSTOMER_READINESS_DM8=PASS rejected="+rejected+" terminal_allowed=2 fixtures_rolled_back=true original_database_modified=false");
    }
}
