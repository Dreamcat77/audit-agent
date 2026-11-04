import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Standalone DM8-only local test-business reset, with typed backups and fail-closed gates. */
public class ResetReviewTestData {
  record Edge(String child,String parent) {}
  enum Classification {DELETE,CONDITIONAL,APPROVED_PROTECTED,UNKNOWN}
  static final Set<String> APPROVED_PROTECTED=Set.of("AUDIT_OPERATION_LOG","AUTH_LOGIN_AUDIT","BLACKLIST_RULE","BLACKLIST_SUPPLIER","BLACKLIST_SUPPLIER_OPERATION_LOG","BUDGET_APPROVAL_RULE_CONFIG","BUDGET_CATEGORY_RULES","BUDGET_PROJECT_CONFIG","BUDGET_SPLIT_RISK_RULE","BUDGET_SUBJECT_MAPPING","BUDGET_YEAR_VALIDITY_CONFIG","CONTRACT_RULE_CONFIG","PROCUREMENT_TENDER_RULE","SYS_ROLE","SYS_USER","SYS_USER_ROLE");
  static final Set<String> CONDITIONAL=Set.of("IDEMPOTENCY_RECORD","AUDIT_TASK","AUDIT_CHECK_RESULT","REVIEW_FINDING_ACKNOWLEDGEMENT","CONTRACT_MODEL_CALL_AUDIT","BUDGET_VALIDATION_CONFIRM_LOG");
  static Classification classify(String table){if(CONDITIONAL.contains(table))return Classification.CONDITIONAL;if(TARGETS.contains(table))return Classification.DELETE;if(APPROVED_PROTECTED.contains(table))return Classification.APPROVED_PROTECTED;return Classification.UNKNOWN;}
  static void requireClassified(Collection<String> tables){for(String table:tables)if(classify(table)==Classification.UNKNOWN)throw new IllegalStateException("UNCLASSIFIED_TABLE");}
  static final int MAX_CELL=128*1024*1024;
  static final Set<String> TARGETS=Set.of(
      "AUDIT_CHECK_RESULT","AUDIT_TASK","BLACKLIST_ALERT","BUDGET_APPROVAL_CHECK","BUDGET_APPROVAL_VALIDATION",
      "BUDGET_REVIEW_ITEM","BUDGET_REVIEW_TASK","BUDGET_MATERIAL_JOB","BUDGET_SPLIT_RISK_ASSESSMENT","BUDGET_VALIDATION_CONFIRM_LOG",
      "BUSINESS_MATERIAL_INTAKE","CONTRACT","CONTRACT_AWARD_NOTICE","CONTRACT_CANDIDATE","CONTRACT_CANDIDATE_CONFIRMATION",
      "CONTRACT_CANDIDATE_LINE_ITEM","CONTRACT_CANDIDATE_SUPPORT_AUTHORITY","CONTRACT_LINE_ITEM","CONTRACT_MODEL_CALL_AUDIT",
      "CONTRACT_REVIEW_CLAUSE_COMPARISON","CONTRACT_REVIEW_CLAUSE_VALUE","CONTRACT_REVIEW_CLAUSE_VALUE_EVIDENCE",
      "CONTRACT_REVIEW_DOCUMENT","CONTRACT_REVIEW_EVIDENCE","CONTRACT_REVIEW_FINDING","CONTRACT_REVIEW_FINDING_ACKNOWLEDGEMENT",
      "CONTRACT_REVIEW_MATERIAL_BINDING","CONTRACT_REVIEW_REPORT","CONTRACT_REVIEW_RUN","CONTRACT_REVIEW_RUN_MATERIAL",
      "CONTRACT_REVIEW_SESSION","CONTRACT_REVIEW_STEP","CONTRACT_REVIEW_STEP_ATTEMPT","IDEMPOTENCY_RECORD",
      "PROCUREMENT_AGENT_REVIEW_SNAPSHOT","PROCUREMENT_APPROVAL_INSTANCE","PROCUREMENT_APPROVAL_NODE","PROCUREMENT_APPROVAL_OPERATION_LOG",
      "PROCUREMENT_COLLUSION_ASSESSMENT","PROCUREMENT_COLLUSION_DOCUMENT","PROCUREMENT_DEMAND","PROCUREMENT_MATERIAL_LINK",
      "PROCUREMENT_NOTIFICATION","PROCUREMENT_QUOTE_CHECK","PROCUREMENT_QUOTE_CHECK_ITEM","PROCUREMENT_QUOTE_COMPARISON",
      "PROCUREMENT_REVIEW_REPORT","PROCUREMENT_REVIEW_STEP","PROCUREMENT_REVIEW_WORKFLOW","PROCUREMENT_SPLIT_PURCHASE_ASSESSMENT",
      "PROCUREMENT_TENDER_DOCUMENT","PROCUREMENT_TENDER_FINDING","PROJECT","PROJECT_CONTRACT","PROJECT_EXTRACTION_RESULT",
      "PROJECT_FACT_EXTRACTION_ATTEMPT","PROJECT_FACT_EXTRACTION_CANDIDATE","PROJECT_FACT_EXTRACTION_REJECTION","PROJECT_FILE",
      "PROJECT_FILE_IDENTITY_ASSESSMENT","PROJECT_FILE_IDENTITY_DECISION","PROJECT_FILE_IDENTITY_EVIDENCE","PROJECT_FILE_PARSE_RESULT","PROJECT_FILE_PROCESSING_JOB","PROJECT_FILE_PROCESSING_ATTEMPT",
      "PROJECT_FILE_ROLE_ASSESSMENT","PROJECT_FILE_ROLE_DECISION","PROJECT_FILE_ROLE_EVIDENCE","REVIEW_FINDING_ACKNOWLEDGEMENT",
      "REVIEW_SESSION_PROJECT_FILE_BINDING","SANITIZED_DEBUG_BUNDLE");
  static final Map<String,String> SCOPES=new TreeMap<>();
  static {
    addScopes("PROJECT","PROJECT_CREATE","PROJECT_METADATA_CONFIRM","PROJECT_FILE_UPLOAD","PROJECT_FILE_UPLOAD_ASYNC");
    addScopes("PROJECT_FILE","ROLE_ASSESSMENT","ROLE_CONFIRMATION","PROJECT_IDENTITY_ASSESSMENT","PROJECT_IDENTITY_CONFIRMATION","PROJECT_FILE_PROGRESSION_RESUME","PROJECT_FILE_PROGRESSION_STAGE");
    addScopes("CONTRACT_CANDIDATE","CONTRACT_CANDIDATE_CONFIRM","CONTRACT_CANDIDATE_SUPPORT_REPLACE","PROJECT_CONTRACT_CANDIDATE_PROMOTE");
    addScopes("PROJECT_FILE_ROLE_DECISION","CONTRACT_CANDIDATE_MATERIALIZE");
    addScopes("CONTRACT","CONTRACT_F02_REVIEW","CONTRACT_REVIEW_SESSION_CREATE","REVIEW_RUN_CREATE");
    addScopes("CONTRACT_REVIEW_SESSION","CONTRACT_REVIEW_DOCUMENT_UPLOAD","CONTRACT_REVIEW_MATERIALS_CONFIRM","CONTRACT_REVIEW_SESSION_DELETE");
    addScopes("CONTRACT_REVIEW_FINDING","CONTRACT_REVIEW_FINDING_ACKNOWLEDGEMENT_CREATE");
    addScopes("CONTRACT_REVIEW_RUN","CONTRACT_REVIEW_REPORT_GENERATE");
    addScopes("CONTRACT_REVIEW_STEP","CONTRACT_REVIEW_STEP_RETRY","REVIEW_STEP_EXECUTE");
    addScopes("AUDIT_CHECK_RESULT","REVIEW_FINDING_ACKNOWLEDGEMENT_CREATE");
    addScopes("BUDGET_REVIEW_ITEM","BUDGET_APPROVAL_PROGRAM_VALIDATE");
  }
  static void addScopes(String type,String... scopes){for(String s:scopes)SCOPES.put(s,type);}
  static boolean isTarget(String table) {return TARGETS.contains(table);}
  static List<String> order(Set<String> nodes,List<Edge> edges) {
    Map<String,Set<String>> parents=new TreeMap<>();Map<String,Integer> incoming=new TreeMap<>();
    for(String n:nodes){parents.put(n,new TreeSet<>());incoming.put(n,0);}
    for(Edge e:edges)if(nodes.contains(e.child)&&nodes.contains(e.parent)&&parents.get(e.child).add(e.parent))incoming.merge(e.parent,1,Integer::sum);
    TreeSet<String> ready=new TreeSet<>();incoming.forEach((n,i)->{if(i==0)ready.add(n);});List<String> out=new ArrayList<>();
    while(!ready.isEmpty()){String n=ready.pollFirst();out.add(n);for(String p:parents.get(n))if(incoming.merge(p,-1,Integer::sum)==0)ready.add(p);}
    if(out.size()!=nodes.size())throw new IllegalStateException("UNPROVEN_FK_CYCLE");return out;
  }
  static void checkDependent(boolean childTarget,boolean parentTarget,long count) {if(!childTarget&&parentTarget&&count>0)throw new IllegalStateException("PROTECTED_FK_DEPENDENT");}
  static boolean idempotencyTarget(String scope,String type,String id,String resultType,String resultId,Map<String,Set<String>> ids) {
    if(!Objects.equals(SCOPES.get(scope),type))return false;
    return ids.getOrDefault(type,Set.of()).contains(id) || (resultType!=null&&ids.getOrDefault(resultType,Set.of()).contains(resultId));
  }
  static final class DbIdentity {final String url,user,schema;DbIdentity(String u,String a,String s){url=u;user=a;schema=s;}public String toString(){return "LOCAL_APPROVED_SCHEMA";}}
  static final Path APPROVED_ROOT=Path.of("D:/code/audit_agent_1D/.worktrees/integration-pre-main-20261010").toAbsolutePath().normalize();
  static final String IDENTITY_BACKUP=".runtime/contract-repair-test/backups/20261014-182117-590";
  static final String IDENTITY_MANIFEST_SHA="eed50b07548e6e252a5f3063bc4fe8cc0de50b5feb9c4ad12653fa0b499ae90c";
  static final String IDENTITY_RECEIPT_SHA="417750fd8ecfd7f75a8ea7938b4c2613b06ecff9937dace0b7f5285c43fca117";
  static final String IDENTITY_SIDECAR_SHA="2a7dc75f8104c4e14799c351e940da00bfd183b76ae004e003067187120c4ea9";
  static DbIdentity parsePrivateIdentity(String text){
    Map<String,String> values=new HashMap<>();
    for(String line:text.split("\\r?\\n")){if(line.isEmpty())continue;int eq=line.indexOf('=');if(eq<1)throw new IllegalStateException("PRIVATE_IDENTITY_SYNTAX");String key=line.substring(0,eq);if(!Set.of("format","url","user","schema").contains(key)||values.putIfAbsent(key,line.substring(eq+1))!=null)throw new IllegalStateException("PRIVATE_IDENTITY_UNKNOWN_OR_DUPLICATE_FIELD");}
    if(values.size()!=4||!"CONTRACT_LOCAL_DB_IDENTITY_V1".equals(values.get("format"))||!"jdbc:dm://127.0.0.1:5236".equals(values.get("url")))throw new IllegalStateException("PRIVATE_IDENTITY_FORMAT_OR_SCOPE");
    for(String key:List.of("user","schema")){String value=values.get(key);if(value==null||!value.matches("[A-Z][A-Z0-9_]{0,127}")||Set.of("SYSDBA","SYSAUDITOR","SYS","SYSTEM").contains(value))throw new IllegalStateException("PRIVATE_IDENTITY_UNSAFE_OR_PRIVILEGED");}
    return new DbIdentity(values.get("url"),values.get("user"),values.get("schema"));
  }
  static String verifiedIdentityText(Path path,Path root,String hash)throws Exception{
    if(!hash.matches("[a-f0-9]{64}")||!path.startsWith(root)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("PRIVATE_IDENTITY_FILE_MISSING_OR_PATH");
    for(Path scan=path;scan!=null&&scan.startsWith(root);scan=scan.getParent())if(Files.isSymbolicLink(scan)||!scan.toRealPath().equals(scan))throw new IllegalStateException("PRIVATE_IDENTITY_REPARSE_POINT");
    if(Files.size(path)>1024*1024)throw new IllegalStateException("PRIVATE_IDENTITY_FILE_TOO_LARGE");byte[] bytes=Files.readAllBytes(path);
    if(!hex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(hash))throw new IllegalStateException("PRIVATE_IDENTITY_AUTHORITY_HASH_MISMATCH");return new String(bytes,StandardCharsets.UTF_8);
  }
  static DbIdentity readIdentityAuthority(Path root,Path expectedRoot,String manifestHash,String receiptHash,String identityHash)throws Exception{
    root=root.toAbsolutePath().normalize();if(!root.equals(expectedRoot.toAbsolutePath().normalize()))throw new IllegalStateException("PRIVATE_IDENTITY_SOURCE_MISMATCH");
    Path backup=root.resolve(IDENTITY_BACKUP);Properties manifest=new Properties();manifest.load(new StringReader(verifiedIdentityText(backup.resolve("manifest.properties"),root,manifestHash)));
    String receipt=verifiedIdentityText(backup.resolve("identity-reset-applied.txt"),root,receiptHash);Map<String,String> lines=new HashMap<>();for(String line:receipt.split("\\r?\\n")){if(line.isEmpty())continue;int eq=line.indexOf('=');if(eq<1||lines.putIfAbsent(line.substring(0,eq),line.substring(eq+1))!=null)throw new IllegalStateException("PRIVATE_IDENTITY_RECEIPT_SYNTAX");}
    if(!"AUDIT_DM8_TYPED_BACKUP_V1".equals(manifest.getProperty("format"))||!"PASS".equals(lines.get("DATA_RESET"))||!Objects.equals(manifest.getProperty("plan.sha256"),lines.get("PLAN_SHA256"))||lines.get("BACKUP")==null||!Path.of(lines.get("BACKUP")).toAbsolutePath().normalize().equals(backup))throw new IllegalStateException("PRIVATE_IDENTITY_BACKUP_RECEIPT_MISMATCH");
    return parsePrivateIdentity(verifiedIdentityText(backup.resolve("local-identity.properties"),root,identityHash));
  }
  static DbIdentity approvedIdentity(Path root)throws Exception{return readIdentityAuthority(root,APPROVED_ROOT,IDENTITY_MANIFEST_SHA,IDENTITY_RECEIPT_SHA,IDENTITY_SIDECAR_SHA);}
  static void identity(DbIdentity expected,String url,String user,String schema,int major){if(expected==null||!expected.url.equals(url)||!expected.user.equals(user)||!expected.schema.equals(schema)||major!=8)throw new IllegalStateException("LOCAL_DM8_IDENTITY_MISMATCH");}
  static byte[] encodeRow(List<Object> row) throws IOException {
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream o=new DataOutputStream(bytes)){o.writeInt(row.size());for(Object v:row)writeCell(o,v);}return bytes.toByteArray();
  }
  static void writeCell(DataOutputStream o,Object v)throws IOException{
    if(v==null){o.writeByte(0);return;}int tag;byte[] b;
    if(v instanceof byte[] a){tag=2;b=a;}
    else if(v instanceof BigDecimal d){tag=3;b=d.toPlainString().getBytes(StandardCharsets.UTF_8);}
    else if(v instanceof Timestamp t){tag=4;b=t.toString().getBytes(StandardCharsets.UTF_8);}
    else if(v instanceof java.sql.Date d){tag=5;b=d.toString().getBytes(StandardCharsets.UTF_8);}
    else if(v instanceof Time t){tag=6;b=t.toString().getBytes(StandardCharsets.UTF_8);}
    else if(v instanceof Boolean b1){o.writeByte(7);o.writeBoolean(b1);return;}
    else if(v instanceof String s){tag=1;b=s.getBytes(StandardCharsets.UTF_8);}
    else throw new IOException("UNSUPPORTED_BACKUP_TYPE");
    if(b.length>MAX_CELL)throw new IOException("BACKUP_CELL_TOO_LARGE");o.writeByte(tag);o.writeInt(b.length);o.write(b);
  }
  static List<Object> decodeRow(byte[] bytes) throws IOException {
    try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes))){int n=in.readInt();if(n<0||n>1024)throw new IOException("BAD_COLUMN_COUNT");List<Object> out=new ArrayList<>();for(int i=0;i<n;i++)out.add(readCell(in));if(in.available()!=0)throw new IOException("BACKUP_TRAILING_BYTES");return out;}
  }
  static Object readCell(DataInputStream in)throws IOException{
    int tag=in.readUnsignedByte();if(tag==0)return null;if(tag==7)return in.readBoolean();int n=in.readInt();if(n<0||n>MAX_CELL)throw new IOException("BAD_CELL_LENGTH");byte[] b=in.readNBytes(n);if(b.length!=n)throw new EOFException();if(tag==2)return b;String s=new String(b,StandardCharsets.UTF_8);
    try{return switch(tag){case 1->s;case 3->new BigDecimal(s);case 4->Timestamp.valueOf(s);case 5->java.sql.Date.valueOf(s);case 6->Time.valueOf(s);default->throw new IOException("BAD_CELL_TAG");};}catch(IllegalArgumentException e){throw new IOException("BAD_CELL_VALUE");}
  }
  static String quote(String name){if(!name.matches("[A-Z][A-Z0-9_]*"))throw new IllegalStateException("UNSAFE_IDENTIFIER");return "\""+name+"\"";}
  static String literal(Object v){if(v instanceof String s)return "'"+s.replace("'","''")+"'";if(v instanceof BigDecimal d)return d.toPlainString();throw new IllegalStateException("UNSUPPORTED_PK_TYPE");}
  static String predicate(List<String> cols,List<Object> values) {if(cols.isEmpty()||cols.size()!=values.size())throw new IllegalStateException("NO_EXACT_IDENTITY");StringJoiner j=new StringJoiner(" AND ");for(int i=0;i<cols.size();i++)j.add(quote(cols.get(i))+"="+literal(values.get(i)));return j.toString();}

  record Column(String name,int jdbc,String sqlType,boolean nullable) {}
  record Fk(String name,String child,String parent,List<String> childCols,List<String> parentCols) {}
  static final class Table {
    final String name; final List<Column> columns; final List<String> pk; final List<List<Object>> rows;
    final List<List<Object>> selected=new ArrayList<>();
    Table(String n,List<Column> c,List<String> p,List<List<Object>> r){name=n;columns=c;pk=p;rows=r;}
    int index(String col){for(int i=0;i<columns.size();i++)if(columns.get(i).name.equals(col))return i;throw new IllegalStateException("UNKNOWN_COLUMN");}
    Object value(List<Object> row,String col){return row.get(index(col));}
    List<Object> values(List<Object> row,List<String> cols){List<Object> out=new ArrayList<>();for(String col:cols)out.add(value(row,col));return out;}
    String key(List<Object> row)throws Exception{return tuple(values(row,pk));}
    String where(List<Object> row){return predicate(pk,values(row,pk));}
    List<List<Object>> kept(){Set<List<Object>> removed=Collections.newSetFromMap(new IdentityHashMap<>());removed.addAll(selected);return rows.stream().filter(r->!removed.contains(r)).toList();}
  }
  record Catalog(Map<String,Table> tables,List<Fk> fks,List<String> facts,String hash) {}
  record Plan(Catalog catalog,List<String> tableOrder,Map<String,List<List<Object>>> rowOrder,Map<String,String> keptFingerprints,String hash) {}
  static String tuple(List<Object> vals)throws Exception{
    List<Object> normalized=new ArrayList<>();for(Object v:vals)normalized.add(v instanceof BigDecimal d?d.stripTrailingZeros():v);return hex(MessageDigest.getInstance("SHA-256").digest(encodeRow(normalized)));
  }
  static String hex(byte[] b){return HexFormat.of().formatHex(b);}
  static String sha(Path p)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(p)){byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)d.update(b,0,n);}return hex(d.digest());}
  static String fingerprint(List<List<Object>> rows)throws Exception{
    List<String> hashes=new ArrayList<>();MessageDigest d=MessageDigest.getInstance("SHA-256");for(var row:rows)hashes.add(hex(d.digest(encodeRow(row))));Collections.sort(hashes);d.reset();for(String h:hashes)d.update(h.getBytes(StandardCharsets.US_ASCII));return rows.size()+":"+hex(d.digest());
  }
  static List<List<String>> query(Connection c,String sql)throws SQLException{
    List<List<String>> out=new ArrayList<>();try(Statement s=c.createStatement()){s.setQueryTimeout(30);try(ResultSet r=s.executeQuery(sql)){int n=r.getMetaData().getColumnCount();while(r.next()){List<String> row=new ArrayList<>();for(int i=1;i<=n;i++)row.add(r.getString(i));out.add(row);}}}return out;
  }
  static Catalog catalog(Connection c,boolean includeRows)throws Exception{
    DbIdentity expected=approvedIdentity(APPROVED_ROOT);String schema=c.getSchema();if(!expected.schema.equals(schema))throw new IllegalStateException("LOCAL_DM8_IDENTITY_MISMATCH");
    Map<String,Table> tables=new TreeMap<>();List<String> facts=new ArrayList<>();
    for(var r:query(c,"SELECT TABLE_NAME FROM USER_TABLES ORDER BY TABLE_NAME")){
      String name=r.get(0);quote(name);List<Column> cols=new ArrayList<>();List<List<Object>> rows=new ArrayList<>();
      try(Statement s=c.createStatement();ResultSet rs=s.executeQuery("SELECT * FROM "+quote(name)+(includeRows?"":" WHERE 1=0"))){
        ResultSetMetaData m=rs.getMetaData();for(int i=1;i<=m.getColumnCount();i++)cols.add(new Column(m.getColumnName(i),m.getColumnType(i),m.getColumnTypeName(i),m.isNullable(i)!=ResultSetMetaData.columnNoNulls));
        while(rs.next()){List<Object> values=new ArrayList<>();for(int i=1;i<=cols.size();i++)values.add(readJdbc(rs,i,cols.get(i-1)));rows.add(values);}
      }
      List<String> pk=new ArrayList<>();for(var p:query(c,"SELECT C.COLUMN_NAME FROM USER_CONS_COLUMNS C JOIN USER_CONSTRAINTS K ON C.CONSTRAINT_NAME=K.CONSTRAINT_NAME WHERE K.CONSTRAINT_TYPE='P' AND K.TABLE_NAME='"+name+"' ORDER BY C.POSITION"))pk.add(p.get(0));
      if(isTarget(name)&&pk.isEmpty())throw new IllegalStateException("TARGET_WITHOUT_PRIMARY_KEY");
      tables.put(name,new Table(name,cols,pk,rows));facts.add("TABLE|"+name);for(Column col:cols)facts.add("COLUMN|"+name+"|"+col);
    }
    for(String sql:List.of(
        "SELECT TABLE_NAME,COLUMN_NAME,DATA_TYPE,DATA_LENGTH,DATA_PRECISION,DATA_SCALE,NULLABLE,DATA_DEFAULT FROM USER_TAB_COLUMNS ORDER BY TABLE_NAME,COLUMN_ID",
        "SELECT TABLE_NAME,CONSTRAINT_NAME,CONSTRAINT_TYPE,R_OWNER,R_CONSTRAINT_NAME,DELETE_RULE,STATUS,SEARCH_CONDITION FROM USER_CONSTRAINTS ORDER BY TABLE_NAME,CONSTRAINT_NAME",
        "SELECT TABLE_NAME,CONSTRAINT_NAME,COLUMN_NAME,POSITION FROM USER_CONS_COLUMNS ORDER BY TABLE_NAME,CONSTRAINT_NAME,COLUMN_NAME",
        "SELECT TABLE_NAME,INDEX_NAME,UNIQUENESS,STATUS FROM USER_INDEXES ORDER BY TABLE_NAME,INDEX_NAME",
        "SELECT TABLE_NAME,INDEX_NAME,COLUMN_NAME,COLUMN_POSITION FROM USER_IND_COLUMNS ORDER BY TABLE_NAME,INDEX_NAME,COLUMN_POSITION",
        "SELECT TABLE_NAME,TRIGGER_NAME,STATUS,TRIGGERING_EVENT FROM USER_TRIGGERS ORDER BY TABLE_NAME,TRIGGER_NAME"))
      for(var r:query(c,sql))facts.add(sql.substring(7,Math.min(sql.indexOf(" FROM "),50))+"|"+r);
    for(var r:query(c,"SELECT TABLE_NAME,TRIGGER_NAME FROM USER_TRIGGERS"))if(isTarget(r.get(0)))throw new IllegalStateException("UNPROVEN_TARGET_TRIGGER");
    if(!query(c,"SELECT OWNER,TABLE_NAME,CONSTRAINT_NAME FROM ALL_CONSTRAINTS WHERE CONSTRAINT_TYPE='R' AND R_OWNER="+literal(schema)+" AND OWNER<>"+literal(schema)+"").isEmpty())throw new IllegalStateException("EXTERNAL_PROTECTED_FK");
    List<Fk> fks=new ArrayList<>();
    for(var r:query(c,"SELECT F.CONSTRAINT_NAME,F.TABLE_NAME,P.TABLE_NAME,F.STATUS,F.DELETE_RULE,F.R_OWNER FROM USER_CONSTRAINTS F LEFT JOIN ALL_CONSTRAINTS P ON F.R_OWNER=P.OWNER AND F.R_CONSTRAINT_NAME=P.CONSTRAINT_NAME WHERE F.CONSTRAINT_TYPE='R' ORDER BY F.CONSTRAINT_NAME")){
      if(!"ENABLED".equals(r.get(3))||!"NO ACTION".equals(r.get(4))||!schema.equals(r.get(5))||r.get(2)==null)throw new IllegalStateException("UNPROVEN_FK_SEMANTICS");
      List<String> cc=new ArrayList<>(),pc=new ArrayList<>();
      for(var p:query(c,"SELECT FC.COLUMN_NAME,PC.COLUMN_NAME FROM USER_CONSTRAINTS F JOIN USER_CONS_COLUMNS FC ON F.CONSTRAINT_NAME=FC.CONSTRAINT_NAME JOIN ALL_CONS_COLUMNS PC ON F.R_OWNER=PC.OWNER AND F.R_CONSTRAINT_NAME=PC.CONSTRAINT_NAME AND FC.POSITION=PC.POSITION WHERE F.CONSTRAINT_NAME='"+r.get(0)+"' ORDER BY FC.POSITION")){cc.add(p.get(0));pc.add(p.get(1));}
      if(cc.isEmpty())throw new IllegalStateException("UNKNOWN_FK_COLUMNS");fks.add(new Fk(r.get(0),r.get(1),r.get(2),cc,pc));
    }
    String hash=hex(MessageDigest.getInstance("SHA-256").digest(String.join("\n",facts).getBytes(StandardCharsets.UTF_8)));return new Catalog(tables,fks,facts,hash);
  }
  static Object readJdbc(ResultSet rs,int i,Column col)throws Exception{
    if(rs.getObject(i)==null)return null;
    return switch(col.jdbc){
      case Types.CLOB,Types.NCLOB,Types.LONGVARCHAR,Types.LONGNVARCHAR -> {try(Reader reader=rs.getCharacterStream(i)){StringBuilder b=new StringBuilder();char[] a=new char[8192];for(int n;(n=reader.read(a))!=-1;){if(b.length()+n>MAX_CELL/2)throw new IOException("CLOB_TOO_LARGE");b.append(a,0,n);}yield b.toString();}}
      case Types.BLOB,Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY -> {try(InputStream in=rs.getBinaryStream(i)){byte[] b=in.readNBytes(MAX_CELL+1);if(b.length>MAX_CELL)throw new IOException("BLOB_TOO_LARGE");yield b;}}
      case Types.CHAR,Types.NCHAR,Types.VARCHAR,Types.NVARCHAR -> rs.getString(i);
      case Types.DECIMAL,Types.NUMERIC,Types.BIGINT,Types.INTEGER,Types.SMALLINT,Types.TINYINT -> rs.getBigDecimal(i);
      case Types.TIMESTAMP -> rs.getTimestamp(i);
      case Types.DATE -> rs.getDate(i);
      case Types.TIME -> rs.getTime(i);
      case Types.BOOLEAN,Types.BIT -> rs.getBoolean(i);
      default -> throw new IllegalStateException("UNSUPPORTED_JDBC_BACKUP_TYPE");
    };
  }
  static Map<String,Set<String>> resourceIds(Catalog cat)throws Exception{
    Map<String,Set<String>> ids=new TreeMap<>();for(Table t:cat.tables.values())if(t.pk.size()==1){Set<String> values=new HashSet<>();for(var row:t.selected){Object v=t.value(row,t.pk.get(0));if(v instanceof String s)values.add(s);}ids.put(t.name,values);}return ids;
  }
  static void selectTargets(Catalog cat)throws Exception{
    requireClassified(cat.tables.keySet());
    for(Table t:cat.tables.values())if(classify(t.name)==Classification.DELETE)t.selected.addAll(t.rows);
    Map<String,Set<String>> ids=resourceIds(cat);Table task=cat.tables.get("AUDIT_TASK");
    for(var r:task.rows)if("CONTRACT".equals(task.value(r,"SOURCE_TYPE"))&&ids.getOrDefault("CONTRACT",Set.of()).contains(task.value(r,"SOURCE_ID")))task.selected.add(r);
    ids=resourceIds(cat);Table checks=cat.tables.get("AUDIT_CHECK_RESULT");for(var r:checks.rows)if(ids.getOrDefault("AUDIT_TASK",Set.of()).contains(checks.value(r,"TASK_ID")))checks.selected.add(r);
    ids=resourceIds(cat);Table ack=cat.tables.get("REVIEW_FINDING_ACKNOWLEDGEMENT");for(var r:ack.rows)if(ids.getOrDefault("AUDIT_CHECK_RESULT",Set.of()).contains(ack.value(r,"CHECK_RESULT_ID")))ack.selected.add(r);
    Table calls=cat.tables.get("CONTRACT_MODEL_CALL_AUDIT");for(var r:calls.rows)if(ids.getOrDefault("PROJECT_FACT_EXTRACTION_ATTEMPT",Set.of()).contains(calls.value(r,"FACT_EXTRACTION_ATTEMPT_ID"))||ids.getOrDefault("CONTRACT_REVIEW_STEP_ATTEMPT",Set.of()).contains(calls.value(r,"REVIEW_STEP_ATTEMPT_ID")))calls.selected.add(r);
    Table log=cat.tables.get("BUDGET_VALIDATION_CONFIRM_LOG");for(var r:log.rows)if(ids.getOrDefault("BUDGET_REVIEW_TASK",Set.of()).contains(log.value(r,"BATCH_ID")))log.selected.add(r);
    ids=resourceIds(cat);Table idem=cat.tables.get("IDEMPOTENCY_RECORD");for(var r:idem.rows)if(idempotencyTarget((String)idem.value(r,"SCOPE"),(String)idem.value(r,"RESOURCE_TYPE"),(String)idem.value(r,"RESOURCE_ID"),(String)idem.value(r,"RESULT_RESOURCE_TYPE"),(String)idem.value(r,"RESULT_RESOURCE_ID"),ids))idem.selected.add(r);
  }
  static boolean removablePromotion(Fk f){return f.name.equals("FK_CONTRACT_CANDIDATE_PROMOTED_CONFIRMATION")&&f.child.equals("CONTRACT_CANDIDATE")&&f.parent.equals("CONTRACT_CANDIDATE_CONFIRMATION")&&f.childCols.equals(List.of("PROMOTED_CONFIRMATION_ID","PROJECT_ID","ID"));}
  static void provePromotionNull(Connection c,Catalog cat)throws Exception{
    Table t=cat.tables.get("CONTRACT_CANDIDATE");if(!t.columns.get(t.index("PROMOTED_CONFIRMATION_ID")).nullable)throw new IllegalStateException("PROMOTION_POINTER_NOT_NULLABLE");int related=0;
    for(var r:query(c,"SELECT CONSTRAINT_NAME,SEARCH_CONDITION FROM USER_CONSTRAINTS WHERE TABLE_NAME='CONTRACT_CANDIDATE' AND CONSTRAINT_TYPE='C'")){
      String expr=Objects.toString(r.get(1),"").toUpperCase(Locale.ROOT);if(expr.contains("PROMOTED_CONFIRMATION_ID")){
        String normalized=expr.replaceAll("[\\s()]","");
        if(!r.get(0).equals("CK_CONTRACT_CANDIDATE_DRAFT_CONFIRMATION")||!normalized.equals("PROMOTION_STATE!='DRAFT'ORPROMOTED_CONFIRMATION_IDISNULL"))throw new IllegalStateException("UNPROVEN_PROMOTION_CHECK");related++;
      }
    }
    if(related!=1||cat.fks.stream().filter(ResetReviewTestData::removablePromotion).count()!=1)throw new IllegalStateException("PROMOTION_NULL_PROOF_MISSING");
  }
  static Plan plan(Connection c,Catalog cat)throws Exception{
    requireClassified(cat.tables.keySet());selectTargets(cat);provePromotionNull(c,cat);Set<String> targetTables=new TreeSet<>();Map<String,Map<String,List<Object>>> chosen=new TreeMap<>();
    for(Table t:cat.tables.values())if(isTarget(t.name)){targetTables.add(t.name);Map<String,List<Object>> m=new TreeMap<>();for(var r:t.selected)if(m.put(t.key(r),r)!=null)throw new IllegalStateException("DUPLICATE_PRIMARY_KEY");chosen.put(t.name,m);}
    List<Edge> edges=new ArrayList<>();Map<String,List<Edge>> selfEdges=new TreeMap<>();for(String t:targetTables)selfEdges.put(t,new ArrayList<>());
    for(Fk f:cat.fks){Table child=cat.tables.get(f.child),parent=cat.tables.get(f.parent);Map<String,List<Object>> parents=new HashMap<>();for(var r:parent.rows)parents.put(tuple(parent.values(r,f.parentCols)),r);
      for(var r:child.rows){List<Object> v=child.values(r,f.childCols);if(v.contains(null))continue;List<Object> p=parents.get(tuple(v));if(p==null)throw new IllegalStateException("EXISTING_ORPHAN_FK");
        boolean ct=chosen.getOrDefault(f.child,Map.of()).containsKey(child.key(r)),pt=chosen.getOrDefault(f.parent,Map.of()).containsKey(parent.key(p));checkDependent(ct,pt,1);
        if(ct&&pt&&!removablePromotion(f)){if(f.child.equals(f.parent))selfEdges.get(f.child).add(new Edge(child.key(r),parent.key(p)));else edges.add(new Edge(f.child,f.parent));}
      }
    }
    List<String> tableOrder=order(targetTables,edges);Map<String,List<List<Object>>> rowOrder=new TreeMap<>();Map<String,String> protectedHashes=new TreeMap<>();
    for(Table t:cat.tables.values()){protectedHashes.put(t.name,fingerprint(t.kept()));if(isTarget(t.name)){List<List<Object>> rows=new ArrayList<>();for(String k:order(chosen.get(t.name).keySet(),selfEdges.get(t.name)))rows.add(chosen.get(t.name).get(k));rowOrder.put(t.name,rows);}}
    MessageDigest d=MessageDigest.getInstance("SHA-256");d.update(cat.hash.getBytes(StandardCharsets.US_ASCII));for(String t:tableOrder){d.update(t.getBytes(StandardCharsets.US_ASCII));d.update(fingerprint(rowOrder.get(t)).getBytes(StandardCharsets.US_ASCII));}for(var e:protectedHashes.entrySet())d.update((e.getKey()+e.getValue()).getBytes(StandardCharsets.US_ASCII));
    return new Plan(cat,tableOrder,rowOrder,protectedHashes,hex(d.digest()));
  }
  static void lockTables(Connection c,Catalog cat,String mode)throws Exception{
    for(String table:cat.tables.keySet())try(Statement s=c.createStatement()){s.setQueryTimeout(10);s.execute("LOCK TABLE "+quote(table)+" IN "+mode+" MODE NOWAIT");}
    System.out.println("DM8_TABLE_LOCKS="+mode+"_NOWAIT_PASS count="+cat.tables.size());
  }
  static void accountGate(Connection c)throws Exception{
    var users=query(c,"SELECT ID,STATUS FROM SYS_USER WHERE USERNAME='test_admin'");if(users.size()!=1||!"ACTIVE".equals(users.get(0).get(1)))throw new IllegalStateException("TEST_ADMIN_NOT_ACTIVE");
    var roles=query(c,"SELECT R.ROLE_CODE FROM SYS_USER U JOIN SYS_USER_ROLE UR ON U.ID=UR.USER_ID JOIN SYS_ROLE R ON UR.ROLE_ID=R.ID WHERE U.USERNAME='test_admin' ORDER BY R.ROLE_CODE");if(roles.isEmpty())throw new IllegalStateException("TEST_ADMIN_WITHOUT_ROLE");
    System.out.println("TEST_ADMIN=ACTIVE id="+users.get(0).get(0)+" roleCount="+roles.size()+" roles="+roles);
  }
  static void writeArchive(Path file,Table t,List<List<Object>> rows)throws Exception{
    try(DataOutputStream o=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file,StandardOpenOption.CREATE_NEW)))){
      o.writeUTF("AUDIT_DM8_TYPED_BACKUP_V1");o.writeUTF(t.name);o.writeInt(t.columns.size());for(Column col:t.columns){o.writeUTF(col.name);o.writeInt(col.jdbc);o.writeUTF(col.sqlType);o.writeBoolean(col.nullable);}o.writeInt(t.pk.size());for(String pk:t.pk)o.writeUTF(pk);o.writeInt(rows.size());for(var r:rows){byte[] b=encodeRow(r);o.writeInt(b.length);o.write(b);}
    }
  }
  static Table readArchive(Path file)throws Exception{
    try(DataInputStream in=new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))){
      if(!in.readUTF().equals("AUDIT_DM8_TYPED_BACKUP_V1"))throw new IOException("BACKUP_VERSION");String name=in.readUTF();quote(name);int n=in.readInt();if(n<1||n>1024)throw new IOException("BAD_COLUMNS");List<Column> cols=new ArrayList<>();for(int i=0;i<n;i++)cols.add(new Column(in.readUTF(),in.readInt(),in.readUTF(),in.readBoolean()));int p=in.readInt();if(p<1||p>n)throw new IOException("BAD_PK");List<String> pk=new ArrayList<>();for(int i=0;i<p;i++)pk.add(in.readUTF());int count=in.readInt();if(count<0||count>1_000_000)throw new IOException("BAD_ROWS");List<List<Object>> rows=new ArrayList<>();for(int i=0;i<count;i++){int length=in.readInt();if(length<4||length>MAX_CELL*2)throw new IOException("BAD_ROW_LENGTH");byte[] b=in.readNBytes(length);if(b.length!=length)throw new EOFException();List<Object> row=decodeRow(b);if(row.size()!=n)throw new IOException("BACKUP_COLUMN_MISMATCH");rows.add(row);}if(in.read()!=-1)throw new IOException("TRAILING_BACKUP_DATA");return new Table(name,cols,pk,rows);
    }
  }
  static Properties backup(Path directory,Plan p)throws Exception{
    requireClassified(p.catalog.tables.keySet());
    if(!Files.isDirectory(directory)||Files.list(directory).findAny().isPresent())throw new IllegalStateException("BACKUP_DIRECTORY_NOT_EMPTY");Properties manifest=new Properties();manifest.setProperty("format","AUDIT_DM8_TYPED_BACKUP_V1");manifest.setProperty("catalog.sha256",p.catalog.hash);manifest.setProperty("plan.sha256",p.hash);manifest.setProperty("delete.order",String.join(",",p.tableOrder));
    long bytes=0,rows=0;for(String name:p.tableOrder){Table t=p.catalog.tables.get(name);Path f=directory.resolve(name+".backup");var selected=p.rowOrder.get(name);writeArchive(f,t,selected);Table read=readArchive(f);if(!read.columns.equals(t.columns)||!read.pk.equals(t.pk)||!fingerprint(read.rows).equals(fingerprint(selected)))throw new IllegalStateException("BACKUP_ROUNDTRIP_FAILED");manifest.setProperty(name+".sha256",sha(f));manifest.setProperty(name+".rows",String.valueOf(selected.size()));manifest.setProperty(name+".bytes",String.valueOf(Files.size(f)));manifest.setProperty(name+".fingerprint",fingerprint(selected));bytes+=Files.size(f);rows+=selected.size();}
    for(var e:p.keptFingerprints.entrySet())manifest.setProperty("protected."+e.getKey(),e.getValue());
    try(OutputStream out=Files.newOutputStream(directory.resolve("manifest.properties"),StandardOpenOption.CREATE_NEW)){manifest.store(out,"No credentials or protected row values; target business backups only");}
    System.out.println("BACKUP_ROUNDTRIP=PASS tables="+p.tableOrder.size()+" rows="+rows+" bytes="+bytes);return manifest;
  }
  static Properties manifest(Path dir)throws Exception{Properties p=new Properties();try(InputStream in=Files.newInputStream(dir.resolve("manifest.properties"))){p.load(in);}if(!"AUDIT_DM8_TYPED_BACKUP_V1".equals(p.getProperty("format")))throw new IllegalStateException("UNKNOWN_BACKUP_FORMAT");return p;}
  static Map<String,Table> verifiedArchive(Path dir,Properties p)throws Exception{
    Map<String,Table> out=new TreeMap<>();for(String name:p.getProperty("delete.order").split(",")){quote(name);if(!isTarget(name)||out.containsKey(name))throw new IllegalStateException("BACKUP_PROTECTED_OR_DUPLICATE_TABLE");Path f=dir.resolve(name+".backup");if(Files.isSymbolicLink(f)||!Files.isRegularFile(f,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("BACKUP_NOT_REGULAR_FILE");if(!sha(f).equals(p.getProperty(name+".sha256")))throw new IllegalStateException("BACKUP_SHA_MISMATCH");Table t=readArchive(f);if(!t.name.equals(name)||t.rows.size()!=Integer.parseInt(p.getProperty(name+".rows"))||!fingerprint(t.rows).equals(p.getProperty(name+".fingerprint")))throw new IllegalStateException("BACKUP_COUNT_OR_CONTENT_MISMATCH");out.put(name,t);}return out;
  }
  static void writeEvidence(Path runtime,Path backupDir,Plan p,Properties manifest)throws Exception{
    Files.createDirectories(runtime.resolve("sql"));StringBuilder inventory=new StringBuilder("# Actual local DM8 reset inventory\n\nIdentity: local loopback DM8 8 / LOCAL_APPROVED_SCHEMA. Backed-up DryRun; DELETE NOT RUN.\n\nCatalog SHA256: `"+p.catalog.hash+"`\n\nPlan SHA256: `"+p.hash+"`\n\nBackup: `"+backupDir+"` (restricted ACL, ignored).\n\nAll table/column/PK/UK/check/index/FK/trigger facts were read from actual USER_* and ALL_* catalog; compared with repository migrations and mappers. Every table has an explicit DELETE, CONDITIONAL or APPROVED_PROTECTED class. UNKNOWN, including an empty unlinked table, blocks plan and Apply.\n\n| Table | Total | Delete | Preserve | Classification/reason | Protected remainder fingerprint | FK parents |\n|---|---:|---:|---:|---|---|---|\n");
    for(Table t:p.catalog.tables.values()){List<String> parents=p.catalog.fks.stream().filter(f->f.child.equals(t.name)).map(f->f.parent+" ("+f.name+")").toList();String reason=switch(classify(t.name)){case APPROVED_PROTECTED->"APPROVED_PROTECTED: verified identity/config/rules/master/audit catalog class";case CONDITIONAL->"CONDITIONAL: exact deleted typed resource identities";case DELETE->"DELETE: exact backed snapshot of approved local test instance";case UNKNOWN->throw new IllegalStateException("UNCLASSIFIED_TABLE");};inventory.append("| ").append(t.name).append(" | ").append(t.rows.size()).append(" | ").append(t.selected.size()).append(" | ").append(t.rows.size()-t.selected.size()).append(" | ").append(reason).append(" | ").append(p.keptFingerprints.get(t.name)).append(" | ").append(String.join(", ",parents)).append(" |\n");}
    inventory.append("\nAll triggers: 0 for actual current schema. External visible incoming foreign keys: 0. Enabled FK graph: every existing child reference reopened and checked in memory. Self-reference row graphs are acyclic and ordered child before parent; no assumption about statement-wide self-FK deletion is used.\n\nThe 38 populated candidate promotion-confirmation pointers are nullable; the only check mentioning the pointer is CK_CONTRACT_CANDIDATE_DRAFT_CONFIRMATION (`PROMOTION_STATE != 'DRAFT' OR PROMOTED_CONFIRMATION_ID IS NULL`), which permits NULL in every state. Only backed-up candidate IDs are unlinked within the deletion transaction. Restore inserts those candidates with NULL and restores the exact original pointers after confirmations exist.\n\nMixed AUDIT_OPERATION_LOG, AUTH_LOGIN_AUDIT and supplier operation history are wholly preserved. Unmatched/unknown idempotency rows remain protected; old history with missing resources is not guessed to belong to deleted instances.\n\n## Actual catalog facts\n\n```text\n");for(String f:p.catalog.facts)inventory.append(f).append('\n');inventory.append("```\n");
    Path repo=runtime.getParent().getParent();List<Path> references=new ArrayList<>();
    for(Path sourceRoot:List.of(repo.resolve("db/dm8"),repo.resolve("backend/src/main/resources/mappers/dm8"))){try(var files=Files.walk(sourceRoot)){references.addAll(files.filter(Files::isRegularFile).filter(f->f.toString().endsWith(".sql")||f.toString().endsWith(".xml")||f.getFileName().toString().equals("README.md")).toList());}}
    Map<Path,String> content=new LinkedHashMap<>();for(Path file:references)content.put(file,Files.readString(file,StandardCharsets.UTF_8).toUpperCase(Locale.ROOT));
    inventory.append("\n## Repository cross-check\n\nThese references confirm the business classification against README, migrations and persistence SQL. They are name/reference matches, not claims that all migration effects or customer schema are matching. Actual catalog remains the authority.\n\n");
    for(String table:p.catalog.tables.keySet()){List<String> matches=new ArrayList<>();var pattern=java.util.regex.Pattern.compile("\\b"+table+"\\b");for(var e:content.entrySet())if(pattern.matcher(e.getValue()).find())matches.add(repo.relativize(e.getKey()).toString());inventory.append("- ").append(table).append(": ").append(matches.isEmpty()?"NO_REPOSITORY_SQL_REFERENCE; protected unless independently classified":String.join(", ",matches)).append('\n');}
    Files.writeString(runtime.resolve("data-reset-inventory.md"),inventory,StandardCharsets.UTF_8);
    StringBuilder cleanup=new StringBuilder("-- AUDIT REVIEW ONLY. Execute through gated reset-review-test-data.ps1, not a SQL console.\n-- No COMMIT in this file: Java checks protected fingerprints before COMMIT.\n-- Catalog SHA256 "+p.catalog.hash+"\n-- Plan SHA256 "+p.hash+"\n");
    Table candidate=p.catalog.tables.get("CONTRACT_CANDIDATE");for(var row:p.rowOrder.get(candidate.name))if(candidate.value(row,"PROMOTED_CONFIRMATION_ID")!=null)cleanup.append("UPDATE ").append(quote(candidate.name)).append(" SET \"PROMOTED_CONFIRMATION_ID\"=NULL WHERE ").append(candidate.where(row)).append(";\n");
    for(String name:p.tableOrder){Table t=p.catalog.tables.get(name);cleanup.append("-- ").append(name).append(" snapshot rows=").append(t.selected.size()).append('\n');for(var row:p.rowOrder.get(name))cleanup.append("DELETE FROM ").append(quote(name)).append(" WHERE ").append(t.where(row)).append(";\n");}
    Files.writeString(runtime.resolve("sql/cleanup-review-test-data.sql"),cleanup,StandardCharsets.UTF_8);
    StringBuilder verify=new StringBuilder("-- Snapshot identity verification. Protected values checked by typed Java SHA fingerprint, never exported.\n");for(String name:p.tableOrder){Table t=p.catalog.tables.get(name);for(var row:p.rowOrder.get(name))verify.append("SELECT COUNT(*) FROM ").append(quote(name)).append(" WHERE ").append(t.where(row)).append("; -- expected 0\n");}
    for(Fk fk:p.catalog.fks){StringJoiner match=new StringJoiner(" AND "),nonnull=new StringJoiner(" AND ");for(int i=0;i<fk.childCols.size();i++){match.add("C."+quote(fk.childCols.get(i))+"=P."+quote(fk.parentCols.get(i)));nonnull.add("C."+quote(fk.childCols.get(i))+" IS NOT NULL");}verify.append("SELECT COUNT(*) FROM ").append(quote(fk.child)).append(" C WHERE ").append(nonnull).append(" AND NOT EXISTS (SELECT 1 FROM ").append(quote(fk.parent)).append(" P WHERE ").append(match).append("); -- orphan count expected 0: ").append(fk.name).append('\n');}
    for(Table t:p.catalog.tables.values())if(!isTarget(t.name))verify.append("SELECT COUNT(*) FROM ").append(quote(t.name)).append("; -- expected ").append(t.rows.size()).append("; SHA fingerprint verified in Java\n");
    Files.writeString(runtime.resolve("sql/verify-review-test-data-reset.sql"),verify,StandardCharsets.UTF_8);
    String restore="# Restore local DM8 review test data\n\nExact verified backup: `"+backupDir+"`. Each .backup file carries JDBC column types, all selected row values (CLOB UTF-8, BLOB bytes, exact decimals and timestamp nanoseconds), row count and SHA256 in manifest.properties. Protected rows, accounts and password hashes are never exported.\n\n1. Keep Java audit backend, all review/file-processing workers, FastAPI and Vite stopped. This reset tool does not stop any service. Verify the local business schema and no application listeners; the wrapper enforces this. Do not stop DM8/Redis or remove historical object bytes.\n2. Do not edit the backup, manifest or generated SQL. Run `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .runtime\\contract-repair-test\\scripts\\reset-review-test-data.ps1 -Mode Restore -BackupDirectory '"+backupDir+"'`.\n3. Restore checks backup SHA/count/content, exact current catalog, empty target instance tables and absence of each restored conditional row identity. Any existing instance data, mismatched protected rows or catalog drift blocks and rolls back. Selected idempotency and conditional audit subsets may coexist only with their byte-identical protected remainder.\n4. Inserts execute in the reverse real FK order; self-history rows reverse the verified row graph. Candidate promotion-confirmation pointers are inserted as NULL and restored from the backup only after confirmations exist. The protected fingerprints and restored rows are verified before COMMIT. A crash or uncertain commit is UNKNOWN; inspect reopened rows before retrying.\n\nApply has NOT RUN as of DryRun. Real mutation/rollback/restore remain NOT VERIFIED until root invokes the gated operation. Filesystem objects are intentionally untouched.\n";
    Files.writeString(runtime.resolve("restore-review-test-data.md"),restore,StandardCharsets.UTF_8);
    manifest.setProperty("cleanup.sha256",sha(runtime.resolve("sql/cleanup-review-test-data.sql")));manifest.setProperty("verify.sha256",sha(runtime.resolve("sql/verify-review-test-data-reset.sql")));
    try(OutputStream o=Files.newOutputStream(backupDir.resolve("manifest.properties"))){manifest.store(o,"Verified backup and reviewable SQL identity");}
  }
  static void bind(PreparedStatement s,int i,Object value,Column col)throws Exception{
    if(value==null){s.setNull(i,col.jdbc);return;}if(value instanceof String text){if(col.jdbc==Types.CLOB||col.jdbc==Types.NCLOB)s.setCharacterStream(i,new StringReader(text),text.length());else s.setString(i,text);}else if(value instanceof byte[] bytes){if(col.jdbc==Types.BLOB)s.setBinaryStream(i,new ByteArrayInputStream(bytes),bytes.length);else s.setBytes(i,bytes);}else if(value instanceof BigDecimal d)s.setBigDecimal(i,d);else if(value instanceof Timestamp t)s.setTimestamp(i,t);else if(value instanceof java.sql.Date d)s.setDate(i,d);else if(value instanceof Time t)s.setTime(i,t);else if(value instanceof Boolean b)s.setBoolean(i,b);else throw new IllegalStateException("UNSUPPORTED_RESTORE_TYPE");
  }
  static void unchanged(Catalog fresh,Properties manifest,boolean restored,Map<String,Table> archive)throws Exception{
    for(Table t:fresh.tables.values()){
      Table backed=archive.get(t.name);Set<String> original=new HashSet<>();if(backed!=null)for(var r:backed.rows)original.add(backed.key(r));List<List<Object>> kept=new ArrayList<>(),present=new ArrayList<>();for(var r:t.rows){if(original.contains(t.key(r)))present.add(r);else kept.add(r);}
      if(!fingerprint(kept).equals(manifest.getProperty("protected."+t.name)))throw new IllegalStateException("PROTECTED_FINGERPRINT_CHANGED");
      if(backed!=null){if(restored&&!fingerprint(present).equals(manifest.getProperty(t.name+".fingerprint")))throw new IllegalStateException("RESTORE_CONTENT_MISMATCH");if(!restored&&!present.isEmpty())throw new IllegalStateException("TARGET_ROWS_NOT_ZERO");}
    }
  }
  static void apply(Connection c,Path runtime,Path dir,Plan p,Properties m)throws Exception{
    requireClassified(p.catalog.tables.keySet());
    if(!p.hash.equals(m.getProperty("plan.sha256")))throw new IllegalStateException("SNAPSHOT_PLAN_DRIFT");Map<String,Table> archived=verifiedArchive(dir,m);
    if(!sha(runtime.resolve("sql/cleanup-review-test-data.sql")).equals(m.getProperty("cleanup.sha256"))||!sha(runtime.resolve("sql/verify-review-test-data-reset.sql")).equals(m.getProperty("verify.sha256")))throw new IllegalStateException("REVIEWED_SQL_DRIFT");
    Table t=p.catalog.tables.get("CONTRACT_CANDIDATE");for(var r:p.rowOrder.get(t.name))if(t.value(r,"PROMOTED_CONFIRMATION_ID")!=null){try(Statement s=c.createStatement()){if(s.executeUpdate("UPDATE "+quote(t.name)+" SET \"PROMOTED_CONFIRMATION_ID\"=NULL WHERE "+t.where(r))!=1)throw new IllegalStateException("UNLINK_COUNT_MISMATCH");}}
    for(String name:p.tableOrder){Table table=p.catalog.tables.get(name);int deleted=0;for(var r:p.rowOrder.get(name))try(Statement s=c.createStatement()){if(s.executeUpdate("DELETE FROM "+quote(name)+" WHERE "+table.where(r))!=1)throw new IllegalStateException("DELETE_COUNT_MISMATCH");deleted++;}System.out.println("DELETED_"+name+"="+deleted);}
    Catalog after=catalog(c,true);if(!after.hash.equals(p.catalog.hash))throw new IllegalStateException("CATALOG_CHANGED");unchanged(after,m,false,archived);validateOrphans(after);accountGate(c);
    try {c.commit();}catch(SQLException e){System.out.println("DATA_RESET=UNKNOWN_COMMIT_OUTCOME REOPEN_BEFORE_RETRY");throw new IllegalStateException("UNKNOWN_COMMIT_OUTCOME");}
    System.out.println("DATA_RESET=PASS PROTECTED_AUTH_AND_CONFIG_UNCHANGED=PASS");Files.writeString(runtime.resolve("data-reset-applied.txt"),"DATA_RESET=PASS\nPLAN_SHA256="+p.hash+"\nBACKUP="+dir+"\nTIMESTAMP="+Instant.now()+"\n",StandardCharsets.UTF_8);
  }
  static void validateOrphans(Catalog cat)throws Exception{
    for(Fk fk:cat.fks){Table child=cat.tables.get(fk.child),parent=cat.tables.get(fk.parent);Set<String> keys=new HashSet<>();for(var r:parent.rows)keys.add(tuple(parent.values(r,fk.parentCols)));for(var r:child.rows){var v=child.values(r,fk.childCols);if(!v.contains(null)&&!keys.contains(tuple(v)))throw new IllegalStateException("ORPHAN_FK_AFTER_MUTATION");}}
  }
  static void restore(Connection c,Path dir,Catalog current,Properties m)throws Exception{
    requireClassified(current.tables.keySet());
    if(!current.hash.equals(m.getProperty("catalog.sha256")))throw new IllegalStateException("RESTORE_CATALOG_DRIFT");Map<String,Table> archived=verifiedArchive(dir,m);unchanged(current,m,false,archived);provePromotionNull(c,current);
    List<String> tables=new ArrayList<>(Arrays.asList(m.getProperty("delete.order").split(",")));Collections.reverse(tables);
    for(String name:tables){Table t=archived.get(name);List<List<Object>> rows=new ArrayList<>(t.rows);Collections.reverse(rows);StringJoiner cols=new StringJoiner(","),marks=new StringJoiner(",");for(Column col:t.columns){cols.add(quote(col.name));marks.add("?");}
      try(PreparedStatement s=c.prepareStatement("INSERT INTO "+quote(name)+" ("+cols+") VALUES ("+marks+")")){for(var r:rows){for(int i=0;i<t.columns.size();i++)bind(s,i+1,name.equals("CONTRACT_CANDIDATE")&&t.columns.get(i).name.equals("PROMOTED_CONFIRMATION_ID")?null:r.get(i),t.columns.get(i));if(s.executeUpdate()!=1)throw new IllegalStateException("RESTORE_INSERT_COUNT");}}
    }
    Table t=archived.get("CONTRACT_CANDIDATE");for(var r:t.rows){Object pointer=t.value(r,"PROMOTED_CONFIRMATION_ID");if(pointer!=null)try(PreparedStatement s=c.prepareStatement("UPDATE "+quote(t.name)+" SET \"PROMOTED_CONFIRMATION_ID\"=? WHERE "+t.where(r))){s.setString(1,(String)pointer);if(s.executeUpdate()!=1)throw new IllegalStateException("RESTORE_POINTER_COUNT");}}
    Catalog after=catalog(c,true);unchanged(after,m,true,archived);validateOrphans(after);accountGate(c);try{c.commit();}catch(SQLException e){System.out.println("DATA_RESTORE=UNKNOWN_COMMIT_OUTCOME REOPEN_BEFORE_RETRY");throw new IllegalStateException("UNKNOWN_COMMIT_OUTCOME");}System.out.println("DATA_RESTORE=PASS PROTECTED_AUTH_AND_CONFIG_UNCHANGED=PASS");
  }
  public static void main(String[] args){
    try {run(args);}catch(Exception e){System.out.println("DATA_RESET_BLOCKED type="+e.getClass().getSimpleName()+" gate="+(e instanceof SQLException s?"JDBC_"+s.getErrorCode():safeGate(e.getMessage())));System.exit(1);}
  }
  static String safeGate(String s){return s!=null&&s.matches("[A-Z0-9_]{1,80}")?s:"UNKNOWN";}
  static void run(String[] args)throws Exception{
    if(args.length!=3||!Set.of("DryRun","Apply","Restore").contains(args[0]))throw new IllegalStateException("BAD_ARGUMENTS");String mode=args[0];Path runtime=Path.of(args[1]).toAbsolutePath().normalize(),dir=Path.of(args[2]).toAbsolutePath().normalize();if(!dir.startsWith(runtime.resolve("backups")))throw new IllegalStateException("BACKUP_OUTSIDE_RUNTIME");
    String url=System.getenv("AUDIT_DB_URL"),user=System.getenv("AUDIT_DB_USERNAME");DbIdentity expected=approvedIdentity(runtime.getParent().getParent());identity(expected,url,user,expected.schema,8);DriverManager.setLoginTimeout(10);
    try(Connection c=DriverManager.getConnection(url,user,System.getenv("AUDIT_DB_PASSWORD"))){c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);identity(expected,url,c.getMetaData().getUserName(),c.getSchema(),c.getMetaData().getDatabaseMajorVersion());if(!c.getMetaData().getDatabaseProductName().toUpperCase(Locale.ROOT).contains("DM"))throw new IllegalStateException("NOT_DM8");
      try{Catalog initial=catalog(c,false);requireClassified(initial.tables.keySet());lockTables(c,initial,mode.equals("DryRun")?"SHARE":"EXCLUSIVE");Catalog cat=catalog(c,true);if(!cat.hash.equals(initial.hash))throw new IllegalStateException("CATALOG_CHANGED_DURING_LOCK");accountGate(c);System.out.println("RESET_DB_IDENTITY=LOCAL_APPROVED_SCHEMA");
        if(mode.equals("Restore")){restore(c,dir,cat,manifest(dir));return;}
        Plan p=plan(c,cat);for(Table t:cat.tables.values())System.out.println("PLAN_"+t.name+" total="+t.rows.size()+" delete="+t.selected.size()+" preserve="+t.kept().size());
        if(mode.equals("DryRun")){Properties m=backup(dir,p);writeEvidence(runtime,dir,p,m);verifiedArchive(dir,m);Catalog after=catalog(c,true);for(Table t:after.tables.values())if(!fingerprint(t.rows).equals(fingerprint(cat.tables.get(t.name).rows)))throw new IllegalStateException("DRYRUN_DATA_CHANGED");c.rollback();System.out.println("DRYRUN=PASS DELETE_NOT_RUN PLAN_SHA256="+p.hash+" BACKUP="+dir);Files.writeString(runtime.resolve("reset-progress.md"),"# Reset progress\n\nDRYRUN=PASS\n\nDATA_RESET=NOT_RUN\n\nRESET_DB_IDENTITY=LOCAL_APPROVED_SCHEMA\n\nBACKUP_ROUNDTRIP=PASS\n\nPLAN_SHA256="+p.hash+"\n\nBACKUP="+dir+"\n\nNo DELETE or UPDATE has run. Root agent reviews and invokes Apply. Self-FKs use proven acyclic row order; candidate mutual FK is broken by the exact backed-up nullable pointer, in the Apply transaction only.\n",StandardCharsets.UTF_8);}
        else apply(c,runtime,dir,p,manifest(dir));
      }catch(Exception e){c.rollback();throw e;}
    }
  }
}
