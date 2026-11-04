import java.sql.*;
import java.util.concurrent.*;

/** Establish SHARE/NOWAIT behavior on the isolated instance before using backup locks. */
class Dm8LockSemantics {
 static Connection open()throws SQLException{return DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"));}
 public static void main(String[] a)throws Exception {
  if(!System.getenv("AUDIT_DB_URL").equals("jdbc:dm://127.0.0.1:15287"))throw new IllegalArgumentException();
  Class.forName("dm.jdbc.driver.DmDriver");
  try(var first=open();var second=open()) {
   first.setAutoCommit(false);second.setAutoCommit(false);
   first.createStatement().execute("LOCK TABLE LOCK_PROBE IN SHARE MODE NOWAIT");
   var future=CompletableFuture.supplyAsync(()->{
    try{second.createStatement().executeUpdate("UPDATE LOCK_PROBE SET ID=2 WHERE ID=1");return true;}
    catch(SQLException e){throw new CompletionException(e);}
   });
   try{future.get(750,TimeUnit.MILLISECONDS);throw new AssertionError("SHARE lock did not block update");}
   catch(TimeoutException expected){System.out.println("SHARE_BLOCKS_WRITER=PASS");}
   first.rollback();
   if(!future.get(5,TimeUnit.SECONDS))throw new AssertionError();second.rollback();
   second.createStatement().executeUpdate("UPDATE LOCK_PROBE SET ID=2 WHERE ID=1");
   long start=System.nanoTime();
   try{first.createStatement().execute("LOCK TABLE LOCK_PROBE IN SHARE MODE NOWAIT");throw new AssertionError("NOWAIT did not reject conflicting writer");}
   catch(SQLException e){System.out.println("NOWAIT_CONFLICT_CODE="+e.getErrorCode());}
   if(System.nanoTime()-start>TimeUnit.SECONDS.toNanos(2))throw new AssertionError("NOWAIT blocked");
   second.rollback();first.rollback();System.out.println("LOCK_SEMANTICS=PASS");
  }
 }
}
