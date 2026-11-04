import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Add-only F01 queue migration. Credentials come only from process environment. */
public final class BudgetMaterialMigration {
    static long scalar(Connection c,String sql) throws SQLException {try(var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getLong(1);}}
    static boolean present(Connection c) throws SQLException {return scalar(c,"SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME='BUDGET_MATERIAL_JOB'")==1;}
    static void require(boolean b) {if(!b)throw new IllegalStateException("budget queue schema drift");}
    static void verify(Connection c,boolean requireIndex) throws SQLException {
        var expected=Map.of("ID",36,"ACTOR_ID",64,"OPERATION_KEY",36,"SHA256",64,"STATUS",20,"LEASE_TOKEN",36,"LEASE_UNTIL",0,"UPDATED_AT",0);
        var seen=new HashSet<String>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT COLUMN_NAME,DATA_TYPE,DATA_LENGTH,NULLABLE FROM USER_TAB_COLUMNS WHERE TABLE_NAME='BUDGET_MATERIAL_JOB'")) {
            while(r.next()) {String name=r.getString(1);require(expected.containsKey(name));seen.add(name);
                int size=expected.get(name);require(size==0?r.getString(2).startsWith("TIMESTAMP"):r.getString(2).equals("VARCHAR")&&r.getInt(3)==size);
                require(r.getString(4).equals(Set.of("LEASE_TOKEN","LEASE_UNTIL").contains(name)?"Y":"N"));
            }
        }
        require(seen.equals(expected.keySet()));
        require(scalar(c,"SELECT COUNT(*) FROM USER_CONSTRAINTS WHERE TABLE_NAME='BUDGET_MATERIAL_JOB' AND CONSTRAINT_TYPE='P' AND STATUS='ENABLED'")==1);
        require(scalar(c,"SELECT COUNT(*) FROM USER_CONSTRAINTS WHERE TABLE_NAME='BUDGET_MATERIAL_JOB' AND CONSTRAINT_NAME='UK_BUDGET_MAT_OPERATION' AND CONSTRAINT_TYPE='U' AND STATUS='ENABLED'")==1);
        require(scalar(c,"SELECT COUNT(*) FROM USER_CONSTRAINTS C JOIN USER_CONSTRAINTS R ON C.R_CONSTRAINT_NAME=R.CONSTRAINT_NAME WHERE C.TABLE_NAME='BUDGET_MATERIAL_JOB' AND C.CONSTRAINT_NAME='FK_BUDGET_MAT_TASK' AND C.CONSTRAINT_TYPE='R' AND C.STATUS='ENABLED' AND R.TABLE_NAME='BUDGET_REVIEW_TASK'")==1);
        try(var s=c.createStatement();var r=s.executeQuery("SELECT SEARCH_CONDITION FROM USER_CONSTRAINTS WHERE TABLE_NAME='BUDGET_MATERIAL_JOB' AND CONSTRAINT_NAME='CK_BUDGET_MAT_STATUS' AND STATUS='ENABLED'")) {
            require(r.next());require(r.getString(1).replaceAll("[\\s\\\"()]","").toUpperCase().equals("STATUSIN'QUEUED','PROCESSING','PARSED','FAILED','UNKNOWN'"));
        }
        if(requireIndex) require(scalar(c,"SELECT COUNT(*) FROM USER_INDEXES WHERE TABLE_NAME='BUDGET_MATERIAL_JOB' AND INDEX_NAME='IX_BUDGET_MAT_PENDING'")==1);
    }
    public static void main(String[] args) {
        try {run(args);}catch(SQLException e){System.err.println("BUDGET_MIGRATION_SQL_FAILED code="+e.getErrorCode()+" state="+e.getSQLState());System.exit(1);}
        catch(Exception e){System.err.println("BUDGET_MIGRATION_FAILED type="+e.getClass().getSimpleName());System.exit(1);}
    }
    static void run(String[] args) throws Exception {
        require(args.length==2&&Set.of("check","apply","verify").contains(args[0]));Class.forName("dm.jdbc.driver.DmDriver");
        try(var c=DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"))) {
            long before=scalar(c,"SELECT COUNT(*) FROM BUDGET_REVIEW_TASK");boolean exists=present(c);
            if(exists) verify(c,false);
            if(args[0].equals("check")){System.out.println("BUDGET_QUEUE_PREFLIGHT=PASS state="+(exists?"ALREADY_MATCHING":"NOT_APPLIED"));return;}
            if(args[0].equals("apply")) {
                String sql=Files.readString(Path.of(args[1])).replaceAll("(?m)^--.*$","");
                for(String statement:sql.split(";")) {
                    statement=statement.trim();if(statement.isEmpty())continue;
                    if(statement.startsWith("CREATE TABLE ")&&exists)continue;
                    if(statement.startsWith("CREATE INDEX ")&&scalar(c,"SELECT COUNT(*) FROM USER_INDEXES WHERE INDEX_NAME='IX_BUDGET_MAT_PENDING'")==1)continue;
                    try(var s=c.createStatement()){s.execute(statement);}
                }
            }
            verify(c,true);require(before==scalar(c,"SELECT COUNT(*) FROM BUDGET_REVIEW_TASK"));
            System.out.println("BUDGET_QUEUE_SCHEMA=PASS historical_tasks_preserved="+before);
        }
    }
}
