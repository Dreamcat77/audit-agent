import java.nio.file.*;
import java.sql.*;

/** Controlled additive migration. Never prints credentials or business rows. */
public class ProcurementQuoteComparisonMigration {
    public static void main(String[] args) {
        try { run(args); }
        catch (SQLException ex) { System.err.println("DM8_FAILED code="+ex.getErrorCode()+" state="+ex.getSQLState()); System.exit(1); }
        catch(Exception ex) { System.err.println("MIGRATION_FAILED type="+ex.getClass().getSimpleName()); System.exit(1); }
    }
    static void run(String[] args) throws Exception {
        Class.forName("dm.jdbc.driver.DmDriver");
        try(var c=DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"))) {
            int table=count(c,"SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME='PROCUREMENT_QUOTE_COMPARISON'");
            int visible=count(c,"SELECT COUNT(*) FROM ALL_TABLES WHERE TABLE_NAME='PROCUREMENT_QUOTE_COMPARISON'");
            if (visible != table) throw new IllegalStateException("snapshot name is owned by another schema");
            int demands=count(c,"SELECT COUNT(*) FROM PROCUREMENT_DEMAND");
            int checks=count(c,"SELECT COUNT(*) FROM PROCUREMENT_QUOTE_CHECK");
            System.out.println("QUOTE_PREFLIGHT connected=true ownedSnapshotTables="+table+" demands="+demands+" checks="+checks);
            if(args[0].equals("preflight"))return;
            if (!args[0].equals("apply") && !args[0].equals("verify")) throw new IllegalArgumentException();
            if(args[0].equals("apply")) {
                if(table!=0 || args.length!=3)throw new IllegalStateException();
                Path backup=Path.of(args[2]);Files.createDirectories(backup);
                // Recoverable state before adding one empty owned table; no legacy table altered.
                String ddl=Files.readString(Path.of(args[1]));
                Files.writeString(backup.resolve("comparison-ddl.sql"),ddl,StandardOpenOption.CREATE_NEW);
                Files.writeString(backup.resolve("preflight.txt"),"demands="+demands+" checks="+checks+"\n",StandardOpenOption.CREATE_NEW);
                String sql=ddl.replaceAll("(?m)^\\s*--[^\\r\\n]*", "").strip();
                if(!sql.startsWith("CREATE TABLE PROCUREMENT_QUOTE_COMPARISON") || sql.indexOf(';')!=sql.length()-1)throw new IllegalStateException();
                try(var statement=c.createStatement()) {statement.execute(sql.substring(0,sql.length()-1));}
            }
            if(count(c,"SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME='PROCUREMENT_QUOTE_COMPARISON'")!=1
                    || count(c,"SELECT COUNT(*) FROM USER_TAB_COLUMNS WHERE TABLE_NAME='PROCUREMENT_QUOTE_COMPARISON'")!=2
                    || count(c,"SELECT COUNT(*) FROM USER_TAB_COLUMNS WHERE TABLE_NAME='PROCUREMENT_QUOTE_COMPARISON' AND ((COLUMN_NAME='CHECK_ID' AND DATA_TYPE='VARCHAR' AND DATA_LENGTH=36) OR (COLUMN_NAME='REPORT_JSON' AND DATA_TYPE='CLOB')) AND NULLABLE='N'")!=2
                    || count(c,"SELECT COUNT(*) FROM USER_CONSTRAINTS WHERE TABLE_NAME='PROCUREMENT_QUOTE_COMPARISON' AND CONSTRAINT_TYPE='P'")!=1
                    || count(c,"SELECT COUNT(*) FROM PROCUREMENT_DEMAND")!=demands
                    || count(c,"SELECT COUNT(*) FROM PROCUREMENT_QUOTE_CHECK")!=checks)throw new IllegalStateException();
            System.out.println("QUOTE_SCHEMA_VERIFIED legacyCountsUnchanged=true");
        }
    }
    static int count(Connection c,String sql)throws Exception{try(var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getInt(1);}}
}
