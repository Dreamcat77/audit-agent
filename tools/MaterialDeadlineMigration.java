import java.sql.*;
import java.util.*;

/** V057 additive migration. Never changes existing task state or schema drift. */
public final class MaterialDeadlineMigration {
    public static void main(String[] args)throws Exception {
        if(args.length!=1||!Set.of("check","apply").contains(args[0]))throw new IllegalArgumentException("mode");
        Class.forName("dm.jdbc.driver.DmDriver");
        try(var c=DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"))) {
            boolean exists=migrate(c,"MATERIAL_EXTRACTION_LEDGER",args[0].equals("apply"));
            System.out.println("V057="+(exists?"SCHEMA_MATCHING":"NOT_APPLIED"));
        }catch(SQLException error){System.err.println("V057_SQL_FAILED code="+error.getErrorCode()+" state="+error.getSQLState());System.exit(1);}
    }
    static boolean migrate(Connection c,String table,boolean apply)throws SQLException {
        if(!table.equals("MATERIAL_EXTRACTION_LEDGER")&&!table.matches("AXP_[A-F0-9]{16}"))throw new IllegalArgumentException("table");
            try(var p=c.prepareStatement("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME=?")) {
                p.setString(1,table);try(var r=p.executeQuery()) {
                r.next();if(r.getInt(1)!=1)throw new IllegalStateException("V057_LEDGER_TABLE_REQUIRED");
                }
            }
            boolean exists=false;
            try(var p=c.prepareStatement("SELECT DATA_TYPE,NULLABLE,DATA_DEFAULT FROM USER_TAB_COLUMNS WHERE TABLE_NAME=? AND COLUMN_NAME='DEADLINE_AT'")) {
                p.setString(1,table);try(var r=p.executeQuery()) {
                if(r.next()){exists=true;if(!r.getString(1).equals("TIMESTAMP")||!r.getString(2).equals("Y")||!Objects.toString(r.getString(3),"").isBlank()||r.next())throw new IllegalStateException("V057_DEADLINE_SCHEMA_DRIFT");}
                }
            }
            if(!exists&&apply)try(var s=c.createStatement()){s.execute("ALTER TABLE "+table+" ADD DEADLINE_AT TIMESTAMP");return migrate(c,table,false);}
            return exists;
    }
}
