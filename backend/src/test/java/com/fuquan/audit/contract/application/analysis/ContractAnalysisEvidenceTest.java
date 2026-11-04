package com.fuquan.audit.contract.application.analysis;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

class ContractAnalysisEvidenceTest {
    final ContractAnalysisEvidence validator=new ContractAnalysisEvidence();
    static Source source(String text){return new Source(UUID.randomUUID(),"合成合同.docx","a".repeat(64),100,SourceState.READY,UUID.randomUUID(),1,sha256(text),null);}
    static ContractAnalysisEvidence.Scope scope(String text){return new ContractAnalysisEvidence.Scope(source(text),text,0,text.length());}
    static ContractAnalysisAiPort.Result result(List<ContractAnalysisAiPort.Fact> facts,List<ContractAnalysisAiPort.Clause> clauses,List<ContractAnalysisAiPort.Finding> findings){return new ContractAnalysisAiPort.Result("synthetic-test","contract-case-v1","CONTRACT","SERVICE",true,facts,clauses,findings);}
    @Test void whitespaceToleranceRetainsOriginalOffsetsAndBytes(){
        var s=scope("开始\n验收合格后 10 日内支付\n结束");var e=validator.locate(s,"验收合格后10日内支付").orElseThrow();
        assertThat(s.text().substring(e.start(),e.end())).isEqualTo(e.excerpt()).isEqualTo("验收合格后 10 日内支付");
        assertThat(e.textSha256()).isEqualTo(s.source().textSha256());
    }
    @Test void repeatedCitationIsRejectedRatherThanGuessed(){
        assertThat(validator.locate(scope("支付100元\n支付100元"),"支付100元")).isEmpty();
    }
    @Test void quoteOutsideSelectedFragmentCannotSupportModelConclusion(){
        String text="甲方名称：合成机关\n验收合格后付款";var s=scope(text);
        var fragment=new ContractAnalysisEvidence.Scope(s.source(),text,11,text.length());
        assertThat(validator.locate(fragment,"甲方名称：合成机关")).isEmpty();
    }
    @Test void realQuoteDoesNotAuthorizeInventedFactValue(){
        var s=scope("合同总金额：100元");var quote=new ContractAnalysisAiPort.Citation(s.source().fileId(),s.text());
        var r=validator.validate(result(List.of(new ContractAnalysisAiPort.Fact("TOTAL_AMOUNT","999元",List.of(quote))),List.of(),List.of()),List.of(s),"FILE");
        assertThat(r.facts()).isEmpty();assertThat(r.rejectedCitations()).isEqualTo(1);assertThat(r.complete()).isFalse();
    }
    @Test void partialScopeCannotAssertWholeContractAbsence(){
        var s=scope("验收合格后付款");var quote=new ContractAnalysisAiPort.Citation(s.source().fileId(),s.text());
        var clause=new ContractAnalysisAiPort.Clause("BREACH","本合同没有违约条款",List.of(quote));
        assertThat(validator.validate(result(List.of(),List.of(clause),List.of()),List.of(s),"FRAGMENT").clauses()).isEmpty();
    }
    @Test void crossFileConflictRequiresBothSources(){
        var s=scope("合同付款比例40%");var f=new ContractAnalysisAiPort.Finding("CROSS_FILE_CONFLICT","WARNING","付款冲突","付款比例不同","核对适用版本",List.of(new ContractAnalysisAiPort.Citation(s.source().fileId(),s.text())));
        assertThat(validator.validate(result(List.of(),List.of(),List.of(f)),List.of(s),"CROSS_FILE").findings()).isEmpty();
    }
    @Test void genericPaymentCodeCannotBypassTwoSidedCrossFileEvidence(){
        var a=scope("原合同约定验收后支付65%");var b=scope("补充协议要求另行足额支付40%，与原款项互不抵扣");
        var first=new ContractAnalysisAiPort.Citation(a.source().fileId(),a.text());var second=new ContractAnalysisAiPort.Citation(b.source().fileId(),b.text());
        var one=new ContractAnalysisAiPort.Finding("PAYMENT_CONFLICT","WARNING","两套付款冲突","原合同与补充协议要求重复足额支付","核实优先关系",List.of(first));
        assertThat(validator.validate(result(List.of(),List.of(),List.of(one)),List.of(a,b),"CROSS_FILE").findings()).isEmpty();
        var both=new ContractAnalysisAiPort.Finding("PAYMENT_CONFLICT","WARNING",one.title(),one.explanation(),one.suggestion(),List.of(first,second));
        assertThat(validator.validate(result(List.of(),List.of(),List.of(both)),List.of(a,b),"CROSS_FILE").findings()).singleElement().satisfies(f->{assertThat(f.code()).isEqualTo("CROSS_FILE_CONFLICT");assertThat(f.evidence()).hasSize(2);});
    }
    @Test void noRiskDoesNotMeanEmptyReportWhenActualClausesWereReviewed(){
        var s=scope("验收合格后十日内付款");var clause=new ContractAnalysisAiPort.Clause("PAYMENT","付款以验收合格为前提，期限为十日",List.of(new ContractAnalysisAiPort.Citation(s.source().fileId(),s.text())));
        var out=validator.validate(result(List.of(),List.of(clause),List.of()),List.of(s),"FILE");
        assertThat(out.useful()).isTrue();assertThat(out.findings()).isEmpty();assertThat(out.complete()).isTrue();
    }
    @Test void noConflictComparisonRequiresEvidenceFromBothFiles(){
        var a=scope("原合同第五条：验收后支付65%");var b=scope("本补充协议替换原合同第五条，验收后支付40%，原付款条款不再适用");
        var first=new ContractAnalysisAiPort.Citation(a.source().fileId(),a.text());
        var second=new ContractAnalysisAiPort.Citation(b.source().fileId(),b.text());
        var both=new ContractAnalysisAiPort.Clause("PAYMENT","补充协议明确替换原付款条款，属于变更",List.of(first,second));
        var out=validator.validate(result(List.of(),List.of(both),List.of()),List.of(a,b),"CROSS_FILE");
        assertThat(out.useful()).isTrue();assertThat(out.complete()).isTrue();assertThat(out.findings()).isEmpty();
        var one=new ContractAnalysisAiPort.Clause("PAYMENT",both.summary(),List.of(first));
        var rejected=validator.validate(result(List.of(),List.of(one),List.of()),List.of(a,b),"CROSS_FILE");
        assertThat(rejected.useful()).isFalse();assertThat(rejected.complete()).isFalse();
    }
    @Test void forgedDocumentIdAndMalformedProtocolAreRejected(){
        var s=scope("验收合格后付款");var f=new ContractAnalysisAiPort.Fact("PARTY_A","验收",List.of(new ContractAnalysisAiPort.Citation(UUID.randomUUID(),s.text())));
        assertThat(validator.validate(result(List.of(f),List.of(),List.of()),List.of(s),"FILE").facts()).isEmpty();
        assertThatThrownBy(()->validator.validate(null,List.of(s),"FILE")).hasMessage("MODEL_SCHEMA_INVALID");
    }
    @Test void procurementBudgetCannotBeRelabeledAsContractTotal(){
        var s=scope("项目预算金额为100万元，中标金额为90万元");
        var f=new ContractAnalysisAiPort.Fact("TOTAL_AMOUNT","100万元",List.of(new ContractAnalysisAiPort.Citation(s.source().fileId(),s.text())));
        assertThat(validator.validate(result(List.of(f),List.of(),List.of()),List.of(s),"FILE").facts()).isEmpty();
    }
    @Test void referencedContractNumberIsNotTheSupplementOwnNumber(){
        var s=scope("关联合同编号：MAIN-01");
        var citation=new ContractAnalysisAiPort.Citation(s.source().fileId(),s.text());
        var wrong=new ContractAnalysisAiPort.Fact("CONTRACT_NO","MAIN-01",List.of(citation));
        var reference=new ContractAnalysisAiPort.Fact("CONTRACT_REFERENCE","MAIN-01",List.of(citation));
        var out=validator.validate(result(List.of(wrong,reference),List.of(),List.of()),List.of(s),"FILE");
        assertThat(out.facts()).extracting(Fact::type).containsExactly("CONTRACT_REFERENCE");
        assertThat(out.complete()).isFalse();
        var own=scope("合同编号：SUP-02");
        var ownFact=new ContractAnalysisAiPort.Fact("CONTRACT_NO","SUP-02",List.of(new ContractAnalysisAiPort.Citation(own.source().fileId(),own.text())));
        assertThat(validator.validate(result(List.of(ownFact),List.of(),List.of()),List.of(own),"FILE").facts()).hasSize(1);
    }
    @Test void singleFileCannotDeclareAnUploadedBatchAttachmentMissing(){
        var s=scope("验收时按合同附件技术参数逐台核对");
        var finding=new ContractAnalysisAiPort.Finding("ACCEPTANCE_AMBIGUITY","WARNING","核对附件范围","当前文档中未包含该附件，应核对技术参数附件","补充核对附件",List.of(new ContractAnalysisAiPort.Citation(s.source().fileId(),s.text())));
        var out=validator.validate(result(List.of(),List.of(),List.of(finding)),List.of(s),"FILE");
        assertThat(out.findings()).isEmpty();
        assertThat(out.rejectedCitations()).isEqualTo(1);
    }
    @Test void optionalImprovementCannotBePromotedByModelWarningLabel(){
        var s=scope("合同生效并收到预付款发票后支付30%");
        var finding=new ContractAnalysisAiPort.Finding("PAYMENT_TRIGGER","WARNING","开票期限可完善","可另行明确开票时间","双方可补充约定",List.of(new ContractAnalysisAiPort.Citation(s.source().fileId(),s.text())),"OPTIONAL_IMPROVEMENT");
        assertThat(validator.validate(result(List.of(),List.of(),List.of(finding)),List.of(s),"FILE").findings()).singleElement().satisfies(f->assertThat(f.level()).isEqualTo("INFO"));
    }
    @Test void actualPaymentConflictRemainsRiskRatherThanBeingHidden(){
        var s=scope("预付款60%，验收款65%，质保款5%，均独立足额支付且互不抵扣");
        var finding=new ContractAnalysisAiPort.Finding("PAYMENT_CONFLICT","WARNING","支付安排相互冲突","各期比例均要求独立足额支付，无法按合同总价同时执行","核对各期比例",List.of(new ContractAnalysisAiPort.Citation(s.source().fileId(),s.text())),"CLAUSE_ISSUE");
        assertThat(validator.validate(result(List.of(),List.of(),List.of(finding)),List.of(s),"FILE").findings()).singleElement().satisfies(f->assertThat(f.level()).isEqualTo("WARNING"));
    }
    @Test void upstreamRejectedItemsRemainPartialEvenWhenRemainingEvidenceIsValid(){
        var s=scope("验收合格后付款");var citation=new ContractAnalysisAiPort.Citation(s.source().fileId(),s.text());
        var result=new ContractAnalysisAiPort.Result("synthetic-test","contract-case-v1","CONTRACT","SERVICE",true,List.of(),List.of(new ContractAnalysisAiPort.Clause("PAYMENT","验收合格后付款",List.of(citation))),List.of(),2);
        var out=validator.validate(result,List.of(s),"FILE");
        assertThat(out.clauses()).hasSize(1);assertThat(out.complete()).isFalse();assertThat(out.rejectedCitations()).isEqualTo(2);
    }
    @Test void unknownFindingBasisFailsClosedWithoutDiscardingOtherValidAnalysis(){
        var s=scope("验收合格后付款");var citation=new ContractAnalysisAiPort.Citation(s.source().fileId(),s.text());
        var finding=new ContractAnalysisAiPort.Finding("PAYMENT_TRIGGER","WARNING","风险","待核实","核对",List.of(citation),"MODEL_SELF_APPROVED");
        var out=validator.validate(result(List.of(),List.of(new ContractAnalysisAiPort.Clause("PAYMENT","验收合格后付款",List.of(citation))),List.of(finding)),List.of(s),"FILE");
        assertThat(out.findings()).isEmpty();assertThat(out.clauses()).hasSize(1);assertThat(out.complete()).isFalse();
    }
}
