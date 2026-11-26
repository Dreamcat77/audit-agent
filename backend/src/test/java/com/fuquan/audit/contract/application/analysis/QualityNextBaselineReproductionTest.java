package com.fuquan.audit.contract.application.analysis;

import com.fuquan.audit.contract.application.clause.*;
import com.fuquan.audit.contract.application.project.extraction.ProjectFactExtractor;
import com.fuquan.audit.contract.domain.project.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;
import static org.assertj.core.api.Assertions.*;

/** Independent reproductions from the new 26 baseline. Expected to fail before fixes. */
class QualityNextBaselineReproductionTest {
    @Test void historicalProjectCaptionCannotSupplyCurrentContractAmountOrParty() {
        String text="供应商履约业绩说明\n供应商：澄源信息技术有限公司\n历史项目名称：东河档案数字化项目\n"
                +"历史项目合同金额：9600000元，历史项目甲方：东河档案管理中心。\n本页仅为历史业绩，不是当前合同。";
        assertThat(ContractAnalysisSemantics.reference(text,text.indexOf("9600000"))).isTrue();
        assertThat(ContractAnalysisSemantics.reference(text,text.indexOf("东河档案管理中心"))).isTrue();
    }

    @Test void explicitCompleteAmountOnlyPriceTableCanBeSummedWithoutInventingQuantity() {
        String text="合同价款明细表（本表列明全部价款构成，无其他分项或另计费用）\n"
                +"项目名称：桂雨终端更新项目 | 合同编号：HT-CY-2027-009 | 单位：元\n"
                +"分项名称 | 分项金额（元） | 备注\n设备 | 300000 | 含税\n安装服务 | 150000 | 含税\n合计 | 450000 | 以上为全部合同价款明细\n";
        var table=ContractAnalysisTable.parse(ContractAnalysisEvidenceTest.source(text),text);
        assertThat(table.complete()).isTrue();
        assertThat(table.rows()).hasSize(2);
        assertThat(table.rows().stream().map(ContractAnalysisTable.Row::amount).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add))
                .isEqualByComparingTo("450000");
        for(var row:table.rows()){
            assertThat(text.substring(row.evidence().start(),row.evidence().end())).isEqualTo(row.evidence().excerpt());
            assertThat(row.quantity()).isNull();assertThat(row.price()).isNull();
        }
    }

    @Test void indexedPriceTableTotalAndAdjacentCompletenessFooterRetainFullSumEvidence(){
        String start="合同价款明细表\n项目名称：栖霞购置项目 合同编号：HT-QX-01 单位：元\n序号 | 分项名称 | 分项金额（元） | 备注\n1 | 设备主体 | 200000 | 含税\n2 | 安装培训 | 60000 | 含税\n合计 | | 260000 | \n";
        String footer="以上为本合同全部价款明细，无其他分项或另计费用。";
        String text=start+footer;
        var table=ContractAnalysisTable.parse(ContractAnalysisEvidenceTest.source(text),text);
        assertThat(table.complete()).isTrue();assertThat(table.rows()).hasSize(2);
        assertThat(table.rows().stream().map(ContractAnalysisTable.Row::amount).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add)).isEqualByComparingTo("260000");
        assertThat(table.rows()).anySatisfy(r->assertThat(r.evidence().excerpt()).contains("合计",footer));
        for(var row:table.rows())assertThat(text.substring(row.evidence().start(),row.evidence().end())).isEqualTo(row.evidence().excerpt());
        for(String incomplete:List.of("本表只列部分价款明细。","以上并非全部价款明细。","另一项目列明全部价款构成。")){
            String other=start+incomplete;
            assertThat(ContractAnalysisTable.parse(ContractAnalysisEvidenceTest.source(other),other).complete()).isFalse();
        }
    }

    @Test void colonAttachmentDeclarationsOutsideAttachmentSectionStillNeedUploadComparison() {
        var contract=new ContractClauseMaterial(UUID.randomUUID(),"合同书.docx",ProjectFileCategory.CONTRACT_BODY,
                "服务合同\n附件一：设备服务清单，作为合同组成部分。附件二：保密协议，作为合同组成部分。\n甲方签章。\n");
        var service=new ContractClauseMaterial(UUID.randomUUID(),"附件一服务清单.pdf",ProjectFileCategory.OTHER,
                "附件一 服务清单\n设备巡检12次，月度报告12份。\n");
        var checks=new ContractTermClauseAnalyzer().analyze(List.of(contract,service)).deterministicChecks();
        assertThat(checks).anyMatch(c->c.type().equals("ATTACHMENT_REFERENCE_MATCH")
                &&c.status().equals("ATTENTION")&&c.summary().contains("保密协议"));
        assertThat(checks).noneMatch(c->c.type().equals("ATTACHMENT_REFERENCE_MATCH")
                &&c.status().equals("ATTENTION")&&c.summary().contains("服务清单"));
    }

    @Test void partyCaptionMustNotSwallowExplanatoryDepartmentProse() {
        String text="合同名称：澄源服务合同\n甲方：上海市公安局(主管)，具体履约部门为警务保障处。\n"
                +"乙方：澄源信息技术有限公司\n合同总价：480000元。";
        var source=ContractAnalysisEvidenceTest.source(text);var now=Instant.now();
        var file=new ProjectFile(source.fileId(),UUID.randomUUID(),null,"合同书.docx","合同书.docx","application/octet-stream",
                ProjectFileCategory.CONTRACT_BODY,ProjectFileIngestionStatus.AVAILABLE,ProjectFileParseStatus.PARSED,
                source.sha256(),source.sha256(),100,text,null,now,now);
        var rules=new ContractAnalysisRules(new ProjectFactExtractor(Clock.systemUTC()),new ContractAnalysisEvidence());
        assertThat(rules.recall(file,source,text).facts().stream().filter(f->f.type().equals("PARTY_A")))
                .isNotEmpty().allSatisfy(f->assertThat(f.value()).doesNotContain("具体履约部门","，",","));
    }

    @Test void providedAnnexWithSameOrdinalButUnrelatedTitleCannotSatisfyReference() {
        var contract=new ContractClauseMaterial(UUID.randomUUID(),"合同书.docx",ProjectFileCategory.CONTRACT_BODY,
                "附件一：设备服务清单，作为合同组成部分。\n");
        var unrelated=new ContractClauseMaterial(UUID.randomUUID(),"附件一保密协议.pdf",ProjectFileCategory.OTHER,
                "附件一 保密协议\n双方不得向第三人披露履约资料。\n");
        assertThat(new ContractTermClauseAnalyzer().analyze(List.of(contract,unrelated)).deterministicChecks())
                .anyMatch(c->c.type().equals("ATTACHMENT_REFERENCE_MATCH")&&c.status().equals("ATTENTION"));
    }

    @Test void oneContractMayUseItsUnambiguousUploadedAnnexWithoutAnExtraProjectCaption() {
        String body="服务合同\n附件一：设备服务清单，作为合同组成部分。附件二：保密协议，作为合同组成部分。\n";
        String annex="附件一 服务清单\n设备巡检12次，月度报告12份。\n";
        var contract=new Source(UUID.randomUUID(),"合同书.docx","a".repeat(64),100,SourceState.READY,UUID.randomUUID(),1,sha256(body),null);
        var file=new Source(UUID.randomUUID(),"附件一服务清单.pdf","b".repeat(64),100,SourceState.READY,UUID.randomUUID(),1,sha256(annex),null);
        var output=ContractAnalysisDocumentRules.evaluate(contract,List.of(contract,file),List.of(),Map.of(contract.fileId(),body,file.fileId(),annex),new ContractAnalysisRelations());
        assertThat(output.findings()).filteredOn(f->f.code().equals("ATTACHMENT_REFERENCE_MATCH"))
                .singleElement().satisfies(f->assertThat(f.explanation()).contains("保密协议").doesNotContain("服务清单"));
    }

    @Test void completeAnnexAmountsAreComparedToTheExplicitlyReferencedContractOnly() {
        String body="合同编号：HT-01\n合同总价：480000元";
        String detail="合同价款明细表（本表列明全部价款构成）\n合同编号：HT-01\n分项名称 | 分项金额（元）\n设备 | 300000\n安装服务 | 150000\n合计 | 450000\n";
        var contract=new Source(UUID.randomUUID(),"合同书.docx","a".repeat(64),100,SourceState.READY,UUID.randomUUID(),1,sha256(body),null);
        var annex=new Source(UUID.randomUUID(),"合同价款明细.xlsx","b".repeat(64),100,SourceState.READY,UUID.randomUUID(),1,sha256(detail),null);
        var cf=List.of(ContractAnalysisRulesTest.fact(contract,body,"CONTRACT_NO","HT-01"),ContractAnalysisRulesTest.fact(contract,body,"TOTAL_AMOUNT","480000元"));
        var af=List.of(ContractAnalysisRulesTest.fact(annex,detail,"CONTRACT_NO","HT-01"));
        var work=List.of(ready(contract,body,cf),ready(annex,detail,af));var relations=new ContractAnalysisRelations();
        var pairs=relations.pairs(List.of(contract,annex),work);assertThat(pairs).hasSize(1);
        var output=relations.evaluate(pairs.get(0),work,Map.of(contract.fileId(),body,annex.fileId(),detail));
        assertThat(output.findings()).anySatisfy(f->{assertThat(f.code()).isEqualTo(com.fuquan.audit.contract.domain.F02RuleCodes.LINE_SUM_MATCH);
            assertThat(f.explanation()).contains("450000","480000","30000");assertThat(f.evidence()).hasSize(3);});
        var wrong=List.of(ready(contract,body,cf),ready(annex,detail,List.of(ContractAnalysisRulesTest.fact(annex,detail,"CONTRACT_NO","HT-OTHER"))));
        assertThat(relations.pairs(List.of(contract,annex),wrong)).isEmpty();
    }
    private Step ready(Source source,String text,List<Fact> facts) {
        return ContractAnalysisPlan.step("test/"+source.fileId(),source,"FILE",0,text.length()).running(Instant.EPOCH)
                .finish(StepState.COMPLETED,new Output("CONTRACT","GENERAL",true,facts,List.of(),List.of(),List.of(),0,"synthetic","v1"),null,Instant.EPOCH);
    }
}
