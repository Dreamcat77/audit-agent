import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Controlled, forward-only Word97 CHECK expansion. Never resets/restores/deletes data. */
public class Word97Maintenance {
  static final String FORMAT="WORD97_COMPLETE_TYPED_SNAPSHOT_V1";
  static final Path ROOT=ResetReviewTestData.APPROVED_ROOT;
  static final String AREA=".runtime/contract-repair-test/word97-maintenance";
  static final List<String> SOURCES=List.of("tools/local-test/ResetReviewTestData.java","tools/local-test/ContractLocalMigration.java","tools/local-test/Word97LocalMigration.java","tools/local-test/Word97Maintenance.java","tools/local-test/maintain-word97-local.ps1","tools/local-test/contract-test-guards.ps1");
  static final Set<Integer> JDBC_TYPES=Set.of(Types.CHAR,Types.NCHAR,Types.VARCHAR,Types.NVARCHAR,Types.LONGVARCHAR,Types.LONGNVARCHAR,Types.CLOB,Types.NCLOB,Types.BLOB,Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY,Types.DECIMAL,Types.NUMERIC,Types.BIGINT,Types.INTEGER,Types.SMALLINT,Types.TINYINT,Types.TIMESTAMP,Types.DATE,Types.TIME,Types.BOOLEAN,Types.BIT);
  record Fact(String kind,List<String> values) {}
  record View(Map<String,ResetReviewTestData.Table> tables,List<Fact> facts,List<String> checks) {}
  interface Database {
    View inspect() throws Exception;
    void execute(String ddl) throws Exception;
  }
  record Progress(int completed,int intent,String state,String hash,int sequence) {}
  static void fail(String gate){throw new IllegalStateException(gate);}
  static String triggerCatalogSql(){return "SELECT TABLE_NAME,TRIGGER_NAME,STATUS,TRIGGERING_EVENT,TRIGGERING_TYPE,TABLE_OWNER,BASE_OBJECT_TYPE,COLUMN_NAME,DESCRIPTION,TRIGGER_BODY FROM USER_TRIGGERS ORDER BY TABLE_NAME,TRIGGER_NAME";}
  static String hash(String value)throws Exception{return ResetReviewTestData.hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
  static List<String> plan(){List<String> out=new ArrayList<>();for(String table:Word97LocalMigration.TABLES)out.addAll(Word97LocalMigration.steps(table,"OLD","MISSING"));return List.copyOf(out);}
  static List<String> stateAt(int stage){
    if(stage<0||stage>8)throw new IllegalStateException("WORD97_UNKNOWN_PREFIX");
    List<List<String>> states=List.of(List.of("OLD","MISSING"),List.of("OLD","NEW"),List.of("MISSING","NEW"),List.of("NEW","NEW"),List.of("NEW","MISSING"));
    List<String> out=new ArrayList<>(states.get(Math.min(stage,4)));out.addAll(states.get(Math.max(0,stage-4)));return List.copyOf(out);
  }
  static int prefix(List<String> checks){for(int i=0;i<=8;i++)if(stateAt(i).equals(checks))return i;throw new IllegalStateException("WORD97_NOT_EXACT_EIGHT_STEP_PREFIX");}
  static boolean mutable(Fact fact){
    if(!Set.of("CONSTRAINT","CONSTRAINT_COLUMN").contains(fact.kind())||fact.values().size()<2)return false;
    String table=fact.values().get(0),name=fact.values().get(1);
    return Word97LocalMigration.TABLES.contains(table)&&(Word97LocalMigration.name(table).equals(name)||(Word97LocalMigration.name(table)+"_DOC_GUARD").equals(name));
  }
  static String projection(View view)throws Exception{
    List<List<Object>> rows=new ArrayList<>();for(Fact f:view.facts())if(!mutable(f)){List<Object> row=new ArrayList<>();row.add(f.kind());row.addAll(f.values());rows.add(row);}return ResetReviewTestData.fingerprint(rows);
  }
  static void validate(View view)throws Exception{
    ResetReviewTestData.requireClassified(view.tables().keySet());prefix(view.checks());
    for(String required:List.of("PROJECT_FILE","PROJECT_FILE_PARSE_RESULT","SYS_USER","SYS_ROLE","SYS_USER_ROLE","AUDIT_OPERATION_LOG","AUTH_LOGIN_AUDIT"))if(!view.tables().containsKey(required))fail("WORD97_REQUIRED_TABLE_MISSING");
    for(var entry:view.tables().entrySet()){
      var table=entry.getValue();ResetReviewTestData.quote(table.name);
      if(!entry.getKey().equals(table.name)||table.pk.isEmpty()||table.columns.isEmpty())fail("WORD97_UNPROVEN_TABLE_METADATA");
      Set<String> names=new HashSet<>();for(var col:table.columns){ResetReviewTestData.quote(col.name());if(!names.add(col.name())||!JDBC_TYPES.contains(col.jdbc())||col.sqlType()==null||col.sqlType().isBlank())fail("WORD97_UNKNOWN_COLUMN_OR_TYPE");}
      for(String pk:table.pk)if(!names.contains(pk))fail("WORD97_UNKNOWN_PRIMARY_KEY_COLUMN");
      for(var row:table.rows){if(row.size()!=table.columns.size())fail("WORD97_ROW_SHAPE_DRIFT");for(int i=0;i<row.size();i++)if(row.get(i)==null&&!table.columns.get(i).nullable())fail("WORD97_NOT_NULL_DATA_DRIFT");ResetReviewTestData.encodeRow(row);}
    }
    for(String table:Word97LocalMigration.TABLES){var t=view.tables().get(table);var col=t.columns.get(t.index("PARSE_METHOD"));if(col.jdbc()!=Types.VARCHAR||!"VARCHAR".equals(col.sqlType())||col.nullable()!=table.equals("PROJECT_FILE"))fail("WORD97_PARSE_METHOD_NULLABILITY_DRIFT");}
  }
  static List<String> tableNames(Properties p){String value=p.getProperty("tables","");List<String> out=Arrays.asList(value.split(",",-1));if(value.isEmpty()||new HashSet<>(out).size()!=out.size()||!new TreeSet<>(out).stream().toList().equals(out))throw new IllegalStateException("WORD97_BACKUP_TABLE_SET_INVALID");ResetReviewTestData.requireClassified(out);return out;}
  static String columnsHash(ResetReviewTestData.Table table)throws Exception{return hash(table.columns.toString()+"|"+table.pk);}
  static void nullability(int jdbc,String dictionary,boolean actual){if(jdbc==ResultSetMetaData.columnNullableUnknown||!Set.of("Y","N").contains(Objects.toString(dictionary,""))||actual!=dictionary.equals("Y")||actual!=(jdbc==ResultSetMetaData.columnNullable))fail("WORD97_UNKNOWN_OR_DRIFTED_NULLABILITY");}
  static byte[] factBytes(List<Fact> facts)throws Exception{
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream out=new DataOutputStream(bytes)){out.writeUTF("WORD97_CATALOG_V1");out.writeInt(facts.size());for(Fact f:facts){List<Object> row=new ArrayList<>();row.add(f.kind());row.addAll(f.values());byte[] b=ResetReviewTestData.encodeRow(row);out.writeInt(b.length);out.write(b);}}return bytes.toByteArray();
  }
  static void writeNew(Path file,byte[] bytes)throws Exception{try(FileChannel out=FileChannel.open(file,StandardOpenOption.WRITE,StandardOpenOption.CREATE_NEW)){ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())out.write(buffer);out.force(true);}}
  static void storeNew(Path file,Properties p)throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();p.store(bytes,"Restricted local complete snapshot. Do not copy to Git or evidence.");writeNew(file,bytes.toByteArray());}
  static Properties read(Path file)throws Exception{Properties p=new Properties();try(InputStream in=Files.newInputStream(file)){p.load(in);}return p;}
  static void regular(Path file)throws Exception{if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file)||!file.toRealPath().equals(file.toAbsolutePath().normalize()))fail("WORD97_BACKUP_FILE_NOT_CANONICAL");}
  static Properties snapshot(Path dir,View view,String identity,String source,String proof)throws Exception{
    validate(view);if(prefix(view.checks())!=0)fail("WORD97_NEW_SNAPSHOT_REQUIRES_OLD_EXACT_CHECKS");
    try(var files=Files.list(dir)){if(files.anyMatch(p->!Set.of("maintenance.lock","writers-stopped.properties").contains(p.getFileName().toString())))fail("WORD97_SNAPSHOT_DIRECTORY_NOT_EMPTY");}
    Properties p=new Properties();p.setProperty("format",FORMAT);p.setProperty("identity.sha256",identity);p.setProperty("source.sha256",source);p.setProperty("writers.proof.sha256",proof);p.setProperty("created",Instant.now().toString());p.setProperty("tables",String.join(",",new TreeSet<>(view.tables().keySet())));p.setProperty("projection",projection(view));
    writeNew(dir.resolve("catalog.typed"),factBytes(view.facts()));p.setProperty("catalog.sha256",ResetReviewTestData.sha(dir.resolve("catalog.typed")));
    for(String name:tableNames(p)){
      var table=view.tables().get(name);Path file=dir.resolve(name+".backup");ResetReviewTestData.writeArchive(file,table,table.rows);
      // Force the full typed archive before any journal can authorize DDL.
      try(FileChannel archive=FileChannel.open(file,StandardOpenOption.WRITE)){archive.force(true);}
      var reopened=ResetReviewTestData.readArchive(file);if(!reopened.name.equals(name)||!reopened.columns.equals(table.columns)||!reopened.pk.equals(table.pk)||!ResetReviewTestData.fingerprint(reopened.rows).equals(ResetReviewTestData.fingerprint(table.rows)))fail("WORD97_TYPED_ROUNDTRIP_FAILED");
      p.setProperty(name+".sha256",ResetReviewTestData.sha(file));p.setProperty(name+".rows",String.valueOf(table.rows.size()));p.setProperty(name+".fingerprint",ResetReviewTestData.fingerprint(table.rows));p.setProperty(name+".columns",columnsHash(table));
    }
    p.setProperty("plan.sha256",hash(String.join("\n",plan())));storeNew(dir.resolve("snapshot.properties"),p);append(dir,ResetReviewTestData.sha(dir.resolve("snapshot.properties")),null,0,0,"SNAPSHOT");return p;
  }
  static void artifacts(Path dir,Properties p,String identity,String source)throws Exception{
    if(!FORMAT.equals(p.getProperty("format"))||!identity.equals(p.getProperty("identity.sha256"))||!source.equals(p.getProperty("source.sha256"))||!hash(String.join("\n",plan())).equals(p.getProperty("plan.sha256")))fail("WORD97_SNAPSHOT_IDENTITY_OR_SOURCE_DRIFT");
    for(String file:List.of("snapshot.properties","journal.log","catalog.typed","writers-stopped.properties"))regular(dir.resolve(file));
    if(!ResetReviewTestData.sha(dir.resolve("catalog.typed")).equals(p.getProperty("catalog.sha256"))||!ResetReviewTestData.sha(dir.resolve("writers-stopped.properties")).equals(p.getProperty("writers.proof.sha256")))fail("WORD97_CATALOG_OR_WRITERS_PROOF_HASH_DRIFT");
    Set<String> expected=new HashSet<>(List.of("snapshot.properties","journal.log","catalog.typed","writers-stopped.properties","maintenance.lock"));
    for(String name:tableNames(p)){
      Path file=dir.resolve(name+".backup");expected.add(file.getFileName().toString());regular(file);if(!ResetReviewTestData.sha(file).equals(p.getProperty(name+".sha256")))fail("WORD97_BACKUP_HASH_DRIFT");var table=ResetReviewTestData.readArchive(file);
      if(!name.equals(table.name)||!String.valueOf(table.rows.size()).equals(p.getProperty(name+".rows"))||!ResetReviewTestData.fingerprint(table.rows).equals(p.getProperty(name+".fingerprint"))||!columnsHash(table).equals(p.getProperty(name+".columns")))fail("WORD97_BACKUP_COUNT_CONTENT_OR_COLUMNS_DRIFT");
    }
    try(var files=Files.list(dir)){if(files.anyMatch(file->!expected.contains(file.getFileName().toString())))fail("WORD97_UNKNOWN_BACKUP_ARTIFACT");}
    progress(dir,ResetReviewTestData.sha(dir.resolve("snapshot.properties")));
  }
  static void unchanged(View view,Properties p)throws Exception{
    validate(view);if(!new TreeSet<>(view.tables().keySet()).equals(new TreeSet<>(tableNames(p)))||!projection(view).equals(p.getProperty("projection")))fail("WORD97_UNRELATED_SCHEMA_DRIFT");
    for(var table:view.tables().values())if(!ResetReviewTestData.fingerprint(table.rows).equals(p.getProperty(table.name+".fingerprint"))||!columnsHash(table).equals(p.getProperty(table.name+".columns")))fail("WORD97_BUSINESS_OR_PROTECTED_ROWS_CHANGED");
  }
  static Progress progress(Path dir,String snapshotHash)throws Exception{
    byte[] bytes=Files.readAllBytes(dir.resolve("journal.log"));if(bytes.length==0||bytes.length>1024*1024||bytes[bytes.length-1]!='\n')fail("WORD97_JOURNAL_TORN_OR_TOO_LARGE");
    Progress previous=null;for(String line:new String(bytes,StandardCharsets.US_ASCII).split("\n")){
      String[] a=line.split("\\|",-1);if(a.length!=8||!a[1].equals(snapshotHash))fail("WORD97_JOURNAL_BACKUP_BINDING_DRIFT");
      int seq,completed,intent;try{seq=Integer.parseInt(a[0]);completed=Integer.parseInt(a[2]);intent=Integer.parseInt(a[3]);}catch(NumberFormatException e){throw new IllegalStateException("WORD97_JOURNAL_SYNTAX");}
      String body=String.join("|",Arrays.copyOf(a,7));if(!hash(body).equals(a[7])||!a[6].equals(previous==null?"ROOT":previous.hash())||seq!=(previous==null?0:previous.sequence()+1))fail("WORD97_JOURNAL_HASH_CHAIN_DRIFT");
      transition(previous,completed,intent,a[4]);previous=new Progress(completed,intent,a[4],a[7],seq);
    }return previous;
  }
  static void transition(Progress before,int completed,int intent,String state){
    if(completed<0||completed>8||intent<completed||intent>8||intent>completed+1)fail("WORD97_JOURNAL_INVALID_PREFIX");
    if(before==null){if(completed!=0||intent!=0||!state.equals("SNAPSHOT"))fail("WORD97_JOURNAL_MISSING_INITIAL_SNAPSHOT");return;}
    if(state.equals("INTENT")){if(before.completed()!=before.intent()||completed!=before.completed()||intent!=completed+1)fail("WORD97_JOURNAL_ILLEGAL_INTENT");}
    else if(state.equals("CONFIRMED")){if(before.intent()!=before.completed()+1||completed!=before.intent()||intent!=completed)fail("WORD97_JOURNAL_UNPROVEN_CONFIRMATION");}
    else if(state.equals("RECONCILED")){if(before.intent()!=before.completed()+1||(completed!=before.completed()&&completed!=before.intent())||intent!=completed)fail("WORD97_JOURNAL_ILLEGAL_RECONCILIATION");}
    else fail("WORD97_JOURNAL_UNKNOWN_STATE");
  }
  static Progress append(Path dir,String snapshotHash,Progress previous,int completed,int intent,String state)throws Exception{
    transition(previous,completed,intent,state);String body=(previous==null?0:previous.sequence()+1)+"|"+snapshotHash+"|"+completed+"|"+intent+"|"+state+"|"+Instant.now()+"|"+(previous==null?"ROOT":previous.hash());String digest=hash(body);
    try(FileChannel out=FileChannel.open(dir.resolve("journal.log"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.APPEND)){ByteBuffer data=ByteBuffer.wrap((body+"|"+digest+"\n").getBytes(StandardCharsets.US_ASCII));while(data.hasRemaining())out.write(data);out.force(true);}return new Progress(completed,intent,state,digest,previous==null?0:previous.sequence()+1);
  }
  static int reconcile(Path dir,Properties p,View view,boolean record)throws Exception{
    unchanged(view,p);String sha=ResetReviewTestData.sha(dir.resolve("snapshot.properties"));Progress progress=progress(dir,sha);int actual=prefix(view.checks());
    if(progress.completed()==progress.intent()){if(actual!=progress.completed())fail("WORD97_UNOWNED_CATALOG_PROGRESS");}
    else {
      if(actual!=progress.completed()&&actual!=progress.intent())fail("WORD97_UNKNOWN_DDL_OUTCOME_PREFIX");
      if(record){append(dir,sha,progress,actual,actual,"RECONCILED");return -1;}
      fail("WORD97_UNKNOWN_REQUIRES_SAME_JOURNAL_RECONCILIATION");
    }return actual;
  }
  static void apply(Database db,Path dir,Properties p,String identity,String source)throws Exception{
    artifacts(dir,p,identity,source);int stage=reconcile(dir,p,db.inspect(),true);if(stage<0)fail("WORD97_RECONCILED_REOPEN_APPLY_REQUIRED");
    String sha=ResetReviewTestData.sha(dir.resolve("snapshot.properties"));while(stage<8){
      artifacts(dir,p,identity,source);View before=db.inspect();unchanged(before,p);if(prefix(before.checks())!=stage)fail("WORD97_PREFIX_CHANGED_BEFORE_DDL");
      Progress j=progress(dir,sha);j=append(dir,sha,j,stage,stage+1,"INTENT");
      // execute() gates writers again after the fsynced intent and immediately before the DDL.
      db.execute(plan().get(stage));View after=db.inspect();unchanged(after,p);if(prefix(after.checks())!=stage+1)fail("WORD97_DDL_EFFECT_NOT_EXACT_NEXT_PREFIX");
      artifacts(dir,p,identity,source);append(dir,sha,j,stage+1,stage+1,"CONFIRMED");stage++;System.out.println("WORD97_DDL_PREFIX_VERIFIED="+stage+" ROWS_UNCHANGED=true");
    }
  }
  static void verify(Database db,Path dir,Properties p,String identity,String source)throws Exception{artifacts(dir,p,identity,source);int stage=reconcile(dir,p,db.inspect(),true);if(stage<0)fail("WORD97_RECONCILED_REOPEN_VERIFY_REQUIRED");if(stage!=8)fail("WORD97_MIGRATION_INCOMPLETE");}
  static void canonical(Path repo,Path dir)throws Exception{
    if(!repo.equals(ROOT)||!repo.toRealPath().equals(repo)||!dir.startsWith(repo.resolve(AREA))||!Objects.equals(dir.getParent(),repo.resolve(AREA))||!dir.getFileName().toString().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,80}"))fail("WORD97_CANONICAL_IGNORED_RUNTIME_REQUIRED");
    for(Path path=dir;path!=null&&path.startsWith(repo);path=path.getParent())if(Files.isSymbolicLink(path)||!path.toRealPath().equals(path))fail("WORD97_REPARSE_PATH_REJECTED");
    Process ignored=new ProcessBuilder("git.exe","-C",repo.toString(),"check-ignore","--quiet","--",dir.toString()).start();if(!ignored.waitFor(10,TimeUnit.SECONDS)||ignored.exitValue()!=0)fail("WORD97_BACKUP_MUST_BE_IGNORED");
  }
  static String source(Path repo)throws Exception{StringBuilder value=new StringBuilder();for(String name:SOURCES)value.append(name).append('|').append(ResetReviewTestData.sha(repo.resolve(name))).append('\n');value.append(ResetReviewTestData.sha(repo.resolve("db/dm8/CONTRACT__project_file_word97_method.sql")));return hash(value.toString());}
  static void writers(Path repo,Path dir,String approvedSha)throws Exception{
    Process proof=new ProcessBuilder("powershell.exe","-NoProfile","-ExecutionPolicy","Bypass","-File",repo.resolve("tools/local-test/maintain-word97-local.ps1").toString(),"-Mode","Verify","-BackupDirectory",dir.toString(),"-ExpectedSourceSha",approvedSha,"-WritersProofOnly").redirectErrorStream(true).start();
    if(!proof.waitFor(20,TimeUnit.SECONDS)){proof.destroyForcibly();fail("WORD97_WRITERS_PROOF_TIMEOUT");}String text=new String(proof.getInputStream().readNBytes(8192),StandardCharsets.UTF_8).trim();if(proof.exitValue()!=0||!text.equals("WORD97_WRITERS_STOPPED=PROVEN"))fail("WORD97_WRITERS_OR_ACL_PROOF_UNAVAILABLE");
  }
  static final class JdbcDatabase implements Database {
    final Connection c;final Path repo,dir;final ResetReviewTestData.DbIdentity expected;final String approvedSha;
    JdbcDatabase(Connection connection,Path root,Path backup,ResetReviewTestData.DbIdentity identity,String sha){c=connection;repo=root;dir=backup;expected=identity;approvedSha=sha;}
    void gates()throws Exception{
      ResetReviewTestData.identity(expected,System.getenv("AUDIT_DB_URL"),c.getMetaData().getUserName(),c.getSchema(),c.getMetaData().getDatabaseMajorVersion());if(!c.getMetaData().getDatabaseProductName().toUpperCase(Locale.ROOT).contains("DM"))fail("WORD97_NOT_DM8");writers(repo,dir,approvedSha);ContractLocalMigration.sessions(c);
    }
    public View inspect()throws Exception{
      gates();var metadata=ResetReviewTestData.catalog(c,false);ResetReviewTestData.requireClassified(metadata.tables().keySet());ResetReviewTestData.lockTables(c,metadata,"SHARE");var catalog=ResetReviewTestData.catalog(c,true);if(!catalog.hash().equals(metadata.hash()))fail("WORD97_CATALOG_CHANGED_DURING_READ");
      List<Fact> facts=new ArrayList<>();
      String[][] queries={
        {"TABLE","SELECT TABLE_NAME FROM USER_TABLES ORDER BY TABLE_NAME"},
        {"COLUMN","SELECT TABLE_NAME,COLUMN_NAME,DATA_TYPE,DATA_LENGTH,DATA_PRECISION,DATA_SCALE,NULLABLE,DATA_DEFAULT,COLUMN_ID FROM USER_TAB_COLUMNS ORDER BY TABLE_NAME,COLUMN_ID"},
        {"CONSTRAINT","SELECT TABLE_NAME,CONSTRAINT_NAME,CONSTRAINT_TYPE,R_OWNER,R_CONSTRAINT_NAME,DELETE_RULE,STATUS,SEARCH_CONDITION FROM USER_CONSTRAINTS ORDER BY TABLE_NAME,CONSTRAINT_NAME"},
        {"CONSTRAINT_COLUMN","SELECT TABLE_NAME,CONSTRAINT_NAME,COLUMN_NAME,POSITION FROM USER_CONS_COLUMNS ORDER BY TABLE_NAME,CONSTRAINT_NAME,POSITION"},
        {"INDEX","SELECT TABLE_NAME,INDEX_NAME,UNIQUENESS,STATUS,INDEX_TYPE FROM USER_INDEXES ORDER BY TABLE_NAME,INDEX_NAME"},
        {"INDEX_COLUMN","SELECT TABLE_NAME,INDEX_NAME,COLUMN_NAME,COLUMN_POSITION,DESCEND FROM USER_IND_COLUMNS ORDER BY TABLE_NAME,INDEX_NAME,COLUMN_POSITION"},
        {"TRIGGER",triggerCatalogSql()}
      };
      for(String[] query:queries)for(var values:ResetReviewTestData.query(c,query[1]))facts.add(new Fact(query[0],Collections.unmodifiableList(new ArrayList<>(values))));
      // Unknown JDBC nullability and unsupported types must fail even for empty tables.
      for(var table:catalog.tables().values()){
        List<Fact> cols=facts.stream().filter(f->f.kind().equals("COLUMN")&&f.values().get(0).equals(table.name)).toList();if(cols.size()!=table.columns.size())fail("WORD97_COLUMN_CATALOG_INCOMPLETE");
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT * FROM "+ResetReviewTestData.quote(table.name)+" WHERE 1=0")){
          ResultSetMetaData m=r.getMetaData();for(int i=1;i<=m.getColumnCount();i++){
            if(!JDBC_TYPES.contains(m.getColumnType(i)))fail("WORD97_UNKNOWN_JDBC_TYPE_OR_NULLABILITY");
            String name=m.getColumnName(i);List<Fact> matching=cols.stream().filter(f->f.values().get(1).equals(name)).toList();if(matching.size()!=1)fail("WORD97_COLUMN_CATALOG_INCOMPLETE");nullability(m.isNullable(i),matching.get(0).values().get(6),table.columns.get(table.index(name)).nullable());
          }
        }
      }
      List<String> checks=Word97LocalMigration.catalog(c);View view=new View(catalog.tables(),facts,checks);validate(view);ResetReviewTestData.validateOrphans(catalog);gates();return view;
    }
    public void execute(String ddl)throws Exception{
      if(!plan().contains(ddl))fail("WORD97_UNAPPROVED_DDL");gates();c.rollback();gates();c.setReadOnly(false);c.setAutoCommit(true);
      try(Statement statement=c.createStatement()){statement.setQueryTimeout(30);statement.execute(ddl);}finally{c.setAutoCommit(false);c.setReadOnly(true);}
    }
  }
  public static void main(String[] args){try{run(args);}catch(Exception e){System.out.println("WORD97_MAINTENANCE=BLOCKED OUTCOME=UNKNOWN_UNTIL_SAME_JOURNAL_REOPEN gate="+(e instanceof SQLException s?"JDBC_"+s.getErrorCode():ResetReviewTestData.safeGate(e.getMessage())));System.exit(1);}}
  static void run(String[] args)throws Exception{
    if(args.length!=4||!Set.of("Snapshot","Apply","Verify").contains(args[0])||!args[3].matches("[a-f0-9]{40}"))fail("WORD97_BAD_ARGUMENTS");String mode=args[0],approvedSha=args[3];Path repo=Path.of(args[1]).toAbsolutePath().normalize(),dir=Path.of(args[2]).toAbsolutePath().normalize();canonical(repo,dir);Word97LocalMigration.approvedSql(repo);writers(repo,dir,approvedSha);
    var expected=ResetReviewTestData.approvedIdentity(repo);String url=System.getenv("AUDIT_DB_URL"),user=System.getenv("AUDIT_DB_USERNAME");ResetReviewTestData.identity(expected,url,user,expected.schema,8);String identity=hash(expected.url+"\n"+expected.user+"\n"+expected.schema),source=source(repo);
    Properties proof=read(dir.resolve("writers-stopped.properties"));if(!"WORD97_WRITERS_STOPPED_V1".equals(proof.getProperty("format"))||!repo.toString().replace('\\','/').equals(proof.getProperty("root"))||!source.equals(proof.getProperty("source.sha256"))||!approvedSha.equals(proof.getProperty("head")))fail("WORD97_CONTROLLED_STOP_PROOF_REQUIRED");
    try(FileChannel lock=FileChannel.open(dir.resolve("maintenance.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock owned=lock.tryLock()){
      if(owned==null)fail("WORD97_ANOTHER_MAINTENANCE_HELPER_ACTIVE");DriverManager.setLoginTimeout(10);
      try(Connection c=DriverManager.getConnection(url,user,System.getenv("AUDIT_DB_PASSWORD"))){
        c.setAutoCommit(false);c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);Database db=new JdbcDatabase(c,repo,dir,expected,approvedSha);
        try{
          if(mode.equals("Snapshot")){View before=db.inspect();Properties p=snapshot(dir,before,identity,source,ResetReviewTestData.sha(dir.resolve("writers-stopped.properties")));artifacts(dir,p,identity,source);unchanged(db.inspect(),p);System.out.println("WORD97_SNAPSHOT=PASS FULL_TYPED_ROUNDTRIP=true DDL_EXECUTED=false TABLES="+before.tables().size());}
          else{Properties p=read(dir.resolve("snapshot.properties"));if(mode.equals("Apply"))apply(db,dir,p,identity,source);else verify(db,dir,p,identity,source);System.out.println("WORD97_"+mode.toUpperCase(Locale.ROOT)+"=PASS PREFIX=8 ROWS_UNCHANGED=true");}
        }finally{c.rollback();}
      }
    }
  }
}
