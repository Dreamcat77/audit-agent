public class ContractAnalysisSchemaTest {
    static void same(String a,String b){if(!ContractAnalysisSchema.canonical(a).equals(ContractAnalysisSchema.canonical(b)))throw new AssertionError("equivalence rejected");}
    static void different(String a,String b){try{if(ContractAnalysisSchema.canonical(a).equals(ContractAnalysisSchema.canonical(b)))throw new AssertionError("drift accepted");}catch(IllegalStateException safelyRejected){}}
    public static void main(String[] args){
        String lease=ContractAnalysisSchema.checks().get("CK_CONTRACT_ANALYSIS_LEASE");
        same(lease,"((\"STATUS\" = 'RUNNING' AND NOT (LEASE_TOKEN IS NULL) AND NOT (LEASE_UNTIL IS NULL)) OR (STATUS != 'RUNNING' AND LEASE_UNTIL IS NULL AND LEASE_TOKEN IS NULL))");
        same("ROW_VERSION >= 0","(((ROW_VERSION >= 0)))");
        same("STATUS IN ('QUEUED','RUNNING')","STATUS IN ( 'RUNNING', 'QUEUED')");
        same("A=1 AND (B=2 AND C=3)","(A=1 AND B=2) AND C=3");
        different(lease,lease.replace("STATUS <> 'RUNNING'","STATUS = 'RUNNING'"));
        different(lease,lease.replace("LEASE_TOKEN IS NOT NULL","LEASE_TOKEN IS NULL"));
        different("A=1 OR (B=2 AND C=3)","(A=1 OR B=2) AND C=3");
        different("STATUS IN ('RUNNING')","STATUS IN ('running')");
        different("ROW_VERSION >= 0","ROW_VERSION >= 1");
        different("A=1","A=1 OR 1=1");different("A=1","A=1 -- ignored");
        System.out.println("CONTRACT_ANALYSIS_EXPRESSION_TEST=PASS cases=11");
    }
}
