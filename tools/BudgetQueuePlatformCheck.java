import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Two real DM8 sessions verify row-lock fencing; only exact synthetic rows are removed. */
public final class BudgetQueuePlatformCheck {
    static Connection connect() throws SQLException {return DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"));}
    static void require(boolean b){if(!b)throw new IllegalStateException("queue invariant failed");}
    static int update(Connection c,String sql,Object...values)throws SQLException{try(var p=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);return p.executeUpdate();}}
    public static void main(String[] args) throws Exception {
        Class.forName("dm.jdbc.driver.DmDriver");String id=UUID.randomUUID().toString(),old=UUID.randomUUID().toString(),next=UUID.randomUUID().toString();
        var executor=Executors.newSingleThreadExecutor();
        try(var c=connect()) {
            c.setAutoCommit(false);
            try {
                update(c,"INSERT INTO BUDGET_REVIEW_TASK(ID,FILE_NAME,FILE_SIZE,STATUS,PARSED_LINE_COUNT,CREATED_TIME,UPDATED_TIME) VALUES(?,'SYNTHETIC-QUEUE-ROLLBACK.xlsx',1,'QUEUED',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id);
                update(c,"INSERT INTO BUDGET_MATERIAL_JOB(ID,ACTOR_ID,OPERATION_KEY,SHA256,STATUS,UPDATED_AT) VALUES(?,'SYNTHETIC-QUEUE-ROLLBACK',?,?,'QUEUED',CURRENT_TIMESTAMP)",id,UUID.randomUUID().toString(),"a".repeat(64));c.commit();
                Timestamp now=Timestamp.from(Instant.now()),past=Timestamp.from(Instant.now().minusSeconds(10)),future=Timestamp.from(Instant.now().plusSeconds(1200));
                String claim="UPDATE BUDGET_MATERIAL_JOB SET STATUS='PROCESSING',LEASE_TOKEN=?,LEASE_UNTIL=?,UPDATED_AT=? WHERE ID=? AND (STATUS='QUEUED' OR (STATUS='PROCESSING' AND LEASE_UNTIL<=?))";
                require(update(c,claim,old,past,now,id,now)==1);c.commit();
                try(var p=c.prepareStatement("SELECT LEASE_TOKEN FROM BUDGET_MATERIAL_JOB WHERE ID=? AND STATUS='PROCESSING' FOR UPDATE")){p.setString(1,id);try(var r=p.executeQuery()){require(r.next()&&old.equals(r.getString(1)));}}
                var started=new CountDownLatch(1);
                var takeover=executor.submit(()->{try(var other=connect()){other.setAutoCommit(false);started.countDown();int changed=update(other,claim,next,future,now,id,now);other.commit();return changed;}});
                require(started.await(3,TimeUnit.SECONDS));Thread.sleep(250);require(!takeover.isDone());
                c.commit();require(takeover.get(10,TimeUnit.SECONDS)==1);
                require(update(c,"UPDATE BUDGET_MATERIAL_JOB SET STATUS='PARSED' WHERE ID=? AND STATUS='PROCESSING' AND LEASE_TOKEN=?",id,old)==0);
                require(update(c,"UPDATE BUDGET_MATERIAL_JOB SET LEASE_UNTIL=? WHERE ID=? AND STATUS='PROCESSING' AND LEASE_TOKEN=?",future,id,old)==0);
                require(update(c,"UPDATE BUDGET_MATERIAL_JOB SET STATUS='FAILED' WHERE ID=? AND STATUS='PROCESSING' AND LEASE_TOKEN=?",id,next)==1);
                require(update(c,claim,old,future,now,id,now)==0);c.rollback();
                System.out.println("REAL_DM8_BUDGET_QUEUE_TWO_SESSION_LOCK_EXPIRED_CLAIM_STALE_RESULTS=PASS");
            } finally {
                c.rollback();update(c,"DELETE FROM BUDGET_MATERIAL_JOB WHERE ID=? AND ACTOR_ID='SYNTHETIC-QUEUE-ROLLBACK'",id);
                update(c,"DELETE FROM BUDGET_REVIEW_TASK WHERE ID=? AND FILE_NAME='SYNTHETIC-QUEUE-ROLLBACK.xlsx'",id);c.commit();
                System.out.println("EXACT_SYNTHETIC_QUEUE_ROWS_REMOVED");
            }
        } catch(SQLException e){System.err.println("BUDGET_QUEUE_SQL_FAILED code="+e.getErrorCode()+" state="+e.getSQLState());System.exit(1);}
        finally {executor.shutdownNow();}
    }
}
