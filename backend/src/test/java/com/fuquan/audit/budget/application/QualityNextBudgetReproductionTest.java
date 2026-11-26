package com.fuquan.audit.budget.application;

import com.fuquan.audit.common.material.application.port.*;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Baseline defects, with model omissions isolated from exact source handling. */
class QualityNextBudgetReproductionTest {
    final MaterialFieldAiPort ai=mock(MaterialFieldAiPort.class);
    final BudgetUploadedMaterialReader reader=new BudgetUploadedMaterialReader(null,ai,null);
    QualityNextBudgetReproductionTest(){when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(),"synthetic","test"));}
    DocumentTextPort.Result text(Segment... segments){return new DocumentTextPort.Result(List.of(segments),"TEXT","a".repeat(64));}
    Map<UUID,BudgetUploadedFacts.FileResult> review(List<BudgetUploadedFacts.Input> inputs) {
        var service=new BudgetBatchReviewService((MaterialExtractionLedger)null,
                (com.fuquan.audit.budget.application.ai.BudgetBatchReviewPort)null,new com.fasterxml.jackson.databind.ObjectMapper());
        return service.review(UUID.randomUUID(),inputs,new BudgetUploadedMaterialAnalyzer().analyzeBatch(inputs));
    }

    @Test void exactUnitRemarkDoesNotReextractACompletelyReadTable() throws Exception {
        var snapshot=reader.extract(text(
                new Segment("excel/sheet:1/row:1","序号 | 一级名称 | 二级名称 | 三级名称 | 申报金额 | 第三方审定金额 | 下达金额 | 备注"),
                new Segment("excel/sheet:1/row:2","1 | 办公保障 | 设备购置 | 应急设备 | 150000 | 145000 | 140000 | 单位：元")),"unit-remark");
        assertThat(snapshot.rows()).isNotEmpty();
        verify(ai,never()).extract(any(),any(),any(),any());
    }

    @Test void explicitInlineItemsSurviveAnEmptyOptionalExtraction() throws Exception {
        String source="项目名称：云桥办公保障预算项目\n本项目申请预算合计180000元，用于应急设备购置150000元及接待餐费30000元。";
        var snapshot=reader.extract(text(new Segment("text/body",source)),"inline");
        var result=new BudgetUploadedMaterialAnalyzer().analyzeBatch(List.of(new BudgetUploadedFacts.Input(UUID.randomUUID(),"申请.txt","PARSED",snapshot)));
        assertThat(result.values().stream().flatMap(r->r.lines().stream())).extracting(BudgetUploadedFacts.Line::subject)
                .contains("应急设备购置","接待餐费");
        assertThat(BudgetBatchAmounts.totals(List.of(new BudgetUploadedFacts.Input(UUID.randomUUID(),"申请.txt","PARSED",snapshot))))
                .anySatisfy(t->{assertThat(t.role()).isEqualTo("declaredTotalAmount");assertThat(t.amount()).isEqualByComparingTo("180000");});
    }

    @Test void knownProjectAmountMustNotBeDescribedAsNoAmount() throws Exception {
        var snapshot=reader.extract(text(new Segment("text/body","项目名称：云桥办公保障预算项目\n项目预算金额：180000元")),"known-total");
        var results=review(List.of(new BudgetUploadedFacts.Input(UUID.randomUUID(),"预算说明.txt","PARSED",snapshot)));
        assertThat(results.values()).allSatisfy(r->{
            assertThat(r.totals()).anySatisfy(t->assertThat(t.amount()).isEqualByComparingTo("180000"));
            assertThat(r.summary()).doesNotContain("未找到可确认的预算科目或金额信息");
        });
    }

    @Test void explicitReplacementApprovalMustBeAProjectTotalNotAnAnonymousLine() throws Exception {
        String source="项目名称：云桥办公保障预算项目\n现书面批准将本项目下达预算从160000元调整为180000元。\n本调整替代前次下达金额，其他用途和实施要求不变。";
        var snapshot=reader.extract(text(new Segment("text/body",source)),"adjustment");
        var results=review(List.of(new BudgetUploadedFacts.Input(UUID.randomUUID(),"调整批复.txt","PARSED",snapshot)));
        assertThat(results.values()).allSatisfy(r->assertThat(r.totals()).anySatisfy(t->{
            assertThat(t.role()).isEqualTo("approvedTotalAmount");assertThat(t.amount()).isEqualByComparingTo("180000");
        }));
    }

    BudgetUploadedFacts.Input input(String name,String source) throws Exception {
        return new BudgetUploadedFacts.Input(UUID.randomUUID(),name,"PARSED",reader.extract(text(new Segment("text/body",source)),name));
    }
    @Test void explicitReplacementSupersedesOnlyTheProvedPreviousTotal() throws Exception {
        var original=input("原批复.txt","项目名称：云桥办公保障预算项目\n批复总额：160000元");
        var revision=input("调整.txt","项目名称：云桥办公保障预算项目\n现书面批准将本项目下达预算从160000元调整为180000元。\n本调整替代前次下达金额。");
        var totals=BudgetBatchAmounts.totals(List.of(original,revision));
        assertThat(totals).filteredOn(t->t.role().equals("approvedTotalAmount"))
                .singleElement().satisfies(t->assertThat(t.amount()).isEqualByComparingTo("180000"));
        assertThat(original.snapshot().rows().stream().flatMap(r->r.facts().stream()))
                .anySatisfy(f->{assertThat(f.field()).isEqualTo("approvedTotalAmount");assertThat(f.value()).contains("160000");});
    }
    @Test void proposedOrNegatedAdjustmentCannotOverrideCurrentApproval() throws Exception {
        for(String wording:List.of("拟将本项目下达预算从160000元调整为180000元。","现将本项目下达预算从160000元调整为180000元，尚未获批。","不同意将本项目下达预算从160000元调整为180000元。")) {
            var source=input("调整申请.txt","项目名称：云桥办公保障预算项目\n"+wording);
            assertThat(source.snapshot().rows().stream().flatMap(r->r.facts().stream()))
                    .noneMatch(f->f.field().equals("approvedTotalAmount"));
        }
    }
    @Test void competingRevisionsAndOtherProjectMustRemainUnresolved() throws Exception {
        var original=input("原批复.txt","项目名称：云桥办公保障预算项目\n批复总额：160000元");
        var a=input("调整A.txt","项目名称：云桥办公保障预算项目\n批准将本项目下达预算从160000元调整为180000元。");
        var b=input("调整B.txt","项目名称：云桥办公保障预算项目\n批准将本项目下达预算从160000元调整为190000元。");
        assertThat(BudgetBatchAmounts.totals(List.of(original,a,b))).hasSize(3);
        var other=input("其他项目.txt","项目名称：北山道路改造项目\n批准将本项目下达预算从160000元调整为180000元。");
        assertThat(BudgetBatchAmounts.totals(List.of(original,other))).hasSize(2);
    }
    @Test void revisionCyclesAndDifferentFiscalYearsCannotOverrideCurrentApproval() throws Exception {
        var original=input("原批复.txt","项目名称：云桥办公保障预算项目\n申报年度：2027年\n批复总额：160000元");
        var nextYear=input("跨年调整.txt","项目名称：云桥办公保障预算项目\n申报年度：2028年\n批准将本项目下达预算从160000元调整为180000元。");
        assertThat(BudgetBatchAmounts.totals(List.of(original,nextYear))).hasSize(2);
        var a=input("A.txt","项目名称：云桥办公保障预算项目\n批准将本项目下达预算从160000元调整为180000元。");
        var b=input("B.txt","项目名称：云桥办公保障预算项目\n批准将本项目下达预算从180000元调整为160000元。");
        assertThat(BudgetBatchAmounts.totals(List.of(a,b))).hasSize(2);
    }
    @Test void sameProjectApprovalWithoutPrintedYearCanUseTheOnlyExplicitFiscalContext() throws Exception {
        var request=input("申请.txt","项目名称：云桥办公保障预算项目\n申报年度：2027年\n申报合计：180000元");
        var approval=input("批复.txt","项目名称：云桥办公保障预算项目\n批复总额：160000元");
        assertThat(BudgetBatchAmounts.checks(BudgetBatchAmounts.totals(List.of(request,approval))))
                .anySatisfy(c->{assertThat(c.code()).isEqualTo("TOTAL_VS_APPROVAL");assertThat(c.explanation()).contains("20000");});
        var otherYear=input("其他年度.txt","项目名称：云桥办公保障预算项目\n申报年度：2028年\n申报合计：190000元");
        assertThat(BudgetBatchAmounts.checks(BudgetBatchAmounts.totals(List.of(request,approval,otherYear))))
                .noneMatch(c->c.code().equals("TOTAL_VS_APPROVAL"));
    }
    @Test void totalContextUsesTheSameConservativeNameNormalizationAsExpenseRows() throws Exception {
        var request=input("申请.txt","项目名称：云桥办公保障预算项目的\n申报年度：2027年\n申报合计：180000元");
        var approval=input("批复.txt","项目名称：云桥办公保障预算项目\n批复总额：160000元");
        assertThat(BudgetBatchAmounts.checks(BudgetBatchAmounts.totals(List.of(request,approval))))
                .anyMatch(c->c.code().equals("TOTAL_VS_APPROVAL"));
    }
    @Test void optionalProseExtractionDoesNotDuplicateExactNativeItemsOrCreateAnOrphanAdjustment() throws Exception {
        String itemLine="本项目申请预算合计180000元，用于应急设备购置150000元及接待餐费30000元。";
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(
                new Row("model1",List.of(new Fact("subjectName","应急设备购置","text/body","应急设备购置150000元"),new Fact("declaredAmount","150000元","text/body","应急设备购置150000元"))),
                new Row("model2",List.of(new Fact("subjectName","接待餐费","text/body","接待餐费30000元"),new Fact("declaredAmount","30000元","text/body","接待餐费30000元")))) ,"test","v1"));
        var material=input("请示.txt","项目名称：云桥办公保障预算项目\n"+itemLine);
        var lines=new BudgetUploadedMaterialAnalyzer().analyzeBatch(List.of(material)).values().stream().flatMap(r->r.lines().stream()).toList();
        assertThat(lines).hasSize(2);
        String change="批准将本项目下达预算从160000元调整为180000元。";
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(new Row("model",List.of(new Fact("approvalAmount","180000元","text/body",change)))),"test","v1"));
        var adjusted=input("调整.txt","项目名称：云桥办公保障预算项目\n"+change);
        assertThat(new BudgetUploadedMaterialAnalyzer().analyzeBatch(List.of(adjusted)).values())
                .allSatisfy(r->assertThat(r.lines()).isEmpty());
    }
    @Test void annualUnitNoticeCannotBecomeAProjectExpenseOrTreatItsUnitAsASubject() throws Exception {
        String source="2027年度财政支出预算通知书\n单位：上海市公安局警务保障处\n本单位年度财政支出预算48000000元。\n其中公用经费30000000元，专项经费18000000元。\n本通知为单位年度总预算，不代表本批项目的批复金额。";
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(new Row("incorrect",List.of(
                new Fact("subjectName","上海市公安局警务保障处","text/body","单位：上海市公安局警务保障处"),
                new Fact("projectBudgetAmount","48000000元","text/body","本单位年度财政支出预算48000000元。"),
                new Fact("declaredAmount","30000000元","text/body","其中公用经费30000000元，专项经费18000000元。"),
                new Fact("declaredAmount","18000000元","text/body","其中公用经费30000000元，专项经费18000000元。")))) ,"test","v1"));
        var notice=input("通知.txt",source);
        assertThat(new BudgetUploadedMaterialAnalyzer().analyzeBatch(List.of(notice)).values()).allSatisfy(r->assertThat(r.lines()).isEmpty());
        assertThat(BudgetBatchAmounts.totals(List.of(notice))).isEmpty();
        assertThat(notice.snapshot().rows().stream().flatMap(r->r.facts().stream()))
                .anyMatch(f->f.field().equals("projectBudgetAmount")&&f.subjectScope().equals("GENERAL"));
    }
    @Test void operativeAnnualNoticeHeadingDoesNotTurnUnitComponentsIntoProjectExpenses() throws Exception {
        String source="关于下达年度财政支出预算的通知\n财政预算通知〔2027〕18号\n现下达你单位2027年度财政支出预算86000000元，其中公用经费26000000元、专项经费60000000元。\n本通知系主管单位年度财政总盘子通知，不列具体项目预算，不替代单个项目下达额度。";
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(new Row("annual-part",List.of(
            new Fact("projectName","年度财政支出预算","text/body","关于下达年度财政支出预算的通知"),
            new Fact("spendingPurpose","专项经费","text/body","专项经费60000000元"),
            new Fact("declaredAmount","60000000元","text/body","专项经费60000000元")))) ,"m","v"));
        var notice=input("年度通知.pdf",source);
        assertThat(new BudgetUploadedMaterialAnalyzer().analyzeBatch(List.of(notice)).get(notice.taskId()).lines()).isEmpty();
        assertThat(notice.snapshot().segments()).singleElement().satisfies(s->assertThat(s.text()).isEqualTo(source));
    }
    @Test void annualCoverMustNotHideAnExplicitProjectAllocationTable() throws Exception {
        String source="2027年度财政支出预算通知书\n本单位年度财政支出预算48000000元。\n项目名称：云桥办公保障预算项目\n预算科目：设备购置；申报金额：180000元";
        var material=input("年度通知.txt",source);
        assertThat(new BudgetUploadedMaterialAnalyzer().analyzeBatch(List.of(material)).values().stream().flatMap(r->r.lines().stream()))
                .anySatisfy(l->{assertThat(l.subject()).isEqualTo("设备购置");assertThat(l.declaredAmount()).isEqualByComparingTo("180000");});
        assertThat(BudgetSourceScope.wrongSubjectRole(new Fact("subjectName","某单位","p1","单位：某单位"))).isTrue();
        assertThat(BudgetSourceScope.wrongSubjectRole(new Fact("subjectName","某单位","p1","预算科目：某单位"))).isFalse();
    }
}
