import java.sql.*;

/** Synthetic-only status probe; prints state/identifiers, never document text. */
public final class QuerySyntheticContractState {
  public static void main(String[] args) throws Exception {
    try (Connection c = DriverManager.getConnection(System.getenv("AUDIT_DB_URL"), System.getenv("AUDIT_DB_USERNAME"), System.getenv("AUDIT_DB_PASSWORD"))) {
      if (args.length > 1) {
        query(c, "SELECT STATUS,REASON_CODE,ATTEMPT_NO,PARSE_RESULT_ID FROM PROJECT_FILE_PROCESSING_JOB WHERE FILE_ID=?", args[1]);
      } else {
        query(c, "SELECT STATUS,REASON_CODE,ATTEMPT_NO,PARSE_RESULT_ID FROM PROJECT_FILE_PROCESSING_JOB WHERE FILE_ID IN (SELECT ID FROM PROJECT_FILE WHERE PROJECT_ID=?)", args[0]);
      }
      query(c, "SELECT COUNT(*) AS C FROM CONTRACT_CANDIDATE WHERE PROJECT_ID=?", args[0]);
      query(c, "SELECT PROMOTION_STATE,VERSION_NO,SOURCE_AUTHORITY_SOURCE FROM CONTRACT_CANDIDATE WHERE PROJECT_ID=?", args[0]);
      query(c, "SELECT COUNT(*) AS C FROM PROJECT_FILE_ROLE_DECISION WHERE PROJECT_ID=?", args[0]);
      query(c, "SELECT SELECTED_ROLE,DECISION_STATUS,VERSION_NO,PARSE_VERSION FROM PROJECT_FILE_ROLE_DECISION WHERE PROJECT_ID=?", args[0]);
      query(c, "SELECT COUNT(*) AS C FROM PROJECT_FILE_IDENTITY_DECISION WHERE PROJECT_ID=?", args[0]);
      query(c, "SELECT RESOLUTION,VERSION_NO,PARSE_VERSION FROM PROJECT_FILE_IDENTITY_DECISION WHERE PROJECT_ID=?", args[0]);
      query(c, "SELECT COUNT(*) AS C FROM PROJECT_FACT_EXTRACTION_ATTEMPT WHERE PROJECT_ID=?", args[0]);
      query(c, "SELECT COUNT(*) AS C FROM PROJECT_CONTRACT WHERE PROJECT_ID=?", args[0]);
    }
  }
  private static void query(Connection c, String sql, String value) throws Exception {
    try (PreparedStatement p=c.prepareStatement(sql)) { p.setString(1,value); try(ResultSet r=p.executeQuery()) {
      ResultSetMetaData m=r.getMetaData(); while(r.next()) { StringBuilder b=new StringBuilder(); for(int i=1;i<=m.getColumnCount();i++){ if(i>1)b.append(' '); b.append(m.getColumnName(i)).append('=').append(r.getString(i)); } System.out.println(b); }
    }}
  }
}
