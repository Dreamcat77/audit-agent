import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Narrow V048 migration; retain a range guard throughout nontransactional DM8 DDL. */
public final class MaterialOrderMigration {
    static final String OLD="MATERIAL_ORDERBETWEEN1AND10", NEW="MATERIAL_ORDERBETWEEN1AND100";
    static String definition(Connection c,String name) throws SQLException {
        try(var s=c.prepareStatement("SELECT STATUS,SEARCH_CONDITION FROM USER_CONSTRAINTS WHERE TABLE_NAME='PROCUREMENT_DEMAND_MATERIAL' AND CONSTRAINT_NAME=?")) {
            s.setString(1,name);try(var r=s.executeQuery()) {
                if(!r.next())return "MISSING";
                if(!"ENABLED".equals(r.getString(1)))throw new IllegalStateException();
                return r.getString(2).replaceAll("[\\s\\\"()]", "").toUpperCase();
            }
        }
    }
    static long rows(Connection c)throws SQLException {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM PROCUREMENT_DEMAND_MATERIAL")){r.next();return r.getLong(1);}
    }
    public static void main(String[] args) {
        try{run(args);}catch(SQLException e){System.err.println("ORDER_MIGRATION_SQL_FAILED code="+e.getErrorCode()+" state="+e.getSQLState());System.exit(1);}
        catch(Exception e){System.err.println("ORDER_MIGRATION_FAILED type="+e.getClass().getSimpleName());System.exit(1);}
    }
    static void run(String[] args)throws Exception {
        if(args.length!=2||!Set.of("check","apply","verify").contains(args[0]))throw new IllegalArgumentException();
        Class.forName("dm.jdbc.driver.DmDriver");
        try(var c=DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"))) {
            String name="CK_PROC_DEMAND_MATERIAL_ORDER",temporary="CK_PROC_DEMAND_MATERIAL_RANGE100";
            String primary=definition(c,name),guard=definition(c,temporary);
            if(!((Set.of(OLD,NEW).contains(primary)&&Set.of("MISSING",NEW).contains(guard))||(primary.equals("MISSING")&&guard.equals(NEW))))throw new IllegalStateException();
            long count=rows(c);
            if(args[0].equals("check")){System.out.println("ORDER_SCHEMA_PREFLIGHT=PASS rows="+count);return;}
            if(args[0].equals("verify")){
                if(!primary.equals(NEW)||!guard.equals("MISSING"))throw new IllegalStateException();
                System.out.println("ORDER_SCHEMA_VERIFIED rows="+count);return;
            }
            if(primary.equals(NEW)&&guard.equals("MISSING")){System.out.println("ORDER_SCHEMA_ALREADY_MATCHING rows="+count);return;}
            Files.writeString(Path.of(args[1]),"rows="+count+"\nconstraint="+primary+"\nguard="+guard+"\n",StandardOpenOption.CREATE_NEW);
            try(var s=c.createStatement()){
                if(guard.equals("MISSING"))s.execute("ALTER TABLE PROCUREMENT_DEMAND_MATERIAL ADD CONSTRAINT "+temporary+" CHECK (MATERIAL_ORDER BETWEEN 1 AND 100)");
                if(primary.equals(OLD))s.execute("ALTER TABLE PROCUREMENT_DEMAND_MATERIAL DROP CONSTRAINT "+name);
                if(!primary.equals(NEW))s.execute("ALTER TABLE PROCUREMENT_DEMAND_MATERIAL ADD CONSTRAINT "+name+" CHECK (MATERIAL_ORDER BETWEEN 1 AND 100)");
                s.execute("ALTER TABLE PROCUREMENT_DEMAND_MATERIAL DROP CONSTRAINT "+temporary);
            }
            if(rows(c)!=count||!definition(c,name).equals(NEW)||!definition(c,temporary).equals("MISSING"))throw new IllegalStateException();
            System.out.println("ORDER_SCHEMA_APPLIED rows_preserved="+count);
        }
    }
}
