import java.util.*;

/** Executable pure planning/identity tests; never opens a database. */
public class Word97LocalMigrationTest {
  interface Check { void run() throws Exception; }
  static int tests, failures;
  static void test(String name,Check check){tests++;try{check.run();System.out.println("PASS "+name);}catch(Throwable failure){failures++;System.out.println("FAIL "+name+" "+failure.getClass().getSimpleName());}}
  static void reject(Check check)throws Exception{try{check.run();}catch(IllegalStateException expected){return;}throw new AssertionError("unsafe state accepted");}
  public static void main(String[] args)throws Exception{
    test("both old checks expand through wider guards",()->{var plan=Word97LocalMigration.steps("PROJECT_FILE","OLD","MISSING");assert plan.size()==4;assert plan.get(0).startsWith("ALTER TABLE PROJECT_FILE ADD CONSTRAINT CK_PROJECT_FILE_PARSE_METHOD_DOC_GUARD CHECK (");assert plan.get(1).equals("ALTER TABLE PROJECT_FILE DROP CONSTRAINT CK_PROJECT_FILE_PARSE_METHOD");assert plan.get(2).contains("ADD CONSTRAINT CK_PROJECT_FILE_PARSE_METHOD CHECK (");assert plan.get(3).equals("ALTER TABLE PROJECT_FILE DROP CONSTRAINT CK_PROJECT_FILE_PARSE_METHOD_DOC_GUARD");assert Word97LocalMigration.steps("PROJECT_FILE_PARSE_RESULT","OLD","MISSING").size()==4;});
    test("partial guarded stages are discoverable without blind retry",()->{assert Word97LocalMigration.steps("PROJECT_FILE","OLD","NEW").size()==3;assert Word97LocalMigration.steps("PROJECT_FILE","MISSING","NEW").size()==2;assert Word97LocalMigration.steps("PROJECT_FILE","NEW","NEW").size()==1;assert Word97LocalMigration.steps("PROJECT_FILE","NEW","MISSING").isEmpty();});
    test("unknown disabled and unguarded states fail closed",()->{reject(()->Word97LocalMigration.steps("PROJECT_FILE","MISSING","MISSING"));reject(()->Word97LocalMigration.steps("PROJECT_FILE","DISABLED","NEW"));reject(()->Word97LocalMigration.steps("PROJECT_FILE","OLD","OLD"));reject(()->Word97LocalMigration.steps("SYS_USER","OLD","MISSING"));});
    test("nullable root and mandatory immutable result remain different",()->{Word97LocalMigration.column("PROJECT_FILE","VARCHAR",32,"Y");Word97LocalMigration.column("PROJECT_FILE_PARSE_RESULT","VARCHAR",32,"N");reject(()->Word97LocalMigration.column("PROJECT_FILE","VARCHAR",32,"N"));reject(()->Word97LocalMigration.column("PROJECT_FILE_PARSE_RESULT","VARCHAR",32,"Y"));reject(()->Word97LocalMigration.column("PROJECT_FILE","VARCHAR",64,"Y"));});
    test("apply cannot use reset receipt or historical empty backup",()->{reject(()->Word97LocalMigration.readOnlyMode("Apply"));reject(()->Word97LocalMigration.readOnlyMode("Restore"));Word97LocalMigration.readOnlyMode("Plan");Word97LocalMigration.readOnlyMode("Verify");});
    test("approved database identity cannot be replaced",()->{var identity=new ResetReviewTestData.DbIdentity("jdbc:dm://127.0.0.1:5236","APPROVED_TEST","APPROVED_TEST");ResetReviewTestData.identity(identity,identity.url,identity.user,identity.schema,8);reject(()->ResetReviewTestData.identity(identity,identity.url,"SYSDBA",identity.schema,8));reject(()->ResetReviewTestData.identity(identity,identity.url,identity.user,"OTHER",8));});
    test("protected row changes remain detectable without exporting values",()->{var before=List.<List<Object>>of(List.of("synthetic-id","ACTIVE"));var changed=List.<List<Object>>of(List.of("synthetic-id","LOCKED"));assert !ResetReviewTestData.fingerprint(before).equals(ResetReviewTestData.fingerprint(changed));});
    test("startup requires exact expanded checks and preserves null distinction",()->{assert ContractTestRuntimeProbe.word97Check("PARSE_METHOD IS NULL OR PARSE_METHOD IN ('PDF_TEXT','DOCX_TEXT','OCR','DOC_TEXT')",true);assert !ContractTestRuntimeProbe.word97Check("PARSE_METHOD IN ('PDF_TEXT','DOCX_TEXT','OCR')",false);assert !ContractTestRuntimeProbe.word97Check("PARSE_METHOD IS NULL OR PARSE_METHOD IN ('PDF_TEXT','DOCX_TEXT','OCR','DOC_TEXT')",false);assert !ContractTestRuntimeProbe.word97Check("PARSE_METHOD IN ('PDF_TEXT','DOCX_TEXT','OCR','DOC_TEXT','OTHER')",false);});
    if(args.length==1){
      test("operator SQL exactly matches guarded additive plan",()->Word97LocalMigration.approvedSql(java.nio.file.Path.of(args[0])));
      test("both public start and preflight require the DOC schema gate",()->{
        String launcher=java.nio.file.Files.readString(java.nio.file.Path.of(args[0]).resolve("scripts/local/contract-test.ps1"));
        String start=launcher.substring(launcher.indexOf("function Invoke-ContractStart"),launcher.indexOf("function Invoke-ContractStatus"));
        assert start.contains("Invoke-ContractDm8 'StartPreflight'");
        assert launcher.contains("'Preflight'{Assert-ContractPython;Invoke-ContractDm8 'StartPreflight'");
      });
    }
    System.out.println("WORD97_MIGRATION_TESTS="+tests+" FAILURES="+failures);if(failures>0)System.exit(1);
  }
}
