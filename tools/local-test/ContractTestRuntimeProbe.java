import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Local-only read-only DM8/worker status and controlled fresh-storage proof. */
public class ContractTestRuntimeProbe {
  public static void main(String[] args) {
    try {
      if(args.length!=1||!Set.of("Status","FreshGate","StartPreflight").contains(args[0]))throw new IllegalStateException("BAD_PROBE_MODE");
      String url=System.getenv("AUDIT_DB_URL"),user=System.getenv("AUDIT_DB_USERNAME");
      var expected=ResetReviewTestData.approvedIdentity(ResetReviewTestData.APPROVED_ROOT);ResetReviewTestData.identity(expected,url,user,expected.schema,8);
      try(Connection c=DriverManager.getConnection(url,user,System.getenv("AUDIT_DB_PASSWORD"))){
        c.setAutoCommit(false);c.setReadOnly(true);ResetReviewTestData.identity(expected,url,c.getMetaData().getUserName(),c.getSchema(),c.getMetaData().getDatabaseMajorVersion());
        System.out.println("DM8=AUTHENTICATED_LOCAL_APPROVED_SCHEMA");
        for(String table:List.of("PROJECT_FILE_PROCESSING_JOB","PROJECT_FILE_PROCESSING_ATTEMPT","CONTRACT_REVIEW_RUN","CONTRACT_REVIEW_STEP_ATTEMPT"))if(count(c,"SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME='"+table+"'")!=1)throw new IllegalStateException("WORKER_SCHEMA_NOT_APPLIED");
        for(String name:List.of("PK_PF_PROCESSING_JOB","CK_PF_PROCESSING_STATE","CK_PF_PROCESSING_LEASE","UK_PF_PROCESSING_FILE","FK_PF_PROCESSING_FILE","FK_PF_PROCESSING_ACTOR","FK_PF_PROCESSING_PARSE","PK_PF_PROCESSING_ATTEMPT","UK_PF_PROCESSING_LEASE","FK_PF_PROCESSING_JOB","CK_PF_PROCESSING_ATT_STATE"))if(count(c,"SELECT COUNT(*) FROM USER_CONSTRAINTS WHERE CONSTRAINT_NAME='"+name+"' AND STATUS='ENABLED'")!=1)throw new IllegalStateException("WORKER_SCHEMA_CONSTRAINT_UNPROVEN");
        System.out.println("CONTRACT_FILE_PROCESSING_SCHEMA=VERIFIED_REQUIRED_CONSTRAINTS");
        boolean word97=word97Supported(c);
        System.out.println("WORD97_SCHEMA="+(word97?"VERIFIED_DOC_TEXT_CHECKS_AND_NULLABILITY":"NOT_APPLIED_OR_UNPROVEN"));
        if(args[0].equals("StartPreflight")&&!word97)throw new IllegalStateException("WORD97_SCHEMA_NOT_APPLIED_START_BLOCKED");
        String semantic=Objects.toString(System.getenv("AUDIT_CONTRACT_SEMANTIC_CHECKPOINTS_ENABLED"),"false").trim().toLowerCase(Locale.ROOT);
        if(!Set.of("true","false").contains(semantic))throw new IllegalStateException("SEMANTIC_FLAG_NOT_BOOLEAN");
        if(semantic.equals("true"))SemanticCheckpointCatalog.verify(c,ResetReviewTestData.APPROVED_ROOT);
        else System.out.println("SEMANTIC_CHECKPOINTS=LEGACY_DISABLED_NOT_ACCEPTED");
        states(c,"FILE_PROCESSING_JOBS","PROJECT_FILE_PROCESSING_JOB");states(c,"FILE_PROCESSING_ATTEMPTS","PROJECT_FILE_PROCESSING_ATTEMPT");states(c,"REVIEW_RUNS","CONTRACT_REVIEW_RUN");states(c,"REVIEW_STEP_ATTEMPTS","CONTRACT_REVIEW_STEP_ATTEMPT");
        if(args[0].equals("FreshGate")){for(String table:List.of("PROJECT_FILE","CONTRACT_REVIEW_DOCUMENT","CONTRACT_REVIEW_REPORT","SANITIZED_DEBUG_BUNDLE","PROJECT_FILE_PROCESSING_JOB"))if(count(c,"SELECT COUNT(*) FROM "+ResetReviewTestData.quote(table))!=0)throw new IllegalStateException("FRESH_STORAGE_DURABLE_REFERENCES_NOT_EMPTY");System.out.println("FRESH_STORAGE_DURABLE_EMPTY_GATE=PASS");}
        c.rollback();
      }
    }catch(Exception e){System.out.println("RUNTIME_PROBE=BLOCKED gate="+(e instanceof SQLException s?"JDBC_"+s.getErrorCode():ResetReviewTestData.safeGate(e.getMessage())));System.exit(1);}
  }
  static long count(Connection c,String sql)throws Exception{try(Statement s=c.createStatement()){s.setQueryTimeout(10);try(ResultSet r=s.executeQuery(sql)){if(!r.next())throw new IllegalStateException("UNKNOWN_CATALOG_RESULT");return r.getLong(1);}}}
  static boolean word97Supported(Connection c)throws Exception{
    for(String table:List.of("PROJECT_FILE","PROJECT_FILE_PARSE_RESULT")){
      boolean nullable=table.equals("PROJECT_FILE");
      String name=nullable?"CK_PROJECT_FILE_PARSE_METHOD":"CK_PROJECT_FILE_PARSE_RESULT_METHOD";
      try(var s=c.prepareStatement("SELECT CONSTRAINT_TYPE,STATUS,SEARCH_CONDITION FROM USER_CONSTRAINTS WHERE TABLE_NAME=? AND CONSTRAINT_NAME=?")){
        s.setQueryTimeout(10);s.setString(1,table);s.setString(2,name);try(var r=s.executeQuery()){
          if(!r.next()||!"C".equals(r.getString(1))||!"ENABLED".equals(r.getString(2))||!word97Check(r.getString(3),nullable)||r.next())return false;
        }
      }
      try(var s=c.prepareStatement("SELECT DATA_TYPE,DATA_LENGTH,NULLABLE FROM USER_TAB_COLUMNS WHERE TABLE_NAME=? AND COLUMN_NAME='PARSE_METHOD'")){
        s.setQueryTimeout(10);s.setString(1,table);try(var r=s.executeQuery()){
          if(!r.next()||!"VARCHAR".equals(r.getString(1))||r.getInt(2)!=32||!(nullable?"Y":"N").equals(r.getString(3))||r.next())return false;
        }
      }
      if(count(c,"SELECT COUNT(*) FROM USER_CONSTRAINTS WHERE TABLE_NAME='"+table+"' AND CONSTRAINT_NAME='"+name+"_DOC_GUARD'")!=0)return false;
    }
    return true;
  }
  static boolean word97Check(String condition,boolean nullable){
    if(condition==null)return false;
    String expected=(nullable?"PARSE_METHODISNULLOR":"")+"PARSE_METHODIN'PDF_TEXT','DOCX_TEXT','OCR','DOC_TEXT'";
    return condition.replaceAll("[\\s()\"]","").equals(expected);
  }
  static void states(Connection c,String label,String table)throws Exception{try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT STATUS,COUNT(*) FROM "+ResetReviewTestData.quote(table)+" GROUP BY STATUS ORDER BY STATUS")){StringJoiner j=new StringJoiner(",");while(r.next()){String status=r.getString(1);if(!status.matches("[A-Z_]{1,30}"))status="UNKNOWN";j.add(status+":"+r.getLong(2));}System.out.println(label+"="+j);}}
}
