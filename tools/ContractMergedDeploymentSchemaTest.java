import java.util.*;
public class ContractMergedDeploymentSchemaTest {
    static void rejected(Runnable r){try{r.run();throw new AssertionError("must reject");}catch(IllegalStateException expected){}}
    public static void main(String[] args) {
        var old=Map.of("PROJECT_FILE","OLD","PROJECT_FILE_PARSE_RESULT","OLD");
        var newer=Map.of("PROJECT_FILE","NEW","PROJECT_FILE_PARSE_RESULT","NEW");
        var sql=List.of("CREATE TABLE JOB (ID INT)","CREATE INDEX Q ON JOB(ID)","CREATE INDEX P ON JOB(ID)","CREATE TABLE ATTEMPT (ID INT)");
        var plan=ContractMergedDeploymentSchema.plan(0,"OLD","MISSING",old,sql);
        if(plan.size()!=16||!plan.get(4).contains("ADD CONSTRAINT")||!plan.get(5).contains("DROP CONSTRAINT"))throw new AssertionError("wider guard must precede original check removal");
        if(!ContractMergedDeploymentSchema.plan(4,"NEW","MISSING",newer,sql).isEmpty())throw new AssertionError("already matching must be no-op");
        for(int stage:new int[]{1,2,3})rejected(()->ContractMergedDeploymentSchema.plan(stage,"OLD","MISSING",old,sql));
        rejected(()->ContractMergedDeploymentSchema.plan(0,"OLD","NEW",old,sql));
        rejected(()->ContractMergedDeploymentSchema.plan(0,"DRIFT","MISSING",old,sql));
        rejected(()->ContractMergedDeploymentSchema.plan(0,"OLD","MISSING",Map.of("PROJECT_FILE","MISSING","PROJECT_FILE_PARSE_RESULT","NEW"),sql));
        rejected(()->ContractMergedDeploymentSchema.plan(0,"NEW","MISSING",newer,List.of("DELETE FROM SYS_USER")));
        System.out.println("MERGED_SCHEMA_GUARD_ORDER_NOOP_PARTIAL_AND_DRIFT_REJECTION=PASS");
    }
}
