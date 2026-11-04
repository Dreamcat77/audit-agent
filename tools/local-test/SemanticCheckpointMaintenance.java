import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Additive-only, three-step semantic schema maintenance. No reset, DROP or restore path. */
public final class SemanticCheckpointMaintenance {
    static final String FORMAT="SEMANTIC_COMPLETE_TYPED_SNAPSHOT_V1";
    static final String AREA=".runtime/contract-repair-test/semantic-maintenance";
    static final String READER_FILE="catalog-reader-revision.properties";
    static final String PREDECESSOR_HEAD="f69be8eae55d1b54185de8805d92478e1d1c64d6";
    static final String PREDECESSOR_SOURCE="4dea4c0f977acd9ad08a74ccc892ab622a31fb5a033e111d616d015bfc211ba3";
    static final String PREDECESSOR_DDL="c377f0da3de48e9f57439081f9fade7126b7e7776e54026e4e8c842208d363dd";
    static final List<String> SOURCES=List.of("tools/local-test/ResetReviewTestData.java","tools/local-test/ContractTestStorageProbe.java","tools/local-test/ContractLocalMigration.java","tools/local-test/Word97LocalMigration.java","tools/local-test/Word97Maintenance.java","tools/local-test/maintain-word97-local.ps1","tools/local-test/SemanticCheckpointCatalog.java","tools/local-test/SemanticCheckpointMaintenance.java","tools/local-test/maintain-semantic-checkpoints-local.ps1","tools/local-test/contract-test-guards.ps1");
    record View(Map<String,ResetReviewTestData.Table> tables,List<Word97Maintenance.Fact> facts,int stage) {}
    interface Database {View inspect()throws Exception;void execute(String ddl)throws Exception;}
    static void fail(String gate){throw new IllegalStateException(gate);}
    static String hash(String value)throws Exception{return Word97Maintenance.hash(value);}
    static String source(Path root)throws Exception{StringBuilder out=new StringBuilder();for(String name:SOURCES)out.append(name).append('|').append(ResetReviewTestData.sha(root.resolve(name))).append('\n');out.append(ResetReviewTestData.sha(root.resolve(SemanticCheckpointCatalog.DDL)));return hash(out.toString());}
    static String projection(View view)throws Exception{return hash(ResetReviewTestData.fingerprint(view.facts().stream().filter(f->!SemanticCheckpointCatalog.TABLES.contains(f.values().get(0))).map(f->{List<Object> row=new ArrayList<>();row.add(f.kind());row.addAll(f.values());return row;}).toList()));}
    static void validate(View view)throws Exception{
        if(view.stage()<0||view.stage()>3)fail("SEMANTIC_CATALOG_PREFIX_UNKNOWN");
        for(String table:view.tables().keySet())if(!SemanticCheckpointCatalog.TABLES.contains(table))ResetReviewTestData.requireClassified(List.of(table));
        for(String required:List.of("SYS_USER","SYS_ROLE","SYS_USER_ROLE","AUTH_LOGIN_AUDIT","AUDIT_OPERATION_LOG","CONTRACT_REVIEW_RUN","CONTRACT_REVIEW_STEP","CONTRACT_REVIEW_STEP_ATTEMPT"))if(!view.tables().containsKey(required))fail("SEMANTIC_BACKUP_REQUIRED_TABLE_MISSING");
        for(var table:view.tables().values()){
            ResetReviewTestData.quote(table.name);if(table.pk.isEmpty()||table.columns.isEmpty())fail("SEMANTIC_BACKUP_METADATA_UNKNOWN");
            Set<String> names=new HashSet<>();for(var column:table.columns){ResetReviewTestData.quote(column.name());if(!names.add(column.name())||!Word97Maintenance.JDBC_TYPES.contains(column.jdbc())||column.sqlType()==null||column.sqlType().isBlank())fail("SEMANTIC_BACKUP_JDBC_TYPE_UNKNOWN");}
            if(!names.containsAll(table.pk))fail("SEMANTIC_BACKUP_PK_COLUMN_UNKNOWN");
            for(var row:table.rows){if(row.size()!=table.columns.size())fail("SEMANTIC_BACKUP_ROW_SHAPE");for(int i=0;i<row.size();i++)if(row.get(i)==null&&!table.columns.get(i).nullable())fail("SEMANTIC_BACKUP_NOT_NULL_DATA_DRIFT");ResetReviewTestData.encodeRow(row);}
        }
    }
    static List<String> names(Properties p){String names=p.getProperty("tables","");var out=Arrays.asList(names.split(",",-1));if(names.isBlank()||new TreeSet<>(out).size()!=out.size()||!new TreeSet<>(out).stream().toList().equals(out))fail("SEMANTIC_BACKUP_TABLE_SET_UNKNOWN");for(String table:out)ResetReviewTestData.requireClassified(List.of(table));return out;}
    static Properties snapshot(Path dir,View view,String identity,String source,String ddlHash)throws Exception{
        validate(view);if(view.stage()!=0||view.tables().keySet().stream().anyMatch(SemanticCheckpointCatalog.TABLES::contains))fail("SEMANTIC_NEW_SNAPSHOT_REQUIRES_ABSENT_TABLES");
        try(var files=Files.list(dir)){if(files.anyMatch(file->!Set.of("maintenance.lock","writers-stopped.properties").contains(file.getFileName().toString())))fail("SEMANTIC_NEW_BACKUP_REQUIRED");}
        Properties p=new Properties();p.setProperty("format",FORMAT);p.setProperty("identity",identity);p.setProperty("source",source);p.setProperty("ddl",ddlHash);p.setProperty("created",Instant.now().toString());p.setProperty("tables",String.join(",",new TreeSet<>(view.tables().keySet())));p.setProperty("projection",projection(view));
        Word97Maintenance.writeNew(dir.resolve("catalog.typed"),Word97Maintenance.factBytes(view.facts()));p.setProperty("catalog",ResetReviewTestData.sha(dir.resolve("catalog.typed")));p.setProperty("writers",ResetReviewTestData.sha(dir.resolve("writers-stopped.properties")));
        for(String name:names(p)){
            var table=view.tables().get(name);Path file=dir.resolve(name+".backup");ResetReviewTestData.writeArchive(file,table,table.rows);try(var channel=FileChannel.open(file,StandardOpenOption.WRITE)){channel.force(true);}
            var reopened=ResetReviewTestData.readArchive(file);if(!name.equals(reopened.name)||!table.columns.equals(reopened.columns)||!table.pk.equals(reopened.pk)||!ResetReviewTestData.fingerprint(table.rows).equals(ResetReviewTestData.fingerprint(reopened.rows)))fail("SEMANTIC_TYPED_ROUNDTRIP_FAILED");
            p.setProperty(name+".hash",ResetReviewTestData.sha(file));p.setProperty(name+".rows",Integer.toString(table.rows.size()));p.setProperty(name+".fingerprint",ResetReviewTestData.fingerprint(table.rows));p.setProperty(name+".columns",Word97Maintenance.columnsHash(table));
        }
        Word97Maintenance.storeNew(dir.resolve("snapshot.properties"),p);Word97Maintenance.append(dir,ResetReviewTestData.sha(dir.resolve("snapshot.properties")),null,0,0,"SNAPSHOT");return p;
    }
    static void artifacts(Path dir,Properties p,String identity,String source,String ddlHash)throws Exception{
        if(!FORMAT.equals(p.getProperty("format"))||!identity.equals(p.getProperty("identity"))||!ddlHash.equals(p.getProperty("ddl")))fail("SEMANTIC_BACKUP_IDENTITY_SOURCE_OR_DDL_DRIFT");
        boolean revised=readerArtifacts(dir,p,source,ddlHash);
        Set<String> expected=new HashSet<>(List.of("snapshot.properties","catalog.typed","writers-stopped.properties","journal.log","maintenance.lock"));
        if(revised)expected.add(READER_FILE);
        for(String file:List.of("snapshot.properties","catalog.typed","writers-stopped.properties","journal.log"))Word97Maintenance.regular(dir.resolve(file));
        if(!ResetReviewTestData.sha(dir.resolve("catalog.typed")).equals(p.getProperty("catalog"))||!ResetReviewTestData.sha(dir.resolve("writers-stopped.properties")).equals(p.getProperty("writers")))fail("SEMANTIC_CATALOG_OR_STOP_PROOF_DRIFT");
        for(String name:names(p)){
            Path file=dir.resolve(name+".backup");expected.add(file.getFileName().toString());Word97Maintenance.regular(file);if(!ResetReviewTestData.sha(file).equals(p.getProperty(name+".hash")))fail("SEMANTIC_BACKUP_HASH_DRIFT");
            var table=ResetReviewTestData.readArchive(file);if(!table.name.equals(name)||!Integer.toString(table.rows.size()).equals(p.getProperty(name+".rows"))||!ResetReviewTestData.fingerprint(table.rows).equals(p.getProperty(name+".fingerprint"))||!Word97Maintenance.columnsHash(table).equals(p.getProperty(name+".columns")))fail("SEMANTIC_BACKUP_CONTENT_DRIFT");
        }
        try(var files=Files.list(dir)){if(files.anyMatch(file->!expected.contains(file.getFileName().toString())))fail("SEMANTIC_UNKNOWN_BACKUP_ARTIFACT");}
        progress(dir);
    }
    static boolean predecessor(Properties snapshot,Properties proof,String ddl){
        return PREDECESSOR_SOURCE.equals(snapshot.getProperty("source"))&&PREDECESSOR_DDL.equals(ddl)&&ddl.equals(snapshot.getProperty("ddl"))
            &&"SEMANTIC_WRITERS_STOPPED_V1".equals(proof.getProperty("format"))&&PREDECESSOR_SOURCE.equals(proof.getProperty("source.sha256"))&&PREDECESSOR_HEAD.equals(proof.getProperty("head"));
    }
    static boolean readerArtifacts(Path dir,Properties p,String source,String ddl)throws Exception{
        Path file=dir.resolve(READER_FILE);
        if(source.equals(p.getProperty("source"))){if(Files.exists(file))fail("SEMANTIC_READER_DOWNGRADE_FORBIDDEN");return false;}
        Word97Maintenance.regular(file);var revision=Word97Maintenance.read(file);var proof=Word97Maintenance.read(dir.resolve("writers-stopped.properties"));
        Set<String> fields=Set.of("format","reason","backup.path","snapshot.sha256","writers.sha256","original.source","original.head","reader.source","reader.head","ddl","journal.prefix.bytes","journal.prefix.sha256","journal.sequence","journal.tip");
        if(!revision.stringPropertyNames().equals(fields)||!predecessor(p,proof,ddl)||!source.matches("[a-f0-9]{64}")
            ||!"SEMANTIC_CATALOG_READER_REVISION_V1".equals(revision.getProperty("format"))||!"DM8_CATALOG_DISPLAY_R1".equals(revision.getProperty("reason"))
            ||!dir.toRealPath().toString().equals(revision.getProperty("backup.path"))||!source.equals(revision.getProperty("reader.source"))
            ||!revision.getProperty("reader.head","").matches("[a-f0-9]{40}")||PREDECESSOR_HEAD.equals(revision.getProperty("reader.head"))
            ||!PREDECESSOR_SOURCE.equals(revision.getProperty("original.source"))||!PREDECESSOR_HEAD.equals(revision.getProperty("original.head"))
            ||!ddl.equals(revision.getProperty("ddl"))||!ResetReviewTestData.sha(dir.resolve("snapshot.properties")).equals(revision.getProperty("snapshot.sha256"))
            ||!ResetReviewTestData.sha(dir.resolve("writers-stopped.properties")).equals(revision.getProperty("writers.sha256")))fail("SEMANTIC_READER_REVISION_BINDING_DRIFT");
        long size=Long.parseLong(revision.getProperty("journal.prefix.bytes"));if(size<=0||size>16384)fail("SEMANTIC_READER_JOURNAL_PREFIX_BOUND");
        byte[] prefix;try(var in=Files.newInputStream(dir.resolve("journal.log"))){prefix=in.readNBytes((int)size);}
        String journal=new String(prefix,StandardCharsets.UTF_8);String[] lines=journal.split("\n",-1);
        if(prefix.length!=size||!hash(journal).equals(revision.getProperty("journal.prefix.sha256"))||lines.length!=3||!lines[2].isEmpty()
            ||!"1".equals(revision.getProperty("journal.sequence"))||lines[1].split("\\|",-1).length!=8||!lines[1].split("\\|",-1)[7].equals(revision.getProperty("journal.tip")))fail("SEMANTIC_READER_JOURNAL_PREFIX_DRIFT");
        progress(dir);return true;
    }
    static void issueReaderRevision(Path dir,Properties p,String identity,String source,String sha,String ddl,View actual,boolean approved)throws Exception{
        if(!approved||!sha.matches("[a-f0-9]{40}")||!source.matches("[a-f0-9]{64}")||sha.equals(PREDECESSOR_HEAD)||source.equals(PREDECESSOR_SOURCE)
            ||!predecessor(p,Word97Maintenance.read(dir.resolve("writers-stopped.properties")),ddl))fail("SEMANTIC_READER_EXPLICIT_FIXED_PREDECESSOR_APPROVAL_REQUIRED");
        artifacts(dir,p,identity,PREDECESSOR_SOURCE,ddl);unchanged(actual,p);var tip=progress(dir);
        if(actual.stage()!=1||tip.completed()!=0||tip.intent()!=1||tip.sequence()!=1||!"INTENT".equals(tip.state()))fail("SEMANTIC_READER_ONLY_FIRST_UNCONFIRMED_CREATE_ALLOWED");
        var revision=new Properties();revision.setProperty("format","SEMANTIC_CATALOG_READER_REVISION_V1");revision.setProperty("reason","DM8_CATALOG_DISPLAY_R1");
        revision.setProperty("backup.path",dir.toRealPath().toString());revision.setProperty("snapshot.sha256",ResetReviewTestData.sha(dir.resolve("snapshot.properties")));
        revision.setProperty("writers.sha256",ResetReviewTestData.sha(dir.resolve("writers-stopped.properties")));revision.setProperty("original.source",PREDECESSOR_SOURCE);revision.setProperty("original.head",PREDECESSOR_HEAD);
        revision.setProperty("reader.source",source);revision.setProperty("reader.head",sha);revision.setProperty("ddl",ddl);
        byte[] journal=Files.readAllBytes(dir.resolve("journal.log"));if(journal.length>16384)fail("SEMANTIC_READER_JOURNAL_PREFIX_BOUND");
        revision.setProperty("journal.prefix.bytes",Integer.toString(journal.length));revision.setProperty("journal.prefix.sha256",hash(new String(journal,StandardCharsets.UTF_8)));
        revision.setProperty("journal.sequence",Integer.toString(tip.sequence()));revision.setProperty("journal.tip",tip.hash());
        Word97Maintenance.storeNew(dir.resolve(READER_FILE),revision);readerArtifacts(dir,p,source,ddl);
    }
    static Word97Maintenance.Progress progress(Path dir)throws Exception{var p=Word97Maintenance.progress(dir,ResetReviewTestData.sha(dir.resolve("snapshot.properties")));if(p.completed()>3||p.intent()>3)fail("SEMANTIC_JOURNAL_PREFIX_OUTSIDE_PLAN");return p;}
    static void unchanged(View view,Properties p)throws Exception{
        validate(view);Set<String> old=new TreeSet<>(view.tables().keySet());old.removeAll(SemanticCheckpointCatalog.TABLES);if(!old.equals(new TreeSet<>(names(p)))||!projection(view).equals(p.getProperty("projection")))fail("SEMANTIC_UNRELATED_SCHEMA_DRIFT");
        for(var table:view.tables().values())if(SemanticCheckpointCatalog.TABLES.contains(table.name)){if(!table.rows.isEmpty())fail("SEMANTIC_NEW_EXECUTION_FACTS_EXIST_KEEP_TABLES");}
        else if(!ResetReviewTestData.fingerprint(table.rows).equals(p.getProperty(table.name+".fingerprint"))||!Word97Maintenance.columnsHash(table).equals(p.getProperty(table.name+".columns")))fail("SEMANTIC_OLD_BUSINESS_OR_PROTECTED_ROWS_CHANGED");
    }
    static int reconcile(Path dir,Properties p,View actual)throws Exception{
        unchanged(actual,p);var prior=progress(dir);
        // The signed-off reader observation proved CREATE 1 had committed. It
        // cannot subsequently be treated as an uncommitted intent and replayed.
        if(Files.exists(dir.resolve(READER_FILE))&&(actual.stage()<1||prior.completed()==0&&(prior.intent()!=1||actual.stage()!=1)))fail("SEMANTIC_READER_PROVEN_PREFIX_REGRESSION");
        if(prior.completed()==prior.intent()){if(actual.stage()!=prior.completed())fail("SEMANTIC_UNOWNED_CATALOG_PROGRESS");return actual.stage();}
        if(actual.stage()!=prior.completed()&&actual.stage()!=prior.intent())fail("SEMANTIC_UNKNOWN_DDL_OUTCOME");
        Word97Maintenance.append(dir,ResetReviewTestData.sha(dir.resolve("snapshot.properties")),prior,actual.stage(),actual.stage(),"RECONCILED");fail("SEMANTIC_RECONCILED_REOPEN_SAME_BACKUP_REQUIRED");return -1;
    }
    static void apply(Database db,Path dir,Properties p,String identity,String source,String ddlHash,List<String> plan)throws Exception{
        artifacts(dir,p,identity,source,ddlHash);int stage=reconcile(dir,p,db.inspect());String snapshot=ResetReviewTestData.sha(dir.resolve("snapshot.properties"));
        while(stage<3){artifacts(dir,p,identity,source,ddlHash);var before=db.inspect();unchanged(before,p);if(before.stage()!=stage)fail("SEMANTIC_CATALOG_CHANGED_BEFORE_DDL");
            var intent=Word97Maintenance.append(dir,snapshot,progress(dir),stage,stage+1,"INTENT");db.execute(plan.get(stage));var after=db.inspect();unchanged(after,p);if(after.stage()!=stage+1)fail("SEMANTIC_DDL_EFFECT_NOT_EXACT_NEXT_PREFIX");
            Word97Maintenance.append(dir,snapshot,intent,stage+1,stage+1,"CONFIRMED");stage++;System.out.println("SEMANTIC_DDL_PREFIX_VERIFIED="+stage+" OLD_ROWS_UNCHANGED=true");
        }
    }
    static void writers(Path root,Path dir,String sha,boolean pendingReader)throws Exception{
        var command=new ArrayList<>(List.of("powershell.exe","-NoProfile","-ExecutionPolicy","Bypass","-File",root.resolve("tools/local-test/maintain-semantic-checkpoints-local.ps1").toString(),"-Mode","Verify","-BackupDirectory",dir.toString(),"-ExpectedSourceSha",sha,"-WritersProofOnly"));
        if(pendingReader)command.add("-CatalogReaderRevisionApproved");
        var process=new ProcessBuilder(command).redirectErrorStream(true).start();
        if(!process.waitFor(25,TimeUnit.SECONDS)){process.destroyForcibly();fail("SEMANTIC_WRITERS_PROOF_TIMEOUT");}String output=new String(process.getInputStream().readNBytes(8192),StandardCharsets.UTF_8).trim();if(process.exitValue()!=0||!output.equals("SEMANTIC_WRITERS_STOPPED=PROVEN"))fail("SEMANTIC_WRITERS_OR_ACL_UNPROVEN");
    }
    static final class JdbcDatabase implements Database {
        final Connection c;final Path root,dir;final String sha;final ResetReviewTestData.DbIdentity identity;final SemanticCheckpointCatalog.Plan plan;final boolean pendingReader;
        JdbcDatabase(Connection c,Path root,Path dir,String sha,ResetReviewTestData.DbIdentity identity,SemanticCheckpointCatalog.Plan plan,boolean pendingReader){this.c=c;this.root=root;this.dir=dir;this.sha=sha;this.identity=identity;this.plan=plan;this.pendingReader=pendingReader;}
        void gates()throws Exception{ResetReviewTestData.identity(identity,System.getenv("AUDIT_DB_URL"),c.getMetaData().getUserName(),c.getSchema(),c.getMetaData().getDatabaseMajorVersion());if(!c.getMetaData().getDatabaseProductName().toUpperCase(Locale.ROOT).contains("DM"))fail("SEMANTIC_NOT_DM8");writers(root,dir,sha,pendingReader);ContractLocalMigration.sessions(c);}
        public View inspect()throws Exception{
            gates();var metadata=ResetReviewTestData.catalog(c,false);for(String table:metadata.tables().keySet())if(!SemanticCheckpointCatalog.TABLES.contains(table))ResetReviewTestData.requireClassified(List.of(table));
            ResetReviewTestData.lockTables(c,metadata,"SHARE");var catalog=ResetReviewTestData.catalog(c,true);if(!metadata.hash().equals(catalog.hash()))fail("SEMANTIC_CATALOG_CHANGED_DURING_SNAPSHOT");
            var facts=SemanticCheckpointCatalog.facts(c);int prefix=SemanticCheckpointCatalog.prefix(facts,plan,c.getSchema());
            for(var table:catalog.tables().values())try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+ResetReviewTestData.quote(table.name)+" WHERE 1=0")){
                var m=r.getMetaData();var cols=SemanticCheckpointCatalog.rows(facts,"COLUMN",table.name);if(cols.size()!=m.getColumnCount())fail("SEMANTIC_JDBC_COLUMN_CATALOG_INCOMPLETE");
                for(int i=1;i<=m.getColumnCount();i++){
                    Word97Maintenance.nullability(m.isNullable(i),cols.get(i-1).get(6),table.columns.get(i-1).nullable());
                    if(SemanticCheckpointCatalog.TABLES.contains(table.name)&&!SemanticCheckpointCatalog.typeMatches(plan.columns().get(table.name).get(i-1).type(),cols.get(i-1).get(2),m.getColumnType(i),m.getColumnTypeName(i)))fail("SEMANTIC_JDBC_COLUMN_TYPE_DRIFT");
                }
            }
            ResetReviewTestData.validateOrphans(catalog);var view=new View(catalog.tables(),facts,prefix);validate(view);gates();return view;
        }
        public void execute(String ddl)throws Exception{if(pendingReader||!plan.statements().contains(ddl))fail("SEMANTIC_DDL_OUTSIDE_SOLE_SQL");gates();c.rollback();gates();c.setReadOnly(false);c.setAutoCommit(true);
            try(var statement=c.createStatement()){statement.setQueryTimeout(30);statement.execute(ddl);}finally{c.setAutoCommit(false);c.setReadOnly(true);}}
    }
    public static void main(String[] args){try{run(args);}catch(Exception failure){System.out.println("SEMANTIC_MAINTENANCE=BLOCKED OUTCOME=UNKNOWN_UNTIL_SAME_JOURNAL_REOPEN gate="+(failure instanceof SQLException sql?"JDBC_"+sql.getErrorCode():ResetReviewTestData.safeGate(failure.getMessage())));System.exit(1);}}
    static void run(String[] args)throws Exception{
        if((args.length!=4&&args.length!=5)||args.length==5&&!"CATALOG_READER_REVISION_APPROVED".equals(args[4])||!Set.of("DryRun","Apply","Verify").contains(args[0])||!args[3].matches("[a-f0-9]{40}"))fail("SEMANTIC_ARGUMENTS_INVALID");Path root=Path.of(args[1]).toAbsolutePath().normalize(),dir=Path.of(args[2]).toAbsolutePath().normalize();
        if(!root.equals(ResetReviewTestData.APPROVED_ROOT)||!root.toRealPath().equals(root)||!Objects.equals(dir.getParent(),root.resolve(AREA))||!dir.getFileName().toString().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,80}"))fail("SEMANTIC_CANONICAL_BACKUP_REQUIRED");
        for(Path path=dir;path!=null&&path.startsWith(root);path=path.getParent())if(!path.toRealPath().equals(path)||Files.isSymbolicLink(path))fail("SEMANTIC_BACKUP_REPARSE_POINT");
        var plan=SemanticCheckpointCatalog.plan(root);var approved=ResetReviewTestData.approvedIdentity(root);String identity=hash(approved.url+"\n"+approved.user+"\n"+approved.schema),source=source(root),ddl=ResetReviewTestData.sha(root.resolve(SemanticCheckpointCatalog.DDL));
        var proof=Word97Maintenance.read(dir.resolve("writers-stopped.properties"));boolean changed=!source.equals(proof.getProperty("source.sha256"))||!args[3].equals(proof.getProperty("head"));
        boolean pendingReader=changed&&!Files.exists(dir.resolve(READER_FILE))&&args.length==5&&args[0].equals("Verify");
        if(!"SEMANTIC_WRITERS_STOPPED_V1".equals(proof.getProperty("format")))fail("SEMANTIC_STOP_PROOF_SOURCE_MISMATCH");
        if(changed){var p=Word97Maintenance.read(dir.resolve("snapshot.properties"));if(!predecessor(p,proof,ddl))fail("SEMANTIC_STOP_PROOF_SOURCE_MISMATCH");
            if(!pendingReader){readerArtifacts(dir,p,source,ddl);if(!args[3].equals(Word97Maintenance.read(dir.resolve(READER_FILE)).getProperty("reader.head")))fail("SEMANTIC_READER_HEAD_DRIFT");}}
        writers(root,dir,args[3],pendingReader);ResetReviewTestData.identity(approved,System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),approved.schema,8);
        try(var channel=FileChannel.open(dir.resolve("maintenance.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()){
            if(lock==null)fail("SEMANTIC_MAINTENANCE_ALREADY_ACTIVE");DriverManager.setLoginTimeout(10);
            try(var c=DriverManager.getConnection(approved.url,approved.user,System.getenv("AUDIT_DB_PASSWORD"))){c.setAutoCommit(false);c.setReadOnly(true);Database db=new JdbcDatabase(c,root,dir,args[3],approved,plan,pendingReader);
                if(pendingReader){try{var p=Word97Maintenance.read(dir.resolve("snapshot.properties"));issueReaderRevision(dir,p,identity,source,args[3],ddl,db.inspect(),true);
                    fail("SEMANTIC_READER_REVISION_CREATED_REOPEN_RECONCILE_REQUIRED");}finally{c.rollback();}}
                try{if(args[0].equals("DryRun")){var before=db.inspect();var p=snapshot(dir,before,identity,source,ddl);artifacts(dir,p,identity,source,ddl);unchanged(db.inspect(),p);System.out.println("SEMANTIC_DRYRUN=PASS FULL_TYPED_ROUNDTRIP=true DDL_EXECUTED=false TABLES="+before.tables().size());}
                    else{var p=Word97Maintenance.read(dir.resolve("snapshot.properties"));artifacts(dir,p,identity,source,ddl);if(args[0].equals("Apply"))apply(db,dir,p,identity,source,ddl,plan.statements());if(reconcile(dir,p,db.inspect())!=3)fail("SEMANTIC_MIGRATION_INCOMPLETE");SemanticCheckpointCatalog.verify(c,root);System.out.println("SEMANTIC_"+args[0].toUpperCase(Locale.ROOT)+"=PASS PREFIX=3 OLD_ROWS_UNCHANGED=true");}}
                finally{c.rollback();}
            }
        }
    }
}
