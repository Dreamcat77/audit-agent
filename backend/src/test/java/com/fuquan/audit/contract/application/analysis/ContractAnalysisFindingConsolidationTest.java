package com.fuquan.audit.contract.application.analysis;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

class ContractAnalysisFindingConsolidationTest {
    @Test void crossFilePrintedCompleteSumDoesNotDuplicateTheSameVerifiedArithmeticIssue(){
        UUID annex=UUID.randomUUID();
        var contract=reference(file,121,"合同含税总价：人民币480000元");
        var rule=new Finding("F02_LINE_SUM_MATCH","WARNING","合同明细合计与总价核对不一致",
                "合同明细合计与总价核对：450000.00元，与依据金额480000元不一致，差额30000.00元","核对明细",
                List.of(contract,reference(annex,100,"设备 | 300,000.00 | 含税"),reference(annex,121,"安装服务 | 150,000.00 | 含税")),"DETERMINISTIC_F02");
        var summary=reference(annex,144,"合计 | 450,000.00 | 以上为全部合同价款明细");
        var ai=new Finding("CROSS_FILE_CONFLICT","WARNING","合同总价与附件明细金额不一致",
                "合同含税总价为480,000元，而附件全部价款明细合计450,000元，两份文件金额不一致，可能导致结算争议。","核对价款口径",
                List.of(contract,summary),"AI_REVIEW_SUGGESTION");
        assertThat(report(ai,rule)).singleElement().satisfies(f->{
            assertThat(f.code()).isEqualTo("F02_LINE_SUM_MATCH");assertThat(f.evidence()).containsAll(rule.evidence()).contains(summary);
        });
        var tax=new Finding(ai.code(),ai.level(),ai.title(),ai.explanation()+"税费口径不同。",ai.suggestion(),ai.evidence(),ai.origin());
        var other=new Finding(ai.code(),ai.level(),ai.title(),ai.explanation(),ai.suggestion(),List.of(contract,reference(UUID.randomUUID(),144,summary.excerpt())),ai.origin());
        var wrongAmount=new Finding(ai.code(),ai.level(),ai.title(),ai.explanation().replace("450,000","440,000"),ai.suggestion(),ai.evidence(),ai.origin());
        assertThat(report(rule,tax,other,wrongAmount)).hasSize(4);
    }
    @Test void blankPartyAndSigningDateNotesDoNotDuplicateStrongerChecksOnTheSameSource(){
        var party=reference(file,0,"乙方：________");var date=reference(file,60,"签订日期为____年____月____日");
        var ruleParty=new Finding("PARTY_B_NAME_COMPLETENESS","WARNING","乙方名称未填写","乙方：无。","补齐",List.of(party),"DETERMINISTIC_FORM");
        var ruleDate=new Finding("DATE_FORMAT_COMPLETENESS","WARNING","合同日期存在空白或格式问题","签订日期为空白。","补齐",List.of(date),"DETERMINISTIC_DOCUMENT");
        var noteParty=new Finding("MATERIAL_SCOPE_NOTE","INFO","乙方主体信息未填写","乙方名称处为空白。","补齐",List.of(reference(file,0,"乙方：________。")),"AI_REVIEW_SUGGESTION");
        var noteDate=new Finding("MATERIAL_SCOPE_NOTE","INFO","签订日期未填写","签订日期为空白。","补齐",List.of(date),"AI_REVIEW_SUGGESTION");
        assertThat(report(noteParty,noteDate,ruleParty,ruleDate)).containsExactly(ruleParty,ruleDate);
        var other=new Finding(noteDate.code(),noteDate.level(),noteDate.title(),noteDate.explanation(),noteDate.suggestion(),List.of(reference(UUID.randomUUID(),60,date.excerpt())),noteDate.origin());
        var extra=new Finding(noteParty.code(),noteParty.level(),noteParty.title(),"乙方名称空白，另有付款条件不一致。","核对",noteParty.evidence(),noteParty.origin());
        assertThat(report(ruleParty,ruleDate,other,extra)).hasSize(4);
    }
    @Test void combinedBlankNoteNeedsBothChecksAndEveryFrozenSourceToMatch(){
        var party=reference(file,0,"乙方：________");var date=reference(file,60,"签订日期为____年____月____日");
        var ruleParty=new Finding("PARTY_B_NAME_COMPLETENESS","WARNING","乙方名称未填写","乙方：无。","补齐",List.of(party),"DETERMINISTIC_FORM");
        var ruleDate=new Finding("DATE_FORMAT_COMPLETENESS","WARNING","合同日期存在空白或格式问题","签订日期为空白。","补齐",List.of(date),"DETERMINISTIC_DOCUMENT");
        var note=new Finding("MATERIAL_SCOPE_NOTE","INFO","乙方主体及签订日期未填写","合同乙方名称栏为空白，签订日期为待填状态，需核实实际签约主体及生效时间。","补充乙方全称及具体签订日期。",List.of(reference(file,0,"乙方：________。"),date),"AI_REVIEW_SUGGESTION");
        assertThat(report(note,ruleParty,ruleDate)).containsExactly(ruleParty,ruleDate);
        assertThat(report(note,ruleParty)).containsExactly(note,ruleParty);
        var other=new Finding(note.code(),note.level(),note.title(),note.explanation(),note.suggestion(),List.of(party,reference(UUID.randomUUID(),60,date.excerpt())),note.origin());
        var extra=new Finding(note.code(),note.level(),note.title(),note.explanation()+"另有付款条件不一致。",note.suggestion(),note.evidence(),note.origin());
        assertThat(report(ruleParty,ruleDate,other,extra)).hasSize(4);
    }
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
    @Test void sameBlankPartyAndAmountOnExactEvidenceAreNotReportedTwice(){
        var party=reference(file,0,"乙方（承揽方）：____________");
        var money=reference(file,40,"合同金额人民币小写：666000元；大写：柒拾万元整。");
        var form=new Finding("PARTY_B_NAME_COMPLETENESS","WARNING","乙方名称未填写","乙方：无。","补齐",List.of(party),"DETERMINISTIC_FORM");
        var partyAi=new Finding("OTHER_CONCRETE_RISK","WARNING","乙方主体信息缺失","乙方名称处为空白。","补齐",List.of(party),"AI_REVIEW_SUGGESTION");
        var rule=new Finding("F02_AMOUNT_WORDS_MATCH","WARNING","合同金额大小写核对不一致","金额不一致。","核对",List.of(money),"DETERMINISTIC_F02");
        var amountAi=new Finding("OTHER_CONCRETE_RISK","WARNING","合同金额大小写不一致","大小写数值不一致。","核对",List.of(money),"AI_REVIEW_SUGGESTION");
        assertThat(report(partyAi,form,amountAi,rule)).containsExactly(form,rule);
        var other=new Finding(amountAi.code(),amountAi.level(),amountAi.title(),amountAi.explanation(),"",List.of(reference(UUID.randomUUID(),40,money.excerpt())),amountAi.origin());
        var extra=new Finding(partyAi.code(),partyAi.level(),partyAi.title(),"乙方名称为空，另有付款条款不一致。","",List.of(party),partyAi.origin());
        assertThat(report(form,rule,other,extra)).hasSize(4);
    }
    @Test void wordingAndQuoteWidthDoNotDuplicateTheSameContractAmountMismatch(){
        String pair="本协议固定含税价97000元，大写人民币玖万捌仟元整";
        var amountRule=new Finding("F02_AMOUNT_WORDS_MATCH","WARNING","合同金额大小写核对不一致",
                "合同金额大小写核对：97000元，与依据金额98000.00元不一致，差额1000.00元","核对",
                List.of(reference(file,75,"价款约定："+pair+"，已经覆盖材料及全部人工")),"DETERMINISTIC_F02");
        var ai=new Finding("PAYMENT_CONFLICT","WARNING","金额大小写不一致",
                "合同小写金额为97000元，大写金额为玖万捌仟元整（98000元），两者不一致，可能导致结算争议。",
                "核实并修正金额大小写",List.of(reference(file,80,pair)),"AI_REVIEW_SUGGESTION");
        assertThat(report(ai,amountRule)).containsExactly(amountRule);
        var payment=new Finding(ai.code(),ai.level(),ai.title(),ai.explanation()+"付款条件另有矛盾。",
                ai.suggestion(),ai.evidence(),ai.origin());
        var otherClause=new Finding(ai.code(),ai.level(),ai.title(),ai.explanation(),ai.suggestion(),
                List.of(reference(file,180,pair)),ai.origin());
        assertThat(report(amountRule,payment,otherClause)).hasSize(3);
    }
}
