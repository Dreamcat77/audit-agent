import java.sql.*;
import java.util.*;

/** V049: inspect both columns before widening; never truncate or alter business rows. */
public final class BudgetSubjectWidthMigration {
    static final List<String> COLUMNS=List.of("SUBJECT_NAME","APPROVAL_SUBJECT_NAME");
    static Map<String,Integer> widths(Connection c) throws SQLException {
        var result=new HashMap<String,Integer>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT COLUMN_NAME,DATA_TYPE,DATA_LENGTH FROM USER_TAB_COLUMNS WHERE TABLE_NAME='BUDGET_REVIEW_ITEM' AND COLUMN_NAME IN ('SUBJECT_NAME','APPROVAL_SUBJECT_NAME')")) {
            while(r.next()) {
                if(!"VARCHAR".equals(r.getString(2))) throw new IllegalStateException("unexpected type");
                int width=r.getInt(3);
                if(width!=200 && width<800) throw new IllegalStateException("unexpected width");
                result.put(r.getString(1),width);
            }
        }
        if(!result.keySet().equals(new HashSet<>(COLUMNS))) throw new IllegalStateException("unknown columns");
        return result;
    }
    static long rows(Connection c) throws SQLException {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM BUDGET_REVIEW_ITEM")){r.next();return r.getLong(1);}
    }
    static void run(Connection c,String mode) throws SQLException {
        var before=widths(c);long count=rows(c);
        if(mode.equals("check")) {System.out.println("BUDGET_WIDTH_PREFLIGHT=PASS needs_widening="+before.containsValue(200));return;}
        if(mode.equals("apply")) try(var s=c.createStatement()) {
            for(String column:COLUMNS) if(before.get(column)==200)
                s.execute("ALTER TABLE BUDGET_REVIEW_ITEM MODIFY "+column+" VARCHAR(800)");
        }
        var after=widths(c);
        if(after.values().stream().anyMatch(v->v<800)||rows(c)!=count) throw new IllegalStateException("width verification failed");
        System.out.println("BUDGET_WIDTH_SCHEMA=PASS historical_items_preserved="+count);
    }
    public static void main(String[] args) {
        try {
            if(args.length!=1||!Set.of("check","apply","verify").contains(args[0]))throw new IllegalArgumentException();
            Class.forName("dm.jdbc.driver.DmDriver");DriverManager.setLoginTimeout(10);
            try(var c=DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"))){run(c,args[0]);}
        } catch(SQLException e){System.err.println("BUDGET_WIDTH_SQL_FAILED code="+e.getErrorCode()+" state="+safeState(e.getSQLState()));System.exit(1);}
        catch(Exception e){System.err.println("BUDGET_WIDTH_FAILED type="+e.getClass().getSimpleName());System.exit(1);}
    }
    static String safeState(String value){return value!=null&&value.matches("[A-Za-z0-9]{1,10}")?value:"NONE";}
}
