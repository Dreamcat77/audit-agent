import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.*;

/** Read-only acceptance probe, strictly restricted to the independent pilot. */
public final class PilotReadOnlyCheck {
    static List<Map<String,Object>> rows(Connection c,String sql,String... args)throws Exception {
        try(var p=c.prepareStatement(sql)) {
            p.setQueryTimeout(25);for(int i=0;i<args.length;i++)p.setString(i+1,args[i]);
            try(var r=p.executeQuery()) {
                var list=new ArrayList<Map<String,Object>>();var m=r.getMetaData();
                while(r.next()){var row=new LinkedHashMap<String,Object>();for(int i=1;i<=m.getColumnCount();i++)row.put(m.getColumnLabel(i),r.getObject(i));list.add(row);}return list;
            }
        }
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("CASE_ID_REQUIRED");
        if(!"jdbc:dm://127.0.0.1:15287".equals(System.getenv("AUDIT_DB_URL"))||!"AUTOCASE_APP".equals(System.getenv("AUDIT_DB_USERNAME")))throw new IllegalStateException("ISOLATED_DATABASE_REQUIRED");
        Class.forName("dm.jdbc.driver.DmDriver");
        try(var c=DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"))) {
            c.setReadOnly(true);
            var out=new LinkedHashMap<String,Object>();out.put("readOnly",true);out.put("instance","AUTOCASE2");
            out.put("case",rows(c,"SELECT ID,STATUS,ROW_VERSION FROM AUDIT_CASE WHERE ID=?",args[0]));
            out.put("documents",rows(c,"SELECT STATUS,COUNT(*) CNT FROM AUDIT_CASE_FILE WHERE CASE_ID=? GROUP BY STATUS",args[0]));
            out.put("facts",rows(c,"SELECT FACT_TYPE,VERIFICATION,COUNT(*) CNT FROM AUDIT_FACT WHERE CASE_ID=? GROUP BY FACT_TYPE,VERIFICATION ORDER BY FACT_TYPE",args[0]));
            out.put("quotes",rows(c,"SELECT COUNT(*) CNT,SUM(LINE_AMOUNT) TOTAL FROM AUDIT_QUOTE_LINE WHERE CASE_ID=?",args[0]));
            out.put("ledger",rows(c,"SELECT STATUS,COUNT(*) CNT,SUM(CALL_COUNT) CALLS FROM MATERIAL_EXTRACTION_LEDGER GROUP BY STATUS"));
            out.put("operationAudit",rows(c,"SELECT ACTION,RESULT_STATUS,COUNT(*) CNT FROM AUDIT_OPERATION_LOG WHERE RESOURCE_ID=? GROUP BY ACTION,RESULT_STATUS",args[0]));
            out.put("supportTableCounts",rows(c,"SELECT COUNT(*) CNT FROM USER_TABLES WHERE TABLE_NAME IN ('SYS_USER','SYS_ROLE','SYS_USER_ROLE','AUTH_LOGIN_AUDIT','AUDIT_OPERATION_LOG','CONTRACT','CONTRACT_REVIEW_SESSION','PROCUREMENT_TENDER_RULE','MATERIAL_EXTRACTION_LEDGER')"));
            System.out.println(new ObjectMapper().writeValueAsString(out));
        }
    }
}
