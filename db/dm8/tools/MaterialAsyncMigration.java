import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Credentials only from process environment. Expand state guard without editing business rows. */
public final class MaterialAsyncMigration {
    static final String OLD="STATUSIN'PROCESSING','PARSED','FAILED','UNKNOWN'";
    static final String NEW="STATUSIN'QUEUED','PROCESSING','PARSED','FAILED','UNKNOWN'";
    static String definition(Connection c,String name) throws SQLException {
        try(var s=c.prepareStatement("SELECT STATUS,SEARCH_CONDITION FROM USER_CONSTRAINTS WHERE TABLE_NAME='BUSINESS_MATERIAL_INTAKE' AND CONSTRAINT_NAME=?")) {
            s.setString(1,name);try(var r=s.executeQuery()) {
                if(!r.next()) return "MISSING";
                if(!"ENABLED".equals(r.getString(1))) throw new IllegalStateException("disabled guard");
                return r.getString(2).replaceAll("[\\s\\\"()]", "").toUpperCase();
            }
        }
    }
    static long rows(Connection c) throws SQLException {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM BUSINESS_MATERIAL_INTAKE")) {r.next();return r.getLong(1);}
    }
    public static void main(String[] args) {
        try {run(args);} catch(SQLException error) {
            System.err.println("ASYNC_MIGRATION_SQL_FAILED code="+error.getErrorCode()+" state="+error.getSQLState());System.exit(1);
        } catch(Exception error) {
            System.err.println("ASYNC_MIGRATION_FAILED type="+error.getClass().getSimpleName());System.exit(1);
        }
    }
    static void run(String[] args) throws Exception {
        if(args.length!=2 || !Set.of("check","apply","verify").contains(args[0])) throw new IllegalArgumentException();
        Class.forName("dm.jdbc.driver.DmDriver");
        try(var c=DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"))) {
            String primary=definition(c,"CK_PROC_MAT_STATUS"),guard=definition(c,"CK_PROC_MAT_STATUS_ASYNC");
            boolean valid=(primary.equals(OLD)&&Set.of("MISSING",NEW).contains(guard))
                    || (primary.equals(NEW)&&Set.of("MISSING",NEW).contains(guard))
                    || (primary.equals("MISSING")&&guard.equals(NEW));
            if(!valid) throw new IllegalStateException("unexpected state constraint");
            long count=rows(c);
            if(args[0].equals("check")){System.out.println("ASYNC_SCHEMA_PREFLIGHT=PASS rows="+count);return;}
            if(args[0].equals("verify")) {
                if(!primary.equals(NEW)||!guard.equals("MISSING")) throw new IllegalStateException("migration incomplete");
                System.out.println("ASYNC_SCHEMA_VERIFIED rows="+count);return;
            }
            if(primary.equals(NEW)&&guard.equals("MISSING")){System.out.println("ASYNC_SCHEMA_ALREADY_MATCHING rows="+count);return;}
            Files.writeString(Path.of(args[1]),"rows="+count+"\nconstraint="+primary+"\nguard="+guard+"\n",StandardOpenOption.CREATE_NEW);
            String check="CHECK (STATUS IN ('QUEUED','PROCESSING','PARSED','FAILED','UNKNOWN'))";
            try(var s=c.createStatement()) {
                if(guard.equals("MISSING")) s.execute("ALTER TABLE BUSINESS_MATERIAL_INTAKE ADD CONSTRAINT CK_PROC_MAT_STATUS_ASYNC "+check);
                if(primary.equals(OLD)) s.execute("ALTER TABLE BUSINESS_MATERIAL_INTAKE DROP CONSTRAINT CK_PROC_MAT_STATUS");
                if(!primary.equals(NEW)) s.execute("ALTER TABLE BUSINESS_MATERIAL_INTAKE ADD CONSTRAINT CK_PROC_MAT_STATUS "+check);
                s.execute("ALTER TABLE BUSINESS_MATERIAL_INTAKE DROP CONSTRAINT CK_PROC_MAT_STATUS_ASYNC");
            }
            if(rows(c)!=count || !definition(c,"CK_PROC_MAT_STATUS").equals(NEW)
                    || !definition(c,"CK_PROC_MAT_STATUS_ASYNC").equals("MISSING")) throw new IllegalStateException("verification failed");
            System.out.println("ASYNC_SCHEMA_APPLIED rows_preserved="+count);
        }
    }
}
