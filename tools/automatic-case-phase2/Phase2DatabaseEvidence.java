import java.sql.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** Read-only catalog/data evidence, restricted to the separate acceptance database. */
public class Phase2DatabaseEvidence {
 public static void main(String[] args)throws Exception {
  if(!"ISOLATED_AUTOCASE2".equals(System.getenv("AUDIT_CASE_TEST_DB_CONFIRM")))throw new IllegalStateException("isolated database required");
  var json=JsonMapper.builder().findAndAddModules().build();var output=new LinkedHashMap<String,Object>();
  try(var c=DriverManager.getConnection(System.getenv("AUDIT_CASE_TEST_DB_URL"),System.getenv("AUDIT_CASE_TEST_DB_USERNAME"),System.getenv("AUDIT_CASE_TEST_DB_PASSWORD"))) {
   if(!"AUTOCASE_APP".equals(c.getSchema()))throw new IllegalStateException("wrong schema");
   output.put("environment","separate AUTOCASE2 instance / AUTOCASE_APP schema / real DM8");
   var tables=new LinkedHashMap<String,Object>();
   for(String table:List.of("AUDIT_CASE","AUDIT_CASE_FILE","AUDIT_FACT","AUDIT_QUOTE_LINE")) {
    var digest=MessageDigest.getInstance("SHA-256");long count=0;
    try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+table+" ORDER BY ID")) {
     while(r.next()){count++;for(int i=1;i<=r.getMetaData().getColumnCount();i++) {
      String value=r.getString(i);byte[] bytes=value==null?new byte[0]:value.getBytes(StandardCharsets.UTF_8);
      digest.update((value==null?"null:":bytes.length+":").getBytes(StandardCharsets.UTF_8));digest.update(bytes);
     }}
    }
    tables.put(table,Map.of("rows",count,"sha256",HexFormat.of().formatHex(digest.digest())));
   }
   output.put("tables",tables);
   for(String catalog:List.of("USER_TAB_COLUMNS","USER_CONSTRAINTS","USER_CONS_COLUMNS","USER_INDEXES","USER_IND_COLUMNS")) {
    var rows=new ArrayList<Map<String,Object>>();
    String fields=switch(catalog) {
     case "USER_TAB_COLUMNS"->"TABLE_NAME,COLUMN_NAME,DATA_TYPE,DATA_LENGTH,DATA_PRECISION,DATA_SCALE,NULLABLE";
     case "USER_CONSTRAINTS"->"TABLE_NAME,CONSTRAINT_NAME,CONSTRAINT_TYPE,STATUS,SEARCH_CONDITION,R_CONSTRAINT_NAME";
     case "USER_CONS_COLUMNS"->"TABLE_NAME,CONSTRAINT_NAME,COLUMN_NAME,POSITION";
     case "USER_INDEXES"->"TABLE_NAME,INDEX_NAME,UNIQUENESS";
     default->"TABLE_NAME,INDEX_NAME,COLUMN_NAME,COLUMN_POSITION";
    };
    try(var s=c.createStatement();var r=s.executeQuery("SELECT "+fields+" FROM "+catalog+" WHERE TABLE_NAME IN ('AUDIT_CASE','AUDIT_CASE_FILE','AUDIT_FACT','AUDIT_QUOTE_LINE') ORDER BY TABLE_NAME")) {
     while(r.next()){var row=new LinkedHashMap<String,Object>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.put(r.getMetaData().getColumnLabel(i),r.getString(i));rows.add(row);}
    }
    output.put(catalog,rows);
   }
   var cases=new ArrayList<Map<String,Object>>();
   try(var s=c.createStatement();var r=s.executeQuery("SELECT ID,STATUS,ROW_VERSION,SNAPSHOT_JSON FROM AUDIT_CASE ORDER BY CREATED_AT")) {
    while(r.next()) {
     var row=new LinkedHashMap<String,Object>();row.put("id",r.getString(1));row.put("status",r.getString(2));row.put("version",r.getLong(3));row.put("attempts",json.readTree(r.getString(4)).path("attempts").asInt());
     for(String table:List.of("AUDIT_CASE_FILE","AUDIT_FACT","AUDIT_QUOTE_LINE"))try(var p=c.prepareStatement("SELECT COUNT(*) FROM "+table+" WHERE CASE_ID=?")){p.setString(1,r.getString(1));try(var n=p.executeQuery()){n.next();row.put(table,n.getLong(1));}}
     cases.add(row);
    }
   }
   output.put("cases",cases);
   try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM AUTO_CASE_TEST_AUDIT")){r.next();output.put("testDurableOperationAuditRows",r.getLong(1));}
  }
  System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(output));
 }
}
