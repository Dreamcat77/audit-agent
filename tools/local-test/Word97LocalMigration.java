import java.nio.file.*;
import java.sql.*;
import java.util.*;
/** Read-only planning/verification only. Automated Apply is intentionally unavailable. */
public class Word97LocalMigration {
  static final List<String> TABLES=List.of("PROJECT_FILE","PROJECT_FILE_PARSE_RESULT");
  static String name(String table){if(!TABLES.contains(table))throw new IllegalStateException("WORD97_UNAPPROVED_TABLE");return table.equals("PROJECT_FILE")?"CK_PROJECT_FILE_PARSE_METHOD":"CK_PROJECT_FILE_PARSE_RESULT_METHOD";}
  static String expression(String table,boolean expanded){name(table);return (table.equals("PROJECT_FILE")?"PARSE_METHOD IS NULL OR ":"")+"PARSE_METHOD IN ('PDF_TEXT','DOCX_TEXT','OCR'"+(expanded?",'DOC_TEXT'":"")+")";}
  static List<String> steps(String table,String current,String guard){
    String name=name(table),temporary=name+"_DOC_GUARD";
    if(!Set.of("OLD","NEW","MISSING").contains(current)||!Set.of("NEW","MISSING").contains(guard)||(current.equals("MISSING")&&guard.equals("MISSING")))throw new IllegalStateException("WORD97_UNKNOWN_CONSTRAINT_STATE");
    if(current.equals("NEW")&&guard.equals("MISSING"))return List.of();
    List<String> plan=new ArrayList<>();
    if(guard.equals("MISSING"))plan.add("ALTER TABLE "+table+" ADD CONSTRAINT "+temporary+" CHECK ("+expression(table,true)+")");
    if(current.equals("OLD"))plan.add("ALTER TABLE "+table+" DROP CONSTRAINT "+name);
    if(!current.equals("NEW"))plan.add("ALTER TABLE "+table+" ADD CONSTRAINT "+name+" CHECK ("+expression(table,true)+")");
    plan.add("ALTER TABLE "+table+" DROP CONSTRAINT "+temporary);return List.copyOf(plan);
  }
  static void column(String table,String type,int length,String nullable){name(table);if(!"VARCHAR".equals(type)||length!=32||!(table.equals("PROJECT_FILE")?"Y":"N").equals(nullable))throw new IllegalStateException("WORD97_COLUMN_TYPE_OR_NULLABILITY_DRIFT");}
  static void readOnlyMode(String mode){if(!Set.of("Plan","Verify").contains(mode))throw new IllegalStateException("WORD97_APPLY_UNAVAILABLE_MAINTENANCE_BACKUP_JOURNAL_REQUIRED");}
  static String definition(Connection c,String table,String constraint)throws Exception{
    try(var s=c.prepareStatement("SELECT CONSTRAINT_TYPE,STATUS,SEARCH_CONDITION FROM USER_CONSTRAINTS WHERE TABLE_NAME=? AND CONSTRAINT_NAME=?")){
      s.setQueryTimeout(10);s.setString(1,table);s.setString(2,constraint);
      try(var r=s.executeQuery()){
        if(!r.next())return "MISSING";
        String type=r.getString(1),status=r.getString(2),condition=r.getString(3);
        if(r.next()||!"C".equals(type)||!"ENABLED".equals(status)||condition==null)throw new IllegalStateException("WORD97_CONSTRAINT_DISABLED_OR_UNKNOWN");
        String actual=ContractLocalMigration.canonical(condition);
        if(actual.equals(ContractLocalMigration.canonical(expression(table,true))))return "NEW";
        if(actual.equals(ContractLocalMigration.canonical(expression(table,false))))return "OLD";
        throw new IllegalStateException("WORD97_CONSTRAINT_DEFINITION_DRIFT");
      }
    }
  }
  static List<String> catalog(Connection c)throws Exception{
    List<String> state=new ArrayList<>();
    for(String table:TABLES){
      try(var s=c.prepareStatement("SELECT DATA_TYPE,DATA_LENGTH,NULLABLE FROM USER_TAB_COLUMNS WHERE TABLE_NAME=? AND COLUMN_NAME='PARSE_METHOD'")){
        s.setQueryTimeout(10);s.setString(1,table);try(var r=s.executeQuery()){if(!r.next())throw new IllegalStateException("WORD97_PARSE_METHOD_COLUMN_MISSING");column(table,r.getString(1),r.getInt(2),r.getString(3));if(r.next())throw new IllegalStateException("WORD97_COLUMN_DUPLICATE");}
      }
      String current=definition(c,table,name(table)),guard=definition(c,table,name(table)+"_DOC_GUARD");steps(table,current,guard);state.add(current);state.add(guard);
    }
    return List.copyOf(state);
  }
  static void approvedSql(Path repo)throws Exception{
    String sql=Files.readString(repo.resolve("db/dm8/CONTRACT__project_file_word97_method.sql"));
    if(sql.indexOf('\r')>=0)throw new IllegalStateException("WORD97_SQL_REQUIRES_LF");
    List<String> expected=new ArrayList<>();for(String table:TABLES)expected.addAll(steps(table,"OLD","MISSING"));
    var actual=ContractLocalMigration.statements(sql);
    if(!actual.stream().map(ContractLocalMigration::canonical).toList().equals(expected.stream().map(ContractLocalMigration::canonical).toList()))throw new IllegalStateException("WORD97_OPERATOR_SQL_DRIFT");
  }
  public static void main(String[] args){try{run(args);}catch(Exception e){System.out.println("WORD97_MIGRATION=BLOCKED NOT_APPLIED gate="+(e instanceof SQLException s?"JDBC_"+s.getErrorCode():ResetReviewTestData.safeGate(e.getMessage())));System.exit(1);}}
  static void run(String[] args)throws Exception{
    if(args.length!=2)throw new IllegalStateException("WORD97_BAD_ARGUMENTS");readOnlyMode(args[0]);
    Path repo=Path.of(args[1]).toAbsolutePath().normalize();approvedSql(repo);
    // This historical authority proves database identity only. It is NOT a current migration backup.
    var expected=ResetReviewTestData.approvedIdentity(repo);String url=System.getenv("AUDIT_DB_URL"),user=System.getenv("AUDIT_DB_USERNAME");ResetReviewTestData.identity(expected,url,user,expected.schema,8);DriverManager.setLoginTimeout(10);
    try(Connection c=DriverManager.getConnection(url,user,System.getenv("AUDIT_DB_PASSWORD"))){
      c.setAutoCommit(false);c.setReadOnly(true);ResetReviewTestData.identity(expected,url,c.getMetaData().getUserName(),c.getSchema(),c.getMetaData().getDatabaseMajorVersion());
      if(!c.getMetaData().getDatabaseProductName().toUpperCase(Locale.ROOT).contains("DM"))throw new IllegalStateException("WORD97_NOT_DM8");
      var before=catalog(c);var after=catalog(c);if(!before.equals(after))throw new IllegalStateException("WORD97_CATALOG_OUTCOME_UNKNOWN_REOPEN");
      int count=0;for(int i=0;i<TABLES.size();i++){var plan=steps(TABLES.get(i),after.get(i*2),after.get(i*2+1));count+=plan.size();System.out.println("WORD97_TABLE="+TABLES.get(i)+" CONSTRAINT="+after.get(i*2)+" GUARD="+after.get(i*2+1)+" REMAINING_STEPS="+plan.size());}
      c.rollback();
      if(args[0].equals("Verify")&&count!=0)throw new IllegalStateException("WORD97_SCHEMA_NOT_APPLIED");
      System.out.println("WORD97_"+args[0].toUpperCase(Locale.ROOT)+"=PASS TOOL_DDL_EXECUTED=false REMAINING_STEPS="+count+" OPERATOR_ONLY_SQL_NOT_APPLIED");
    }
  }
}
