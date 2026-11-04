import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Executable adversarial tests, no replacement database dialect. */
public class ResetReviewTestDataTest {
  static int failures, tests;
  interface Check { void run() throws Exception; }
  static void test(String name, Check check) { tests++; try {check.run();System.out.println("PASS "+name);} catch(Throwable e) {failures++;System.out.println("FAIL "+name+" "+e.getClass().getSimpleName());} }
  static void rejected(Check c) throws Exception {try {c.run();}catch(IllegalStateException|IOException e){return;}throw new AssertionError("unsafe action accepted");}
  public static void main(String[] args) throws Exception {
    test("unknown table cannot become deletion target",()-> {assert !ResetReviewTestData.isTarget("AUDIT_NEW_UNKNOWN");});
    test("unknown empty catalog table without FK blocks reset plan",()-> {
      var unknown=new ResetReviewTestData.Table("UNKNOWN_EMPTY_NO_FK",List.of(new ResetReviewTestData.Column("ID",Types.VARCHAR,"VARCHAR",false)),List.of("ID"),List.of());
      var catalog=new ResetReviewTestData.Catalog(Map.of(unknown.name,unknown),List.of(),List.of(),"unused");
      boolean blocked=false;try{ResetReviewTestData.requireClassified(catalog.tables().keySet());}catch(IllegalStateException e){blocked=e.getMessage().equals("UNCLASSIFIED_TABLE");}assert blocked;
      assert ResetReviewTestData.classify(unknown.name)==ResetReviewTestData.Classification.UNKNOWN;
      boolean planBlocked=false;try{ResetReviewTestData.plan(null,catalog);}catch(IllegalStateException e){planBlocked=e.getMessage().equals("UNCLASSIFIED_TABLE");}assert planBlocked;
      var unsafePlan=new ResetReviewTestData.Plan(catalog,List.of(),Map.of(),Map.of(),"unused");boolean applyBlocked=false;try{ResetReviewTestData.apply(null,null,null,unsafePlan,new Properties());}catch(IllegalStateException e){applyBlocked=e.getMessage().equals("UNCLASSIFIED_TABLE");}assert applyBlocked;
    });
    test("actual known auth rules and conditional records classify explicitly",()-> {assert ResetReviewTestData.classify("SYS_USER")==ResetReviewTestData.Classification.APPROVED_PROTECTED;assert ResetReviewTestData.classify("PROCUREMENT_TENDER_RULE")==ResetReviewTestData.Classification.APPROVED_PROTECTED;assert ResetReviewTestData.classify("PROJECT")==ResetReviewTestData.Classification.DELETE;assert ResetReviewTestData.classify("IDEMPOTENCY_RECORD")==ResetReviewTestData.Classification.CONDITIONAL;ResetReviewTestData.requireClassified(Set.of("SYS_USER","IDEMPOTENCY_RECORD","PROJECT_FILE_PROCESSING_JOB","PROJECT_FILE_PROCESSING_ATTEMPT"));});
    test("authentication audit supplier and budget configuration stay protected",()-> {for(String t:List.of("SYS_USER","SYS_ROLE","SYS_USER_ROLE","AUTH_LOGIN_AUDIT","AUDIT_OPERATION_LOG","BLACKLIST_SUPPLIER","BLACKLIST_SUPPLIER_OPERATION_LOG","BUDGET_SUBJECT_MAPPING","BUDGET_SPLIT_RISK_RULE","CONTRACT_RULE_CONFIG")) assert !ResetReviewTestData.isTarget(t):t;});
    test("project instance is authorized target",()-> {assert ResetReviewTestData.isTarget("PROJECT");});
    test("new file job attempt and async scope bind to deleted Project",()-> {assert ResetReviewTestData.isTarget("PROJECT_FILE_PROCESSING_JOB");assert ResetReviewTestData.isTarget("PROJECT_FILE_PROCESSING_ATTEMPT");assert ResetReviewTestData.idempotencyTarget("PROJECT_FILE_UPLOAD_ASYNC","PROJECT","old",null,null,Map.of("PROJECT",Set.of("old")));assert !ResetReviewTestData.idempotencyTarget("PROJECT_FILE_UPLOAD_ASYNC","PROJECT","new",null,null,Map.of("PROJECT",Set.of("old")));});
    test("FK delete puts descendant before parent",()-> {assert ResetReviewTestData.order(Set.of("P","C","G"),List.of(new ResetReviewTestData.Edge("G","C"),new ResetReviewTestData.Edge("C","P"))).equals(List.of("G","C","P"));});
    test("unproven FK cycle cannot produce a plan",()-> rejected(()->ResetReviewTestData.order(Set.of("P","C"),List.of(new ResetReviewTestData.Edge("P","C"),new ResetReviewTestData.Edge("C","P")))));
    test("protected dependent blocks parent deletion",()-> rejected(()->ResetReviewTestData.checkDependent(false,true,1)));
    test("empty protected dependent does not authorize its deletion",()-> {ResetReviewTestData.checkDependent(false,true,0); assert !ResetReviewTestData.isTarget("NEW_PROTECTED");});
    test("same UUID with wrong semantic type never matches",()-> {assert !ResetReviewTestData.idempotencyTarget("PROJECT_CREATE","SYS_USER","same","SYS_USER","same",Map.of("PROJECT",Set.of("same")));});
    test("unknown operation namespace remains protected",()-> {assert !ResetReviewTestData.idempotencyTarget("UNKNOWN_SCOPE","PROJECT","same",null,null,Map.of("PROJECT",Set.of("same")));});
    test("known operation requires exact deleted source or result identity",()-> {var ids=Map.of("PROJECT",Set.of("p"),"PROJECT_FILE",Set.of("f"));assert ResetReviewTestData.idempotencyTarget("PROJECT_FILE_UPLOAD","PROJECT","p","PROJECT_FILE","f",ids);assert !ResetReviewTestData.idempotencyTarget("PROJECT_FILE_UPLOAD","PROJECT","new",null,null,ids);});
    test("private identity rejects arbitrary nonprivileged account and schema drift",()-> {
      var approved=ResetReviewTestData.parsePrivateIdentity(identityFixture());assert approved!=null;
      ResetReviewTestData.identity(approved,"jdbc:dm://127.0.0.1:5236","FIXTURE_BUSINESS","FIXTURE_SCHEMA",8);
      for(String u:List.of("jdbc:dm://localhost:5236","jdbc:dm://127.0.0.1:5236?schema=OTHER","jdbc:dm://192.0.2.1:5236"))rejected(()->ResetReviewTestData.identity(approved,u,"FIXTURE_BUSINESS","FIXTURE_SCHEMA",8));
      for(String account:List.of("SYSDBA","SYSAUDITOR","OTHER_BUSINESS"))rejected(()->ResetReviewTestData.identity(approved,"jdbc:dm://127.0.0.1:5236",account,"FIXTURE_SCHEMA",8));
      rejected(()->ResetReviewTestData.identity(approved,"jdbc:dm://127.0.0.1:5236","FIXTURE_BUSINESS","OTHER",8));
      rejected(()->ResetReviewTestData.identity(approved,"jdbc:dm://127.0.0.1:5236","FIXTURE_BUSINESS","FIXTURE_SCHEMA",7));
    });
    test("private identity syntax rejects unknown duplicate missing and privileged fields",()-> {
      for(String bad:List.of(identityFixture()+"password=must-not-be-accepted\n",identityFixture()+"user=OTHER\n",identityFixture().replace("schema=FIXTURE_SCHEMA\n",""),identityFixture().replace("FIXTURE_BUSINESS","SYSDBA"),identityFixture().replace("FIXTURE_SCHEMA","SYSAUDITOR"),identityFixture().replace("127.0.0.1","192.0.2.1"),identityFixture().replace("FIXTURE_SCHEMA","UNSAFE'NAME")))rejected(()->ResetReviewTestData.parsePrivateIdentity(bad));
    });
    test("latest reset receipt can advance while immutable authority stays pinned",()-> {
      Path tmp=Files.createTempDirectory(Path.of(".runtime/contract-repair-test"),"identity-regression-").toAbsolutePath().normalize();
      try{
        Path dir=tmp.resolve(".runtime/contract-repair-test/backups/20261014-182117-590");Files.createDirectories(dir);Path manifest=dir.resolve("manifest.properties"),sidecar=dir.resolve("local-identity.properties"),receipt=dir.resolve("identity-reset-applied.txt"),latest=tmp.resolve(".runtime/contract-repair-test/data-reset-applied.txt");
        Files.writeString(manifest,"format=AUDIT_DM8_TYPED_BACKUP_V1\nplan.sha256=fixture-plan\n");Files.writeString(sidecar,identityFixture());Files.writeString(receipt,"DATA_RESET=PASS\nPLAN_SHA256=fixture-plan\nBACKUP="+dir+"\n");Files.copy(receipt,latest);
        String mh=fixtureSha(manifest),rh=fixtureSha(receipt),ih=fixtureSha(sidecar);
        var approved=ResetReviewTestData.readIdentityAuthority(tmp,tmp,mh,rh,ih);assert approved!=null;assert approved.user.equals("FIXTURE_BUSINESS");assert !approved.toString().contains("FIXTURE_BUSINESS");
        Files.writeString(latest,"DATA_RESET=PASS\nPLAN_SHA256=later-authorized-plan\nBACKUP="+dir.resolveSibling("later-authorized-backup")+"\n");
        var reopened=ResetReviewTestData.readIdentityAuthority(tmp,tmp,mh,rh,ih);assert reopened.user.equals("FIXTURE_BUSINESS");
        rejected(()->ResetReviewTestData.readIdentityAuthority(tmp,tmp.resolveSibling(tmp.getFileName()+"-other"),mh,rh,ih));
        for(Path file:List.of(manifest,receipt,sidecar)){String original=Files.readString(file);Files.writeString(file,original+"tamper\n");rejected(()->ResetReviewTestData.readIdentityAuthority(tmp,tmp,mh,rh,ih));Files.writeString(file,original);}
        Files.writeString(receipt,"DATA_RESET=PASS\nPLAN_SHA256=fixture-plan\nBACKUP="+dir.resolveSibling("sibling-backup")+"\n");String siblingHash=fixtureSha(receipt);rejected(()->ResetReviewTestData.readIdentityAuthority(tmp,tmp,mh,siblingHash,ih));
        Files.delete(sidecar);rejected(()->ResetReviewTestData.readIdentityAuthority(tmp,tmp,mh,siblingHash,ih));
      }finally{try(var paths=Files.walk(tmp)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    });
    test("typed backup preserves null empty Unicode decimals timestamp and blob",()-> {
      List<Object> row=Arrays.asList(null,"","测试𠀀\n'quote'",new BigDecimal("1234567890123456.78"),Timestamp.valueOf("2026-10-14 12:34:56.123456789"),new byte[]{0,1,-1},true,new byte[0]);
      var bytes=ResetReviewTestData.encodeRow(row);var restored=ResetReviewTestData.decodeRow(bytes);
      assert restored.size()==8; assert restored.get(0)==null; assert restored.get(1).equals("");assert restored.get(2).equals(row.get(2));assert restored.get(3).equals(row.get(3));assert restored.get(4).equals(row.get(4));assert Arrays.equals((byte[])restored.get(5),(byte[])row.get(5));assert restored.get(6).equals(true);assert ((byte[])restored.get(7)).length==0;
    });
    test("truncated backup is rejected before mutation",()-> {byte[] b=ResetReviewTestData.encodeRow(List.of("abc"));rejected(()->ResetReviewTestData.decodeRow(Arrays.copyOf(b,b.length-1)));});
    test("snapshot DELETE predicate preserves later IDs",()-> {String p=ResetReviewTestData.predicate(List.of("ID"),List.of("old'key"));assert p.equals("\"ID\"='old''key'"):p;assert !p.contains("new-key");});
    test("self history sorts leaf before referenced predecessor",()-> {assert ResetReviewTestData.order(Set.of("v1","v2","v3"),List.of(new ResetReviewTestData.Edge("v3","v2"),new ResetReviewTestData.Edge("v2","v1"))).equals(List.of("v3","v2","v1"));});
    test("self loop cannot be silently ignored",()-> rejected(()->ResetReviewTestData.order(Set.of("v1"),List.of(new ResetReviewTestData.Edge("v1","v1")))));
    test("numeric FK tuple does not depend on decimal scale",()-> {assert ResetReviewTestData.tuple(List.of(new BigDecimal("1.0"))).equals(ResetReviewTestData.tuple(List.of(new BigDecimal("1.00"))));});
    test("typed CLOB BLOB archive SHA and tampering gate",()-> {
      Path tmp=Files.createTempDirectory(Path.of(".runtime/contract-repair-test"),"archive-regression-");Path file=tmp.resolve("PROJECT_FILE.backup");
      try {
        var cols=List.of(new ResetReviewTestData.Column("ID",Types.VARCHAR,"VARCHAR",false),new ResetReviewTestData.Column("TEXT",Types.CLOB,"CLOB",true),new ResetReviewTestData.Column("BYTES",Types.BLOB,"BLOB",true));
        var rows=List.<List<Object>>of(Arrays.asList("old-file","长文𠀀".repeat(40000),new byte[]{0,-1,12}),Arrays.asList("other","",new byte[0]));
        var table=new ResetReviewTestData.Table("PROJECT_FILE",cols,List.of("ID"),rows);ResetReviewTestData.writeArchive(file,table,rows);
        var read=ResetReviewTestData.readArchive(file);assert read.columns.equals(cols);assert read.pk.equals(List.of("ID"));assert read.rows.get(0).get(1).equals(rows.get(0).get(1));assert Arrays.equals((byte[])read.rows.get(0).get(2),new byte[]{0,-1,12});assert read.rows.get(1).get(1).equals("");assert ((byte[])read.rows.get(1).get(2)).length==0;
        Properties m=new Properties();m.setProperty("delete.order","PROJECT_FILE");m.setProperty("PROJECT_FILE.sha256",ResetReviewTestData.sha(file));m.setProperty("PROJECT_FILE.rows","2");m.setProperty("PROJECT_FILE.fingerprint",ResetReviewTestData.fingerprint(rows));ResetReviewTestData.verifiedArchive(tmp,m);
        byte[] bytes=Files.readAllBytes(file);bytes[bytes.length-1]^=1;Files.write(file,bytes);rejected(()->ResetReviewTestData.verifiedArchive(tmp,m));
      }finally{Files.deleteIfExists(file);Files.delete(tmp);}
    });
    test("protected row mutation is rejected before commit gate",()-> {
      var cols=List.of(new ResetReviewTestData.Column("ID",Types.VARCHAR,"VARCHAR",false),new ResetReviewTestData.Column("STATUS",Types.VARCHAR,"VARCHAR",false));
      var baseline=List.<List<Object>>of(List.of("user-id","ACTIVE"));Properties m=new Properties();m.setProperty("protected.SYS_USER",ResetReviewTestData.fingerprint(baseline));
      var changed=new ResetReviewTestData.Table("SYS_USER",cols,List.of("ID"),List.of(List.of("user-id","LOCKED")));
      var cat=new ResetReviewTestData.Catalog(Map.of("SYS_USER",changed),List.of(),List.of(),"unused");rejected(()->ResetReviewTestData.unchanged(cat,m,false,Map.of()));
    });
    test("restore rejects later target data even with different identity",()-> {
      var cols=List.of(new ResetReviewTestData.Column("ID",Types.VARCHAR,"VARCHAR",false));var backed=new ResetReviewTestData.Table("PROJECT",cols,List.of("ID"),List.of(List.of("old")));
      var current=new ResetReviewTestData.Table("PROJECT",cols,List.of("ID"),List.of(List.of("new")));Properties m=new Properties();m.setProperty("protected.PROJECT","0:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
      var cat=new ResetReviewTestData.Catalog(Map.of("PROJECT",current),List.of(),List.of(),"unused");rejected(()->ResetReviewTestData.unchanged(cat,m,false,Map.of("PROJECT",backed)));
    });
    test("backup manifest cannot select authentication for restore",()-> {Properties m=new Properties();m.setProperty("delete.order","SYS_USER");rejected(()->ResetReviewTestData.verifiedArchive(Path.of(".runtime/contract-repair-test"),m));});
    test("malformed backup lengths and trailing bytes fail closed",()-> {
      rejected(()->ResetReviewTestData.decodeRow(new byte[]{-1,-1,-1,-1}));byte[] b=ResetReviewTestData.encodeRow(List.of("x"));rejected(()->ResetReviewTestData.decodeRow(Arrays.copyOf(b,b.length+1)));
    });
    System.out.println("RESET_TESTS="+tests+" FAILURES="+failures);if(failures!=0)System.exit(1);
  }
  static String identityFixture(){return "format=CONTRACT_LOCAL_DB_IDENTITY_V1\nurl=jdbc:dm://127.0.0.1:5236\nuser=FIXTURE_BUSINESS\nschema=FIXTURE_SCHEMA\n";}
  static String fixtureSha(Path file)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
}
