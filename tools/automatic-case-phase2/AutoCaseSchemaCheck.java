import java.sql.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Isolated acceptance migrator: complete absence -> apply, complete match -> no-op, all unknown/partial states stop. */
public class AutoCaseSchemaCheck {
 static final Set<String> TABLES=Set.of("AUDIT_CASE","AUDIT_CASE_FILE","AUDIT_FACT","AUDIT_QUOTE_LINE");
 public static void main(String[] args)throws Exception {
  if(!"ISOLATED_AUTOCASE2".equals(System.getenv("AUDIT_CASE_TEST_DB_CONFIRM")))throw new IllegalArgumentException("isolated database confirmation required");
  Class.forName("dm.jdbc.driver.DmDriver");
  try(var c=DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"))) {
   check(c,Path.of(args[0]),args.length>1&&args[1].equals("apply"));
  }
 }
 public static void check(Connection c,Path script,boolean apply)throws Exception {
  if(!"ISOLATED_AUTOCASE2".equals(System.getenv("AUDIT_CASE_TEST_DB_CONFIRM")))throw new IllegalArgumentException("isolated database confirmation required");
  String schema=c.getSchema();if(schema==null)schema=System.getenv("AUDIT_DB_USERNAME");
  if(!schema.equals("AUTOCASE_APP"))throw new IllegalArgumentException("dedicated test schema required");
  String source=Files.readString(script).replaceAll("(?m)^--.*$","");
  List<String> statements=Arrays.stream(source.split(";")).map(String::strip).filter(s->!s.isEmpty()).toList();
  var existing=new HashSet<String>();
  try(var s=c.createStatement();var r=s.executeQuery("SELECT TABLE_NAME FROM USER_TABLES")){while(r.next())if(TABLES.contains(r.getString(1)))existing.add(r.getString(1));}
  if(!existing.isEmpty()&&!existing.equals(TABLES))throw new IllegalStateException("PARTIAL_SCHEMA: do not replay or recreate");
  if(existing.isEmpty()) {
   if(!apply)throw new IllegalStateException("SCHEMA_ABSENT");
   for(String sql:statements)try(var s=c.createStatement()){s.execute(sql);}
   System.out.println("DDL_APPLIED="+statements.size());
  } else System.out.println("DDL_APPLIED=0");
  var expectedColumns=new HashMap<String,Map<String,String>>();
  for(String sql:statements) {
   var table=Pattern.compile("CREATE TABLE (\\w+) \\((.*)\\)",Pattern.DOTALL).matcher(sql);
   if(!table.matches())continue;
   String name=table.group(1);var columns=new LinkedHashMap<String,String>();
   for(String line:table.group(2).split("\n")) {
    var col=Pattern.compile("^\\s*(\\w+) (VARCHAR\\(\\d+\\)|DECIMAL\\(\\d+,\\d+\\)|BIGINT|INT|TIMESTAMP|CLOB) NOT NULL.*$").matcher(line);
    if(col.matches())columns.put(col.group(1),col.group(2));
   }
   expectedColumns.put(name,columns);
   try(var p=c.prepareStatement("SELECT COLUMN_NAME,DATA_TYPE,DATA_LENGTH,DATA_PRECISION,DATA_SCALE,NULLABLE FROM USER_TAB_COLUMNS WHERE TABLE_NAME=?")) {
    p.setString(1,name);var seen=new HashSet<String>();
    try(var r=p.executeQuery()){while(r.next()) {
     String column=r.getString(1),expected=columns.get(column);if(expected==null)continue;
     String type=r.getString(2).toUpperCase(Locale.ROOT);boolean match;
     if(expected.startsWith("VARCHAR"))match=(type.equals("VARCHAR")||type.equals("VARCHAR2"))&&r.getInt(3)==Integer.parseInt(expected.replaceAll("\\D",""));
     else if(expected.startsWith("DECIMAL")) {var nums=expected.substring(8,expected.length()-1).split(",");match=(type.equals("DECIMAL")||type.equals("DEC"))&&r.getInt(4)==Integer.parseInt(nums[0])&&r.getInt(5)==Integer.parseInt(nums[1]);}
     else match=type.equals(expected)||expected.equals("INT")&&type.equals("INTEGER");
     if(!match||!r.getString(6).equals("N"))throw new IllegalStateException("COLUMN_DRIFT "+name+"."+column+" actual="+type+" expected="+expected);
     seen.add(column);
    }}
    if(!seen.equals(columns.keySet()))throw new IllegalStateException("MISSING_COLUMN "+name);
   }
  }
  // Named constraints must be present and enabled; FK/unique column order is verified via catalog.
  for(String sql:statements) {
   var table=Pattern.compile("CREATE TABLE (\\w+) \\((.*)\\)",Pattern.DOTALL).matcher(sql);
   if(!table.matches())continue;
   String name=table.group(1);
   var constraints=Pattern.compile("CONSTRAINT (\\w+) (UNIQUE|FOREIGN KEY|CHECK) \\(([^\\n]+)").matcher(table.group(2));
   while(constraints.find()) {
    String constraint=constraints.group(1),kind=constraints.group(2);
    try(var p=c.prepareStatement("SELECT CONSTRAINT_TYPE,STATUS,SEARCH_CONDITION FROM USER_CONSTRAINTS WHERE TABLE_NAME=? AND CONSTRAINT_NAME=?")) {
     p.setString(1,name);p.setString(2,constraint);try(var r=p.executeQuery()) {
      if(!r.next()||!Set.of("ENABLED","ENABLE").contains(r.getString(2)))throw new IllegalStateException("CONSTRAINT_MISSING_OR_DISABLED "+constraint);
      String expectedType=kind.equals("UNIQUE")?"U":kind.equals("CHECK")?"C":"R";
      if(!expectedType.equals(r.getString(1)))throw new IllegalStateException("CONSTRAINT_TYPE_DRIFT "+constraint);
      if(kind.equals("CHECK")) {
       String expected=constraints.group(3).replaceAll(",?$","");
       if(!canonical(expected).equals(canonical(r.getString(3))))throw new IllegalStateException("CHECK_UNKNOWN_OR_DRIFT "+constraint);
      }
     }
    }
    if(!kind.equals("CHECK")) {
     String expected=constraints.group(3).split("\\)")[0].replace(" ","");
     var columns=new ArrayList<String>();
     try(var p=c.prepareStatement("SELECT COLUMN_NAME FROM USER_CONS_COLUMNS WHERE CONSTRAINT_NAME=? ORDER BY POSITION")){p.setString(1,constraint);try(var r=p.executeQuery()){while(r.next())columns.add(r.getString(1));}}
     if(!String.join(",",columns).equals(expected))throw new IllegalStateException("CONSTRAINT_COLUMN_DRIFT "+constraint);
     if(kind.equals("FOREIGN KEY")) {
      var reference=Pattern.compile("REFERENCES (\\w+)\\(([^)]+)\\)").matcher(constraints.group(3));
      if(!reference.find())throw new IllegalStateException("UNKNOWN_FK "+constraint);
      try(var p=c.prepareStatement("SELECT C.TABLE_NAME,CC.COLUMN_NAME FROM USER_CONSTRAINTS F JOIN USER_CONSTRAINTS C ON F.R_CONSTRAINT_NAME=C.CONSTRAINT_NAME JOIN USER_CONS_COLUMNS CC ON C.CONSTRAINT_NAME=CC.CONSTRAINT_NAME WHERE F.CONSTRAINT_NAME=? ORDER BY CC.POSITION")) {
       p.setString(1,constraint);var referenced=new ArrayList<String>();try(var r=p.executeQuery()){while(r.next()){if(!r.getString(1).equals(reference.group(1)))throw new IllegalStateException("FK_TARGET_DRIFT");referenced.add(r.getString(2));}}
       if(!String.join(",",referenced).equals(reference.group(2)))throw new IllegalStateException("FK_COLUMN_DRIFT");
      }
     }
    }
   }
   try(var p=c.prepareStatement("SELECT CC.COLUMN_NAME FROM USER_CONSTRAINTS C JOIN USER_CONS_COLUMNS CC ON C.CONSTRAINT_NAME=CC.CONSTRAINT_NAME WHERE C.TABLE_NAME=? AND C.CONSTRAINT_TYPE='P' AND C.STATUS='ENABLED' ORDER BY CC.POSITION")) {
    p.setString(1,name);var primary=new ArrayList<String>();try(var r=p.executeQuery()){while(r.next())primary.add(r.getString(1));}
    if(!primary.equals(List.of("ID")))throw new IllegalStateException("PRIMARY_KEY_DRIFT "+name);
   }
  }
  for(String sql:statements) {
   var index=Pattern.compile("CREATE INDEX (\\w+) ON (\\w+)\\(([^)]+)\\)").matcher(sql);
   if(!index.matches())continue;var actual=new ArrayList<String>();
   try(var p=c.prepareStatement("SELECT COLUMN_NAME FROM USER_IND_COLUMNS WHERE INDEX_NAME=? AND TABLE_NAME=? ORDER BY COLUMN_POSITION")){p.setString(1,index.group(1));p.setString(2,index.group(2));try(var r=p.executeQuery()){while(r.next())actual.add(r.getString(1));}}
   if(!String.join(",",actual).equals(index.group(3)))throw new IllegalStateException("INDEX_DRIFT "+index.group(1));
  }
  System.out.println("SCHEMA_VERIFIED tables="+expectedColumns.size());
 }
 static String canonical(String x) {
  // Only the script's simple atoms joined by AND, and single-column IN lists: parentheses are not used for OR/grouping.
  if(x==null)return "";
  String value=x.replace("\"","").replaceAll("\\s+","").toUpperCase(Locale.ROOT);
  String grammar=x.replaceAll("'[^']*'","''");
  if(Pattern.compile("(?i)\\b(?:OR|SELECT)\\b|[+*/]").matcher(grammar).find())throw new IllegalStateException("UNKNOWN_CHECK_GRAMMAR");
  return value.replace("(","").replace(")","").replace("!=","<>");
 }
}
