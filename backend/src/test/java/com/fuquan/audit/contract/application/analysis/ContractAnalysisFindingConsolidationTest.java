package com.fuquan.audit.contract.application.analysis;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

class ContractAnalysisFindingConsolidationTest {
    final UUID file=UUID.randomUUID(),parse=UUID.randomUUID();
    Evidence reference(UUID id,int start,String quote){return new Evidence(id,"a".repeat(64),parse,1,"b".repeat(64),start,start+quote.length(),quote);}
    Finding rule(){return new Finding("F02_LINE_SUM_MATCH","WARNING","合同明细合计与总价核对不一致",
        "合同明细合计与总价核对：78000元，与依据金额120000.00元不一致，差额42000.00元","核对明细",List.of(reference(file,0,"合同总金额120000.00元"),reference(file,100,"明细合计78000.00元")),"DETERMINISTIC_F02");}
    Finding ai(String explanation,UUID source){return new Finding("OTHER_CONCRETE_RISK","WARNING","合同总金额与明细表合计不一致",explanation,"核实明细",List.of(reference(source,0,"合同总金额120000.00元"),reference(source,100,"明细合计78000.00元")),"AI_REVIEW_SUGGESTION");}
    List<Finding> report(Finding... findings){
        var output=new Output("CONTRACT","PROCUREMENT",true,List.of(),List.of(),List.of(),List.of(findings),0,"synthetic-model","contract-case-v1");
        var step=new Step("rules/"+file,file,"RULES",0,200,sha256("synthetic"),StepState.COMPLETED,null,Instant.EPOCH,output,null);
        return ContractAnalysisReport.uniqueFindings(List.of(step));
    }
    @Test void exactArithmeticalRestatementCountsOneGroundedRisk(){
        var expected=rule();assertThat(report(ai("总金额120000元，明细合计78000元，差额42000元，不一致",file),expected)).containsExactly(expected);
    }
    @Test void differentAmountsAndDifferentFilesCannotHideAConcern(){
        var drift=ai("总金额120000元，明细合计78000元，差额40000元，不一致",file);
        var other=ai("总金额120000元，明细合计78000元，差额42000元，不一致",UUID.randomUUID());
        assertThat(report(rule(),drift,other)).hasSize(3);
    }
    @Test void additionalPaymentOrTaxMeaningRemainsIndependent(){
        var payment=ai("明细合计78000元、总金额120000元，差额42000元不一致，另外付款条件互相矛盾",file);
        var tax=ai("明细合计78000元、总金额120000元，差额42000元不一致，税费口径不同",file);
        assertThat(report(rule(),payment,tax)).hasSize(3);
    }
}
