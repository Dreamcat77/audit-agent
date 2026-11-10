package com.fuquan.audit.contract.application.analysis;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

class ContractAnalysisFormChecksTest {
    @Test void blankPartyCaptionIsAnIssueAndNeverAnIdentity() {
        String text="家具定制合同\n甲方（定作方）：青禾大学\n乙方（承揽方）：\n签订时间：    年  月  日\n";
        var source=ContractAnalysisEvidenceTest.source(text);var checks=new ArrayList<Check>();var findings=new ArrayList<Finding>();
        ContractAnalysisFormChecks.evaluate(source,text,List.of(),checks,findings);
        assertThat(findings).singleElement().satisfies(f->{
            assertThat(f.code()).isEqualTo("PARTY_B_NAME_COMPLETENESS");assertThat(f.level()).isEqualTo("WARNING");
            assertThat(f.explanation()).isEqualTo("乙方：无。乙方名称未填写。");
            assertThat(text.substring(f.evidence().get(0).start(),f.evidence().get(0).end())).isEqualTo(f.evidence().get(0).excerpt()).contains("乙方（承揽方）：");
        });
        assertThat(checks).singleElement().satisfies(c->assertThat(c.status()).isEqualTo("FAIL"));
        var doc=new ContractAnalysisReport.Document(source.fileId(),source.name(),"READY","CONTRACT","GENERAL",List.of(),List.of(),checks,findings,text.length(),text.length(),true);
        assertThat(ContractAnalysisCategories.project(List.of(doc),checks,findings)).filteredOn(c->c.id().equals("PARTY_QUALIFICATION"))
                .singleElement().satisfies(c->{assertThat(c.status()).isEqualTo("ATTENTION");assertThat(c.items()).singleElement().satisfies(i->assertThat(i.summary()).contains("乙方：无"));});
    }
    @Test void filledSignatureOrGroundedPartyFactPreventsFalseMissingClaim() {
        for(String suffix:List.of("\n乙方（承揽方）（盖章）：北岭家具有限公司\n住所：北城路", "\n乙方：张三")) {
            var text="定制合同\n乙方（承揽方）：\n"+suffix;var s=ContractAnalysisEvidenceTest.source(text);
            var checks=new ArrayList<Check>();var findings=new ArrayList<Finding>();
            ContractAnalysisFormChecks.evaluate(s,text,List.of(),checks,findings);assertThat(findings).isEmpty();
        }
        String text="合同\n乙方（承揽方）：\n由北岭家具有限公司承揽";var s=ContractAnalysisEvidenceTest.source(text);
        var checks=new ArrayList<Check>();var findings=new ArrayList<Finding>();
        ContractAnalysisFormChecks.evaluate(s,text,List.of(ContractAnalysisRulesTest.fact(s,text,"PARTY_B","北岭家具有限公司")),checks,findings);
        assertThat(findings).isEmpty();
    }
    @Test void emptySignatureContactCaptionsDoNotBecomeNamesOrHideTheBlank() {
        String text="乙方 （承揽方） ：\n乙方（承揽方）（盖章）：住所：法定代表人：电话：账号：";
        var s=ContractAnalysisEvidenceTest.source(text);var checks=new ArrayList<Check>();var findings=new ArrayList<Finding>();
        ContractAnalysisFormChecks.evaluate(s,text,List.of(),checks,findings);
        assertThat(findings).singleElement().satisfies(f->assertThat(f.evidence()).hasSize(2));
    }
    @Test void notExtractedDoesNotProveBlankAndOtherFileCannotSupplyPartyIdentity() {
        String text="定制合同\n乙方负责运输和安装。";var s=ContractAnalysisEvidenceTest.source(text);
        var checks=new ArrayList<Check>();var findings=new ArrayList<Finding>();
        ContractAnalysisFormChecks.evaluate(s,text,List.of(),checks,findings);assertThat(findings).isEmpty();
        text="定制合同\n乙方：";s=ContractAnalysisEvidenceTest.source(text);
        var other=ContractAnalysisEvidenceTest.source("乙方：其他合同公司");
        ContractAnalysisFormChecks.evaluate(s,text,List.of(ContractAnalysisRulesTest.fact(other,"乙方：其他合同公司","PARTY_B","其他合同公司")),checks,findings);
        assertThat(findings).singleElement().satisfies(f->assertThat(f.code()).isEqualTo("PARTY_B_NAME_COMPLETENESS"));
    }
    @Test void explicitPricePaymentBreachAndWarrantyBlanksRemainConcreteIssues() {
        String text="家具定制合同\n人民币金额（小写）：¥     （包含税费）\n本合同签订后    日内，应支付     %作为定金。\n     %尾款由定作方验收后支付。\n承揽方逾期交货，每逾期一天，按货款金额的    向定作方支付违约金。\n定作方无故解除合同，应赔偿合同总计金额的     %违约金。\n承揽方提供    年的保用期。";
        var s=ContractAnalysisEvidenceTest.source(text);var checks=new ArrayList<Check>();var findings=new ArrayList<Finding>();
        ContractAnalysisFormChecks.evaluate(s,text,List.of(),checks,findings);
        assertThat(findings).extracting(Finding::code).containsExactly("CONTRACT_AMOUNT_COMPLETENESS","PAYMENT_TERMS_UNFILLED","BREACH_TERMS_UNFILLED","WARRANTY_TERM_UNFILLED");
        assertThat(findings).allSatisfy(f->f.evidence().forEach(e->assertThat(text.substring(e.start(),e.end())).isEqualTo(e.excerpt())));
        var filled="人民币金额（小写）：¥ 1000（包含税费）\n应支付 30 %作为定金。\n70 %尾款验收后支付。\n按货款金额的 1%向定作方支付违约金。\n赔偿合同总计金额的 10 %违约金。\n提供 3 年的保用期。";
        findings.clear();checks.clear();ContractAnalysisFormChecks.evaluate(ContractAnalysisEvidenceTest.source(filled),filled,List.of(),checks,findings);
        assertThat(findings).isEmpty();
    }
}
