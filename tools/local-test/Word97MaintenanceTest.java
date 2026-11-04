import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Offline executable tests. Synthetic runtime fixtures only; never opens a database. */
public class Word97MaintenanceTest {
  interface Check {void run()throws Exception;}
  static int tests,failures;
  static Path runtime;
  static final String IDENTITY="synthetic-approved-identity",SOURCE="synthetic-source";
  static void test(String name,Check check){tests++;PrintStream console=System.out;Throwable failed=null;try(PrintStream captured=new PrintStream(new ByteArrayOutputStream())){System.setOut(captured);try{check.run();}catch(Throwable failure){failed=failure;}finally{System.setOut(console);}}if(failed==null)console.println("PASS "+name);else{failures++;console.println("FAIL "+name+" "+failed.getClass().getSimpleName()+" "+ResetReviewTestData.safeGate(failed.getMessage()));}}
  static void check(boolean condition){if(!condition)throw new AssertionError("Expected safety behavior absent");}
  static void reject(Check check)throws Exception{try{check.run();}catch(IllegalStateException expected){return;}throw new AssertionError("Unsafe operation accepted");}
  static ResetReviewTestData.Column col(String name,int type,String sql,boolean nullable){return new ResetReviewTestData.Column(name,type,sql,nullable);}
  static Word97Maintenance.View view(int stage)throws Exception{
    Map<String,ResetReviewTestData.Table> tables=new TreeMap<>();List<Word97Maintenance.Fact> facts=new ArrayList<>();
    for(String name:List.of("PROJECT_FILE","PROJECT_FILE_PARSE_RESULT","SYS_USER","SYS_ROLE","SYS_USER_ROLE","AUDIT_OPERATION_LOG","AUTH_LOGIN_AUDIT")){
      List<ResetReviewTestData.Column> cols=new ArrayList<>(List.of(col("ID",Types.VARCHAR,"VARCHAR",false)));List<Object> row=new ArrayList<>(List.of("synthetic-"+name));
      if(name.startsWith("PROJECT_FILE")){cols.add(col("PARSE_METHOD",Types.VARCHAR,"VARCHAR",name.equals("PROJECT_FILE")));row.add("PDF_TEXT");}
      else {cols.add(col("VALUE",Types.VARCHAR,"VARCHAR",false));row.add("synthetic-protected-value");}
      tables.put(name,new ResetReviewTestData.Table(name,cols,List.of("ID"),List.of(row)));
      facts.add(new Word97Maintenance.Fact("TABLE",List.of(name)));for(var c:cols)facts.add(new Word97Maintenance.Fact("COLUMN",List.of(name,c.name(),c.sqlType(),c.nullable()?"Y":"N")));
    }
    var typed=List.of(col("ID",Types.VARCHAR,"VARCHAR",false),col("BODY",Types.CLOB,"CLOB",true),col("BINARY",Types.BLOB,"BLOB",false),col("MONEY",Types.DECIMAL,"DECIMAL",false),col("STAMP",Types.TIMESTAMP,"TIMESTAMP",false));
    tables.put("CONTRACT",new ResetReviewTestData.Table("CONTRACT",typed,List.of("ID"),List.of(Arrays.asList("synthetic-contract","synthetic text \uD840\uDC00",new byte[]{0,1,-1},new BigDecimal("100.20"),Timestamp.valueOf("2026-10-15 12:00:00.123456789")))));
    facts.add(new Word97Maintenance.Fact("TRIGGER",List.of("SYS_USER","T_SYNTHETIC","ENABLED","INSERT","trigger body v1")));
    facts.add(new Word97Maintenance.Fact("INDEX",List.of("PROJECT_FILE","IDX_SYNTHETIC","VALID")));
    facts.add(new Word97Maintenance.Fact("CONSTRAINT",List.of("SYS_USER","CK_SYNTHETIC","C","ENABLED","VALUE != 'CK_PROJECT_FILE_PARSE_METHOD'")));
    return new Word97Maintenance.View(tables,facts,Word97Maintenance.stateAt(stage));
  }
  static Word97Maintenance.View withFact(Word97Maintenance.View view,String kind,List<String> values){List<Word97Maintenance.Fact> facts=new ArrayList<>(view.facts());facts.add(new Word97Maintenance.Fact(kind,values));return new Word97Maintenance.View(view.tables(),facts,view.checks());}
  static Path directory()throws Exception{Path dir=Files.createTempDirectory(runtime,"case-");Files.writeString(dir.resolve("writers-stopped.properties"),"synthetic=true\n",StandardCharsets.US_ASCII);return dir;}
  static Properties snapshot(Path dir,Word97Maintenance.View view)throws Exception{return Word97Maintenance.snapshot(dir,view,IDENTITY,SOURCE,ResetReviewTestData.sha(dir.resolve("writers-stopped.properties")));}
  static final class FakeDatabase implements Word97Maintenance.Database {
    int stage,executed,reads;int failBefore=-1,failAfter=-1,driftAt=-1;boolean sessionsBlocked;
    Word97Maintenance.View replacement;
    public Word97Maintenance.View inspect()throws Exception{reads++;if(sessionsBlocked)throw new IllegalStateException("OTHER_SESSION_ACTIVE");return replacement==null?view(stage):replacement;}
    public void execute(String ddl)throws Exception{
      check(!sessionsBlocked);if(failBefore==stage){failBefore=-1;throw new SQLException("synthetic timeout before effect");}
      String table=stage<4?"PROJECT_FILE":"PROJECT_FILE_PARSE_RESULT";String name=stage<4?"CK_PROJECT_FILE_PARSE_METHOD":"CK_PROJECT_FILE_PARSE_RESULT_METHOD";
      switch(stage%4){
        case 0 -> check(ddl.startsWith("ALTER TABLE "+table+" ADD CONSTRAINT "+name+"_DOC_GUARD CHECK (")&&ddl.endsWith("'DOC_TEXT'))"));
        case 1 -> check(ddl.equals("ALTER TABLE "+table+" DROP CONSTRAINT "+name));
        case 2 -> check(ddl.startsWith("ALTER TABLE "+table+" ADD CONSTRAINT "+name+" CHECK (")&&ddl.endsWith("'DOC_TEXT'))"));
        case 3 -> check(ddl.equals("ALTER TABLE "+table+" DROP CONSTRAINT "+name+"_DOC_GUARD"));
        default -> throw new AssertionError();
      }
      stage++;executed++;if(driftAt==stage)replacement=withFact(view(stage),"INDEX",List.of("SYS_USER","IDX_DRIFT","VALID"));
      if(failAfter==stage){failAfter=-1;throw new SQLException("synthetic lost response after implicit commit");}
    }
  }
  static void apply(FakeDatabase db,Path dir,Properties p)throws Exception{Word97Maintenance.apply(db,dir,p,IDENTITY,SOURCE);}
  public static void main(String[] args)throws Exception{
    test("DM8 trigger projection retains every real catalog field without Oracle-only names",()->{
      String sql=Word97Maintenance.triggerCatalogSql();
      Set<String> actual=Set.of(sql.substring("SELECT ".length(),sql.indexOf(" FROM ")).split(","));
      check(actual.equals(Set.of("TABLE_NAME","TRIGGER_NAME","STATUS","TRIGGERING_EVENT","TRIGGERING_TYPE","TABLE_OWNER","BASE_OBJECT_TYPE","COLUMN_NAME","DESCRIPTION","TRIGGER_BODY")));
      check(sql.endsWith("FROM USER_TRIGGERS ORDER BY TABLE_NAME,TRIGGER_NAME"));
    });
    Path repo=args.length==0?Path.of("").toAbsolutePath():Path.of(args[0]).toAbsolutePath();runtime=repo.resolve(".runtime/word97-offline-tests");Files.createDirectories(runtime);
    test("complete current backup includes protected and populated business values",()->{
      Path dir=directory();var original=view(0);Properties p=snapshot(dir,original);Word97Maintenance.artifacts(dir,p,IDENTITY,SOURCE);check(Word97Maintenance.tableNames(p).size()==8);
      for(String name:original.tables().keySet()){var restored=ResetReviewTestData.readArchive(dir.resolve(name+".backup"));check(ResetReviewTestData.fingerprint(restored.rows).equals(ResetReviewTestData.fingerprint(original.tables().get(name).rows)));check(restored.rows.size()==1);}
      var contract=ResetReviewTestData.readArchive(dir.resolve("CONTRACT.backup"));check(contract.rows.get(0).get(3).equals(new BigDecimal("100.20")));check(((Timestamp)contract.rows.get(0).get(4)).getNanos()==123456789);
    });
    test("eight additive steps preserve all rows and verify final catalog",()->{Path dir=directory();Properties p=snapshot(dir,view(0));FakeDatabase db=new FakeDatabase();apply(db,dir,p);check(db.executed==8&&db.stage==8&&db.reads>=17);Word97Maintenance.verify(db,dir,p,IDENTITY,SOURCE);apply(db,dir,p);check(db.executed==8);});
    test("fresh snapshot refuses partial or already expanded schema",()->{reject(()->snapshot(directory(),view(1)));reject(()->snapshot(directory(),view(8)));});
    test("combined migration permits only exact public SQL prefixes",()->{check(Word97Maintenance.prefix(List.of("MISSING","NEW","OLD","MISSING"))==2);reject(()->Word97Maintenance.prefix(List.of("OLD","MISSING","OLD","NEW")));reject(()->Word97Maintenance.prefix(List.of("MISSING","MISSING","OLD","MISSING")));reject(()->Word97Maintenance.prefix(List.of("OLD","DISABLED","OLD","MISSING")));});
    test("uncertain implicit commit requires same journal reconciliation without replay",()->{
      for(int interrupted=1;interrupted<=8;interrupted++){Path dir=directory();Properties p=snapshot(dir,view(0));FakeDatabase db=new FakeDatabase();db.failAfter=interrupted;try{apply(db,dir,p);throw new AssertionError();}catch(SQLException expected){}
        check(db.executed==interrupted);reject(()->apply(db,dir,p));check(db.executed==interrupted);apply(db,dir,p);check(db.executed==8);Word97Maintenance.verify(db,dir,p,IDENTITY,SOURCE);
      }
    });
    test("uncertain DDL with no effect also needs reconciliation reopen",()->{Path dir=directory();Properties p=snapshot(dir,view(0));FakeDatabase db=new FakeDatabase();db.failBefore=1;try{apply(db,dir,p);throw new AssertionError();}catch(SQLException expected){}check(db.stage==1);reject(()->apply(db,dir,p));check(db.executed==1);apply(db,dir,p);check(db.executed==8);});
    test("foreign progress beyond single fsynced intent fails closed",()->{Path dir=directory();Properties p=snapshot(dir,view(0));FakeDatabase db=new FakeDatabase();db.failAfter=1;try{apply(db,dir,p);}catch(SQLException expected){}db.stage=3;reject(()->apply(db,dir,p));check(db.executed==1);});
    test("unowned catalog progress cannot borrow another backup",()->{Path dir=directory();Properties p=snapshot(dir,view(0));FakeDatabase db=new FakeDatabase();db.stage=1;reject(()->apply(db,dir,p));check(db.executed==0);});
    test("account role audit and business changes each block before DDL",()->{
      for(String name:List.of("SYS_USER","SYS_ROLE","SYS_USER_ROLE","AUDIT_OPERATION_LOG","AUTH_LOGIN_AUDIT","CONTRACT")){Path dir=directory();Properties p=snapshot(dir,view(0));var current=view(0);var tables=new TreeMap<>(current.tables());var t=tables.get(name);List<Object> row=new ArrayList<>(t.rows.get(0));row.set(0,"synthetic-modified");tables.put(name,new ResetReviewTestData.Table(name,t.columns,t.pk,List.of(row)));FakeDatabase db=new FakeDatabase();db.replacement=new Word97Maintenance.View(tables,current.facts(),current.checks());reject(()->apply(db,dir,p));check(db.executed==0);}
    });
    test("after DDL catalog drift leaves unresolved durable intent",()->{Path dir=directory();Properties p=snapshot(dir,view(0));FakeDatabase db=new FakeDatabase();db.driftAt=1;reject(()->apply(db,dir,p));check(db.executed==1);var j=Word97Maintenance.progress(dir,ResetReviewTestData.sha(dir.resolve("snapshot.properties")));check(j.completed()==0&&j.intent()==1);});
    test("unrelated checks indexes foreign keys triggers columns remain in projection",()->{
      var original=view(0);String before=Word97Maintenance.projection(original);for(String kind:List.of("INDEX","INDEX_COLUMN","TRIGGER","COLUMN","CONSTRAINT"))check(!before.equals(Word97Maintenance.projection(withFact(original,kind,List.of("PROJECT_FILE","UNRELATED","CK_PROJECT_FILE_PARSE_METHOD")))));
      check(before.equals(Word97Maintenance.projection(withFact(original,"CONSTRAINT",List.of("PROJECT_FILE","CK_PROJECT_FILE_PARSE_METHOD","C","ENABLED","old")))));
      check(before.equals(Word97Maintenance.projection(withFact(original,"CONSTRAINT_COLUMN",List.of("PROJECT_FILE_PARSE_RESULT","CK_PROJECT_FILE_PARSE_RESULT_METHOD_DOC_GUARD","PARSE_METHOD","1")))));
      check(!before.equals(Word97Maintenance.projection(withFact(original,"INDEX",List.of("PROJECT_FILE","CK_PROJECT_FILE_PARSE_METHOD","VALID")))));
    });
    test("backup catalog and stop-proof byte drift block without DDL",()->{for(String file:List.of("SYS_USER.backup","CONTRACT.backup","catalog.typed","writers-stopped.properties")){Path dir=directory();Properties p=snapshot(dir,view(0));Files.write(dir.resolve(file),new byte[]{1},StandardOpenOption.APPEND);FakeDatabase db=new FakeDatabase();reject(()->apply(db,dir,p));check(db.executed==0);}});
    test("manifest cannot be rebound after journal was created",()->{Path dir=directory();Properties p=snapshot(dir,view(0));p.setProperty("created","synthetic-changed");try(OutputStream out=Files.newOutputStream(dir.resolve("snapshot.properties"))){p.store(out,"synthetic");}reject(()->Word97Maintenance.artifacts(dir,p,IDENTITY,SOURCE));});
    test("torn append-only journal is never treated as missing",()->{Path dir=directory();Properties p=snapshot(dir,view(0));Files.writeString(dir.resolve("journal.log"),"0|torn",StandardOpenOption.APPEND);FakeDatabase db=new FakeDatabase();reject(()->apply(db,dir,p));check(db.executed==0);});
    test("unsupported empty-table types and unknown tables fail before snapshot",()->{
      var base=view(0);var tables=new TreeMap<>(base.tables());tables.put("CONTRACT",new ResetReviewTestData.Table("CONTRACT",List.of(col("ID",Types.VARCHAR,"VARCHAR",false),col("OPAQUE",Types.OTHER,"OTHER",true)),List.of("ID"),List.of()));reject(()->snapshot(directory(),new Word97Maintenance.View(tables,base.facts(),base.checks())));
      tables.remove("CONTRACT");tables.put("UNKNOWN_EMPTY",new ResetReviewTestData.Table("UNKNOWN_EMPTY",List.of(col("ID",Types.VARCHAR,"VARCHAR",false)),List.of("ID"),List.of()));reject(()->snapshot(directory(),new Word97Maintenance.View(tables,base.facts(),base.checks())));
    });
    test("unknown nullability and nullability swaps fail closed",()->{Word97Maintenance.nullability(ResultSetMetaData.columnNullable,"Y",true);Word97Maintenance.nullability(ResultSetMetaData.columnNoNulls,"N",false);reject(()->Word97Maintenance.nullability(ResultSetMetaData.columnNullableUnknown,"Y",true));reject(()->Word97Maintenance.nullability(ResultSetMetaData.columnNoNulls,"?",false));reject(()->Word97Maintenance.nullability(ResultSetMetaData.columnNoNulls,"Y",true));});
    test("another database session prevents DDL and journal intent",()->{Path dir=directory();Properties p=snapshot(dir,view(0));FakeDatabase db=new FakeDatabase();db.sessionsBlocked=true;reject(()->apply(db,dir,p));check(db.executed==0);check(Word97Maintenance.progress(dir,ResetReviewTestData.sha(dir.resolve("snapshot.properties"))).intent()==0);});
    test("identity and source drift cannot reach database execution seam",()->{Path dir=directory();Properties p=snapshot(dir,view(0));FakeDatabase db=new FakeDatabase();reject(()->Word97Maintenance.apply(db,dir,p,"other",SOURCE));reject(()->Word97Maintenance.apply(db,dir,p,IDENTITY,"other"));check(db.executed==0&&db.reads==0);});
    test("verify cannot label incomplete prefix complete",()->{Path dir=directory();Properties p=snapshot(dir,view(0));FakeDatabase db=new FakeDatabase();reject(()->Word97Maintenance.verify(db,dir,p,IDENTITY,SOURCE));check(db.executed==0);});
    if(args.length>0){
      test("maintenance classpath helper is allowed while actual same-tree jar is blocked",()->reviewGate(repo,"helper"));
      test("relative business launch and sourceless uvicorn remain unknown",()->reviewGate(repo,"unknown"));
      test("approved source requires explicit SHA and a clean working tree",()->reviewGate(repo,"source"));
      test("PowerShell writer ACL and private AST gates execute offline",()->powershell(repo));
    }
    System.out.println("WORD97_MAINTENANCE_TESTS="+tests+" FAILURES="+failures);if(failures!=0)System.exit(1);
  }
  static void reviewGate(Path repo,String scenario)throws Exception{
    String preamble="$ErrorActionPreference='Stop'; Set-StrictMode -Version Latest; $repo='"+repo.toString().replace("'","''")+"'; "+
      "$tokens=$null; $errors=$null; $tree=[Management.Automation.Language.Parser]::ParseFile((Join-Path $repo 'tools/local-test/maintain-word97-local.ps1'),[ref]$tokens,[ref]$errors); if($errors.Count){throw 'PARSE'}; "+
      ". (Join-Path $repo 'tools/local-test/contract-test-guards.ps1'); foreach($name in @('Assert-Word97StoppedState','Assert-Word97SourceIdentity')){$fn=$tree.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name},$true); Invoke-Expression $fn[0].Extent.Text}; ";
    String body=switch(scenario){
      case "helper" -> "$p=[pscustomobject]@{Name='java.exe';CommandLine=('java -cp '+$repo+'\\.runtime\\classes;C:\\m2\\DmJdbcDriver18.jar Word97Maintenance Verify '+$repo);ProcessId=123;CreationDate=[datetime]::UtcNow}; Assert-Word97StoppedState $repo @($p) @() @(); "+
        "$p.CommandLine='java -jar '+$repo+'\\backend\\app.jar'; $refused=$false; try{Assert-Word97StoppedState $repo @($p) @() @()}catch{$refused=$true}; if(-not $refused){throw 'SAME_TREE_JAR_NOT_BLOCKED'}";
      case "unknown" -> "$blocked=0; foreach($command in @('java -jar backend\\app.jar','python -m uvicorn audit_ai.main:app','python -m contract_review_worker')){$name=if($command.StartsWith('java')){'java.exe'}else{'python.exe'}; $p=[pscustomobject]@{Name=$name;CommandLine=$command;ProcessId=123;CreationDate=[datetime]::UtcNow}; try{Assert-Word97StoppedState $repo @($p) @() @()}catch{if($_.Exception.Message -ne 'WORD97_APPLICATION_PROCESS_SCOPE_UNKNOWN'){throw}; $blocked++}}; if($blocked -ne 3){throw 'UNKNOWN_WRITER_NOT_BLOCKED'}; "+
        "$p=[pscustomobject]@{Name='python.exe';CommandLine='python -m uvicorn audit_ai.main:app --app-dir C:\\other-worktree\\services';ProcessId=124;CreationDate=[datetime]::UtcNow}; Assert-Word97StoppedState $repo @($p) @() @()";
      case "source" -> "$approved='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'; Assert-Word97SourceIdentity $repo 'integration/pre-main-20261010' $approved $approved @(); $blocked=0; foreach($dirty in @(' M tools/local-test/Word97Maintenance.java','?? tools/local-test/untracked.java')){try{Assert-Word97SourceIdentity $repo 'integration/pre-main-20261010' $approved $approved @($dirty)}catch{if($_.Exception.Message -ne 'SOURCE_NOT_CLEAN'){throw};$blocked++}}; if($blocked -ne 2){throw 'DIRTY_SOURCE_ACCEPTED'}; "+
        "$binding=[scriptblock]::Create($tree.ParamBlock.Extent.Text+'; ''BOUND'''); $refused=$false; try{& $binding -Mode Verify | Out-Null}catch{if($_.FullyQualifiedErrorId -notlike '*MissingMandatoryParameter*'){throw};$refused=$true}; if(-not $refused){throw 'EXPECTED_SHA_NOT_MANDATORY'}";
      default -> throw new IllegalArgumentException();
    };
    Process child=new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-Command",preamble+body+"; 'WORD97_REVIEW_GATE=PASS'").redirectErrorStream(true).start();if(!child.waitFor(20,java.util.concurrent.TimeUnit.SECONDS)){child.destroyForcibly();throw new AssertionError("Review gate timeout");}String output=new String(child.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();if(child.exitValue()!=0||!output.equals("WORD97_REVIEW_GATE=PASS")){Files.writeString(runtime.resolve("review-"+scenario+"-synthetic-failure.txt"),output);throw new AssertionError("Review regression missing");}
  }
  static void powershell(Path repo)throws Exception{
    String script="$ErrorActionPreference='Stop'; Set-StrictMode -Version Latest; $repo='"+repo.toString().replace("'","''")+"'; "+
      "$tokens=$null; $errors=$null; $tree=[Management.Automation.Language.Parser]::ParseFile((Join-Path $repo 'tools/local-test/maintain-word97-local.ps1'),[ref]$tokens,[ref]$errors); if($errors.Count){throw 'PARSE'}; "+
      "$fn=$tree.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Assert-Word97StoppedState'},$true); Invoke-Expression $fn[0].Extent.Text; "+
      "Assert-Word97StoppedState $repo @() @() @(); $blocked=0; "+
      "try{Assert-Word97StoppedState $repo @() @([pscustomobject]@{LocalPort=8888}) @()}catch{$blocked++}; "+
      "$p=[pscustomobject]@{Name='java.exe';CommandLine=('java -jar '+$repo+'\\backend\\audit.jar');ProcessId=123;CreationDate=[datetime]::UtcNow}; "+
      "try{Assert-Word97StoppedState $repo @($p) @() @()}catch{$blocked++}; "+
      "$p.CommandLine='java -jar C:\\other-worktree\\audit.jar'; Assert-Word97StoppedState $repo @($p) @() @(); "+
      "try{Assert-Word97StoppedState $repo @($p) @() @([pscustomobject]@{codeRoot=$repo;pid=123;creationUtc=$p.CreationDate.ToUniversalTime().ToString('o')})}catch{$blocked++}; "+
      "if($blocked -ne 3){throw 'WRITER_GATE'}; "+
      ". (Join-Path $repo 'tools/local-test/contract-test-guards.ps1'); "+
      "$definition=$tree.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Assert-Word97SourceIdentity'},$true); Invoke-Expression $definition[0].Extent.Text; "+
      "$approved='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'; Assert-Word97SourceIdentity $repo 'integration/pre-main-20261010' $approved $approved; $sourceBlocked=0; "+
      "try{Assert-Word97SourceIdentity $repo 'integration/pre-main-20261010' 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' $approved}catch{$sourceBlocked++}; "+
      "try{Assert-Word97SourceIdentity $repo 'integration/pre-main-20261010' $approved 'invalid'}catch{$sourceBlocked++}; if($sourceBlocked -ne 2){throw 'APPROVED_SOURCE_GATE'}; "+
      "$config=Join-Path $repo '.runtime/word97-offline-tests/synthetic-env.ps1'; "+
      "[IO.File]::WriteAllText($config,('$env:AUDIT_DB_URL=''synthetic'''+[char]10+'$env:AUDIT_DB_USERNAME=''synthetic'''+[char]10+'$env:AUDIT_DB_PASSWORD=''synthetic'''+[char]10+'throw ''MUST_NOT_EXECUTE'''+[char]10)); "+
      "$keys=@('AUDIT_DB_URL','AUDIT_DB_USERNAME','AUDIT_DB_PASSWORD'); $before=[Environment]::GetEnvironmentVariable('AUDIT_DB_PASSWORD','Process'); $v=Read-ContractLiteralEnvironment $config $keys $keys; "+
      "if($v.Count -ne 3 -or $v['AUDIT_DB_PASSWORD'] -ne 'synthetic' -or [Environment]::GetEnvironmentVariable('AUDIT_DB_PASSWORD','Process') -ne $before){throw 'AST_GATE'}; "+
      "[IO.File]::WriteAllText($config,('$env:AUDIT_DB_URL=(Get-Date)'+[char]10)); $refused=$false; try{Read-ContractLiteralEnvironment $config $keys $keys | Out-Null}catch{$refused=$true}; if(-not $refused){throw 'AST_NONLITERAL'}; "+
      "$word97Root=$repo; foreach($name in @('Assert-Word97NoReparse','Set-Word97RestrictedDirectory','Assert-Word97RestrictedDirectory')){$definition=$tree.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name},$true); Invoke-Expression $definition[0].Extent.Text}; "+
      "$aclDir=Join-Path $repo ('.runtime/word97-offline-tests/acl-'+[guid]::NewGuid().ToString('N')); [IO.Directory]::CreateDirectory($aclDir)|Out-Null; Set-Word97RestrictedDirectory $aclDir; Assert-Word97RestrictedDirectory $aclDir; "+
      "[IO.File]::WriteAllText((Join-Path $aclDir 'synthetic.txt'),'synthetic'); Assert-Word97RestrictedDirectory $aclDir; "+
      "$weakDir=Join-Path $repo ('.runtime/word97-offline-tests/weak-'+[guid]::NewGuid().ToString('N')); [IO.Directory]::CreateDirectory($weakDir)|Out-Null; "+
      "$acl=New-Object Security.AccessControl.DirectorySecurity; $owner=[Security.Principal.WindowsIdentity]::GetCurrent().User; $acl.SetOwner($owner); $acl.SetAccessRuleProtection($true,$false); "+
      "foreach($sid in @($owner,(New-Object Security.Principal.SecurityIdentifier('S-1-5-18')),(New-Object Security.Principal.SecurityIdentifier('S-1-1-0')))){$acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow')))}; Set-Acl -LiteralPath $weakDir -AclObject $acl; "+
      "$refused=$false; try{Assert-Word97RestrictedDirectory $weakDir}catch{$refused=$true}; if(-not $refused){throw 'ACL_GATE'}; 'WORD97_POWERSHELL_OFFLINE=PASS'";
    Process process=new ProcessBuilder("powershell.exe","-NoProfile","-ExecutionPolicy","Bypass","-Command",script).redirectErrorStream(true).start();if(!process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();throw new AssertionError("PowerShell timeout");}String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();if(process.exitValue()!=0||!output.equals("WORD97_POWERSHELL_OFFLINE=PASS")){Files.writeString(runtime.resolve("powershell-synthetic-failure.txt"),output);throw new AssertionError("PowerShell behavior gate failed");}
  }
}
