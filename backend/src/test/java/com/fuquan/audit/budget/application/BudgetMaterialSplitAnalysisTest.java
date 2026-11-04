package com.fuquan.audit.budget.application;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.budget.application.BudgetMaterialRisk.*;
import com.fuquan.audit.budget.domain.BudgetSplitRiskRule;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class BudgetMaterialSplitAnalysisTest {
    @Test void selectedRowsAreAddedWithoutVersionIdentityDateOrDepartmentFiltering() {
        var a=BudgetMaterialSplitAnalysis.analyzeSelected(List.of(row("甲","电子","31200","2026","甲"),row("甲","电子","19600","2025","乙"),row("甲","电子","31200","2026","甲")));
        assertThat(a.totalAmount()).isEqualByComparingTo("82000");assertThat(a.countedItems()).isEqualTo(3);
        assertThat(a.complete()).isTrue();assertThat(a.exclusions()).isEmpty();assertThat(a.bandCode()).isEqualTo("BELOW_1M");
    }
    @Test void oneMillionBoundaryIsInclusiveAndUsesExactDecimalArithmetic() {
        for(String amount:List.of("0", "9999.99", "10000", "100000", "999999.99")) {
            var a=BudgetMaterialSplitAnalysis.analyzeSelected(List.of(row("甲","电子",amount,null,null)));
            assertThat(a.version()).isEqualTo("SELECTED_SUM_V3");
            assertThat(a.bandCode()).isEqualTo("BELOW_1M");
            assertThat(a.riskLevel()).isEqualTo("INFO");
            assertThat(a.summary()).contains("未触发 100 万元的额外审批门槛");
            assertThat(a.totalAmount()).isEqualByComparingTo(amount);
        }
        var above=BudgetMaterialSplitAnalysis.analyzeSelected(List.of(row("甲","电子","1000000.01",null,null)));
        assertThat(above.bandCode()).isEqualTo("AT_OR_ABOVE_1M");
        assertThat(above.riskLevel()).isEqualTo("WARNING");
        assertThat(above.summary()).contains("需履行额外审批");
        var combined=BudgetMaterialSplitAnalysis.analyzeSelected(List.of(row("甲","电子","999999.99",null,null),row("乙","工程","0.02",null,null)));
        assertThat(combined.totalAmount()).isEqualByComparingTo("1000000.01");
        assertThat(combined.bandCode()).isEqualTo("AT_OR_ABOVE_1M");
        var exact=BudgetMaterialSplitAnalysis.analyzeSelected(List.of(row("甲","电子","600000",null,null),row("乙","工程","400000.00",null,null)));
        assertThat(exact.bandCode()).isEqualTo("AT_OR_ABOVE_1M");
        assertThat(exact.riskLevel()).isEqualTo("WARNING");
        for(String amount:List.of("1000000","1000000.00")) {
            var boundary=BudgetMaterialSplitAnalysis.analyzeSelected(List.of(row("甲","电子",amount,null,null)));
            assertThat(boundary.bandCode()).isEqualTo("AT_OR_ABOVE_1M");
            assertThat(boundary.summary()).contains("需履行额外审批");
        }
    }
    @Test void missingOrNegativeAmountsNeverProduceAnAllClearBand() {
        for(String amount:Arrays.asList(null,"-0.01")) {
            var a=BudgetMaterialSplitAnalysis.analyzeSelected(List.of(row("甲","电子",amount,null,null),row("乙","工程","1000001",null,null)));
            assertThat(a.complete()).isFalse();assertThat(a.bandCode()).isNull();
            assertThat(a.riskLevel()).isEqualTo("UNKNOWN");assertThat(a.summary()).doesNotContain("未触发");
        }
    }
    @Test void absentAmountsAreNeverReportedAsZeroOrAssignedToACompleteBand() {
        var a=BudgetMaterialSplitAnalysis.analyzeSelected(List.of(row("甲","电子",null,null,null),row("乙","工程","20000",null,null)));
        assertThat(a.totalAmount()).isEqualByComparingTo("20000");assertThat(a.complete()).isFalse();assertThat(a.bandCode()).isNull();assertThat(a.summary()).contains("暂不能确定");
    }
    BudgetSplitRiskRule rule(String amount) {return new BudgetSplitRiskRule("TEST_"+amount,"测试门槛",30,new BigDecimal(amount),new BigDecimal(amount),2,60,85,3);}
    Candidate row(String project,String subject,String amount,String year,String department) {
        return new Candidate(new Selection(UUID.randomUUID(),0),"合成预算.xlsx","2026-10-16T00:00:00Z",
                new BudgetUploadedFacts.Line(project,project,subject,null,year,department,
                        amount==null?null:new BigDecimal(amount),null,"INSUFFICIENT","缺少批复金额",List.of()));
    }
    @Test void amountsFromUploadedDeclarationsTriggerEachApplicableThresholdWithoutApprovalMasterData() {
        var first=row("改造甲","音频","30000.00","2026","部门甲");
        var second=row("改造乙","布线","20000.00","2026","部门甲");
        var a=BudgetMaterialSplitAnalysis.analyze(List.of(first,second),List.of(rule("50000"),rule("200000"),rule("2000000")));
        assertThat(a.findings()).hasSize(1);assertThat(a.riskLevel()).isEqualTo("WARNING");
        var f=a.findings().get(0);assertThat(f.totalAmount()).isEqualByComparingTo("50000");assertThat(f.rule().version()).isEqualTo(3);
        assertThat(f.projects()).containsExactly(first,second);
        assertThat(f.explanation()).contains("审批、报告","同一项目","尚未验证","30 天");
    }
    @Test void sameProjectDifferentSubjectsAreCombinedButDuplicateFilesAreNotExtraSpending() {
        var a=BudgetMaterialSplitAnalysis.analyze(List.of(row("项目甲","音频","30000","2026","甲"),row("项目甲","音频","30000.00","2026","甲"),row("项目甲","布线","20000","2026","甲")),List.of(rule("50000")));
        assertThat(a.totalAmount()).isEqualByComparingTo("50000");assertThat(a.countedItems()).isEqualTo(2);
        assertThat(a.findings()).hasSize(1);assertThat(a.exclusions()).extracting(BudgetMaterialSplitAnalysis.Exclusion::reasonCode).containsExactly("DUPLICATE");
    }
    @Test void conflictingVersionsAreExcludedRatherThanAddedToReachThreshold() {
        var a=BudgetMaterialSplitAnalysis.analyze(List.of(row("甲","电子","30000","2026","甲"),row("甲","电子","40000","2026","甲"),row("乙","电子","20000","2026","甲")),List.of(rule("50000")));
        assertThat(a.findings()).isEmpty();assertThat(a.totalAmount()).isEqualByComparingTo("20000");
        assertThat(a.exclusions()).hasSize(2).allMatch(e->e.reasonCode().equals("CONFLICTING_VERSIONS"));assertThat(a.summary()).contains("依据不足");
    }
    @Test void exactSingleItemThresholdIsNotASplitAndDecimalArithmeticHasNoFloatingPointError() {
        assertThat(BudgetMaterialSplitAnalysis.analyze(List.of(row("甲","电子","50000","2026","甲"),row("乙","电子","1","2026","甲")),List.of(rule("50000"))).findings()).isEmpty();
        assertThat(BudgetMaterialSplitAnalysis.analyze(List.of(row("甲","电子","24999.99","2026","甲"),row("乙","电子","25000","2026","甲")),List.of(rule("50000"))).findings()).isEmpty();
        assertThat(BudgetMaterialSplitAnalysis.analyze(List.of(row("甲","电子","24999.99","2026","甲"),row("乙","电子","25000.01","2026","甲")),List.of(rule("50000"))).findings()).hasSize(1);
    }
    @Test void allThreeExistingThresholdsUseTheirOwnIndividualAndAggregateLimits() {
        var rules=List.of(rule("50000"),rule("200000"),rule("2000000"));
        for(String[] example:List.of(new String[]{"30000","20000","50000"},new String[]{"80000","120000","200000"},new String[]{"800000","1200000","2000000"})) {
            var a=BudgetMaterialSplitAnalysis.analyze(List.of(row("甲","电子",example[0],"2026","甲"),row("乙","电子",example[1],"2026","甲")),rules);
            assertThat(a.findings()).hasSize(1);assertThat(a.findings().get(0).rule().threshold()).isEqualByComparingTo(example[2]);
        }
    }
    @Test void unrelatedYearsDepartmentsAndMissingScopeNeverBridgeToReachThreshold() {
        var a=BudgetMaterialSplitAnalysis.analyze(List.of(row("甲","电子","30000","2026","甲"),row("乙","电子","30000","2025","甲"),row("丙","电子","30000","2026","乙"),row("丁","电子","30000",null,"甲")),List.of(rule("50000")));
        assertThat(a.findings()).isEmpty();assertThat(a.summary()).contains("分别核算");assertThat(a.riskLevel()).isEqualTo("UNKNOWN");
    }
    @Test void noRulesAndMissingAmountsNeverProduceAnAllClearConclusion() {
        var a=BudgetMaterialSplitAnalysis.analyze(List.of(row("甲","电子",null,"2026","甲")),List.of());
        assertThat(a.riskLevel()).isEqualTo("UNKNOWN");assertThat(a.summary()).contains("未配置","依据不足");assertThat(a.exclusions()).hasSize(1);
    }
    @Test void sourceUploadDateDoesNotSatisfyAnApplicationWindow() {
        var a=BudgetMaterialSplitAnalysis.analyze(List.of(row("甲","电子","30000",null,null),row("乙","电子","20000",null,null)),List.of(rule("50000")));
        assertThat(a.findings()).hasSize(1);assertThat(a.findings().get(0).riskLevel()).isEqualTo("WARNING");
        assertThat(a.findings().get(0).explanation()).contains("未确认完整申报日期");
    }
    @Test void snapshotRetainsRuleAndFactsAndLegacyRecordsAreNotReinterpreted() throws Exception {
        var json=new ObjectMapper();var rows=List.of(row("甲","电子","30000","2026","甲"),row("乙","电子","20000","2026","甲"));
        var analysis=BudgetMaterialSplitAnalysis.analyze(rows,List.of(rule("50000")));
        var result=new Result(UUID.randomUUID(),"合成账号","2026-10-16T00:00:00Z",rows,List.of(analysis.summary()),analysis);
        assertThat(json.readValue(json.writeValueAsString(result),Result.class)).isEqualTo(result);
        var legacy=new Result(UUID.randomUUID(),"合成账号",result.createdAt(),rows,List.of("旧结果"));
        var tree=json.valueToTree(legacy);((com.fasterxml.jackson.databind.node.ObjectNode)tree).remove("splitAnalysis");
        assertThat(json.treeToValue(tree,Result.class).splitAnalysis()).isNull();
    }
    Candidate withCodes(Candidate row,String projectCode,String subjectCode,List<BudgetUploadedFacts.Evidence> sources) {
        var l=row.line();return new Candidate(row.source(),row.fileName(),row.uploadedAt(),new BudgetUploadedFacts.Line(
                l.project(),projectCode,l.subject(),subjectCode,l.year(),l.department(),l.declaredAmount(),l.approvalAmount(),l.status(),l.opinion(),sources));
    }
    @Test void codedAndNamedCopiesNeverInflateTheAggregate() {
        var code=withCodes(row("编号甲","电子","30000","2026","甲"),"编号甲","S1",List.of(
                new BudgetUploadedFacts.Evidence(UUID.randomUUID(),"合成.xlsx","projectName","项目名称甲","第1行","合成")));
        var named=withCodes(row("项目名称甲","电子","30000.00","2026","甲"),null,null,List.of());
        var a=BudgetMaterialSplitAnalysis.analyze(List.of(code,named),List.of(rule("50000")));
        assertThat(a.findings()).isEmpty();assertThat(a.totalAmount()).isEqualByComparingTo("30000");
        assertThat(a.exclusions()).extracting(BudgetMaterialSplitAnalysis.Exclusion::reasonCode).containsExactly("DUPLICATE");
    }
    @Test void ambiguousNameCannotBecomeAnAdditionalSmallProject() {
        var evidence=List.of(new BudgetUploadedFacts.Evidence(UUID.randomUUID(),"合成.xlsx","projectName","同名项目","第1行","合成"));
        var rows=List.of(withCodes(row("编号甲","电子","10000","2026","甲"),"编号甲",null,evidence),
                withCodes(row("编号乙","电子","10000","2026","甲"),"编号乙",null,evidence),
                withCodes(row("同名项目","电子","30000","2026","甲"),null,null,List.of()));
        var a=BudgetMaterialSplitAnalysis.analyze(rows,List.of(rule("50000")));
        assertThat(a.findings()).isEmpty();assertThat(a.totalAmount()).isEqualByComparingTo("20000");
        assertThat(a.exclusions()).extracting(BudgetMaterialSplitAnalysis.Exclusion::reasonCode).containsExactly("AMBIGUOUS_IDENTITY");
    }
}
