package com.fuquan.audit.budget.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fuquan.audit.common.material.application.port.*;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.*;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;

class BudgetUploadedMaterialReaderTest {
    @Test void referenceToProjectAllocationListIsNotAnotherBudgetSubjectEvenWithACroppedModelQuote() throws Exception {
        String name="望川公共设施维护项目";
        String reference=name+"的资金安排按项目清单中的下达金额列执行。";
        for(String quote:List.of(reference,name)){
            when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(new Row("model",List.of(
                    new Fact("subjectName",name,"p1",quote)))),"m","v"));
            var snapshot=reader.extract(text(new Segment("p1","年度财政支出预算通知书\n申报单位：综合保障处\n"+reference)),"allocation-reference");
            assertThat(snapshot.rows().stream().flatMap(r->r.facts().stream())).noneMatch(f->f.field().equals("subjectName"));
            assertThat(snapshot.rows().stream().flatMap(r->r.facts().stream())).anyMatch(f->f.field().equals("departmentName")&&f.value().equals("综合保障处"));
        }
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(),"m","v"));
        var actual=reader.extract(text(new Segment("p1","预算科目："+name+"；申报金额：120000元；批复金额：120000元")),"actual-subject");
        assertThat(actual.rows().stream().flatMap(r->r.facts().stream())).anyMatch(f->f.field().equals("subjectName")&&f.value().equals(name));
    }
    @Test void purposeFactsDoNotRejoinAlreadyGroundedProseAmountsIntoAConflictingRow() throws Exception {
        String locator="docx/body/node:5/part:5";
        String total="本项目申请预算合计120000元", equipment="用于设备改造90000元", meal="和接待宴请30000元";
        var modelRow=new Row("model",List.of(
                new Fact("declaredTotalAmount","120000元",locator,total,"CURRENT_PROJECT","TOTAL"),
                new Fact("spendingPurpose","设备改造",locator,equipment),
                new Fact("spendingPurpose","接待宴请",locator,meal),
                new Fact("declaredAmount","90000",locator,equipment,"CURRENT_PROJECT","TOTAL"),
                new Fact("declaredAmount","30000",locator,meal,"UNKNOWN","UNIT")));
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(modelRow),"m","v"));
        var snapshot=reader.extract(text(new Segment("docx/body/node:2/part:2","项目名称：望川公共设施维护项目"),
                new Segment(locator,total+"，"+equipment+meal+"。")),"purpose");
        var task=new BudgetUploadedFacts.Input(UUID.randomUUID(),"预算申请.docx","PARSED",snapshot);
        var lines=new BudgetUploadedMaterialAnalyzer().analyzeBatch(List.of(task)).get(task.taskId()).lines();
        assertThat(lines).extracting(BudgetUploadedFacts.Line::subject).containsExactlyInAnyOrder("设备改造","接待宴请");
        assertThat(lines).noneMatch(line->line.status().equals("CONFLICT"));
        assertThat(snapshot.rows().stream().flatMap(row->row.facts().stream()).filter(fact->fact.field().equals("spendingPurpose"))).hasSize(2);
    }
    @Test void separatelyQuotedInlineSubjectsDoNotBecomeOneConflictingBudgetRow() throws Exception {
        String source="项目名称：松林经费项目\r\n本项目申报预算合计180000元，用于设备购置150000元、接待餐费30000元。\r\n申请支出用途：拟支付接待餐费。";
        String equipment="用于设备购置150000元",meal="接待餐费30000元";
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(new Row("model",List.of(
                new Fact("subjectName","设备购置","text/body",equipment),new Fact("declaredAmount","150000元","text/body",equipment),
                new Fact("subjectName","接待餐费","text/body",meal),new Fact("declaredAmount","30000元","text/body",meal)))),"m","v"));
        var snapshot=reader.extract(text(new Segment("text/body",source)),"inline");
        var results=new BudgetUploadedMaterialAnalyzer().analyzeBatch(List.of(new BudgetUploadedFacts.Input(UUID.randomUUID(),"申请.txt","PARSED",snapshot)));
        var lines=results.values().stream().flatMap(r->r.lines().stream()).toList();
        assertThat(lines).extracting(BudgetUploadedFacts.Line::subject).containsExactlyInAnyOrder("设备购置","接待餐费");
        assertThat(lines).noneMatch(l->l.status().equals("CONFLICT"));
    }
    @Test void physicalLineQuotesKeepActualCrLfCrAndUnicodeBreakWidths() throws Exception {
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(),"m","v"));
        for(String newline:List.of("\n","\r\n","\r","\u2028")) {
            String source=String.join(newline,"预算申报说明","项目名称：松林保障经费项目","申报单位：综合管理处","申报年度：2027年","项目预算金额：180000元");
            var snapshot=reader.extract(text(new Segment("text/body",source)),"line-width");
            var facts=snapshot.rows().stream().flatMap(r->r.facts().stream()).toList();
            assertThat(facts).extracting(Fact::field).contains("projectName","departmentName","budgetYear","projectBudgetAmount");
            for(var fact:facts) {
                assertThat(fact.excerpt()).contains(fact.value());
                var located=BudgetSourceText.locate(new BudgetUploadedFacts.Evidence(UUID.randomUUID(),"说明.txt",fact.field(),fact.value(),fact.locator(),fact.excerpt()),snapshot);
                assertThat(located.start()).isNotNull();
                assertThat(source.substring(located.start(),located.end())).isEqualTo(fact.excerpt());
            }
        }
    }
    @Test void aSharedFiscalClassificationDoesNotMergeDifferentBudgetItems() throws Exception {
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(),"m","v"));
        var snapshot=reader.extract(text(
            new Segment("excel/sheet:1/row:1","一级名称 | 二级名称 | 三级名称 | 支出功能分类代码 | 申报金额 | 第三方审定金额 | 下达金额 | 单位"),
            new Segment("excel/sheet:1/row:2","设施保障 | 设备更新 | 终端更新 | F02 | 100 | 110 | 100 | 元"),
            new Segment("excel/sheet:1/row:3","设施保障 | 日常维护 | 网络维护 | F02 | 200 | 210 | 190 | 元")),"template");
        var input=new BudgetUploadedFacts.Input(UUID.randomUUID(),"项目明细.xlsx","PARSED",snapshot);
        var lines=new BudgetUploadedMaterialAnalyzer().analyzeBatch(List.of(input)).get(input.taskId()).lines();
        assertThat(lines).hasSize(2).anySatisfy(line->{assertThat(line.subject()).isEqualTo("终端更新");assertThat(line.status()).isEqualTo("WITHIN_APPROVAL");})
            .anySatisfy(line->{assertThat(line.subject()).isEqualTo("网络维护");assertThat(line.status()).isEqualTo("OVER_APPROVAL");assertThat(line.approvalAmount()).isEqualByComparingTo("190");});
        assertThat(lines).allSatisfy(line->assertThat(line.subjectCode()).isNull());
    }
    @Test void fieldOfficeBudgetTemplateKeepsSubjectTiersProcurementNumberAndApprovalRolesSeparate() throws Exception {
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(),"m","v"));
        var snapshot=reader.extract(text(
                new Segment("excel/sheet:1/row:1","序号 | 一级名称 | 二级名称 | 三级名称 | 政府采购编号 | 支出功能分类代码 | 支出功能分类名称 | 申报金额 | 第三方审定金额 | 下达金额 | 备注 | 单位"),
                new Segment("excel/sheet:1/row:2","1 | 信息化建设 | 设备购置 | 终端更新 | CG-2026-01 | F02 | 信息设备支出 | 2200000 | 2100000 | 2050000 | 本项目支出 | 元")),"template");
        var facts=snapshot.rows().stream().flatMap(row->row.facts().stream()).toList();
        assertThat(facts).filteredOn(f->f.field().equals("subjectName")).extracting(Fact::value)
                .containsExactly("终端更新");
        assertThat(facts).anySatisfy(f->{assertThat(f.field()).isEqualTo("subjectLevel1Name");assertThat(f.value()).isEqualTo("信息化建设");});
        assertThat(facts).anySatisfy(f->{assertThat(f.field()).isEqualTo("subjectLevel2Name");assertThat(f.value()).isEqualTo("设备购置");});
        assertThat(facts).anySatisfy(f->{assertThat(f.field()).isEqualTo("functionalClassificationCode");assertThat(f.value()).isEqualTo("F02");});
        assertThat(facts).anySatisfy(f->{assertThat(f.field()).isEqualTo("applicationNo");assertThat(f.value()).isEqualTo("CG-2026-01");});
        assertThat(facts).noneMatch(f->f.field().equals("projectCode")&&f.value().equals("CG-2026-01"));
        assertThat(facts).anySatisfy(f->{assertThat(f.field()).isEqualTo("declaredAmount");assertThat(f.value()).isEqualTo("2200000元");});
        assertThat(facts).filteredOn(f->f.field().equals("approvalAmount")).extracting(Fact::value)
                .containsExactly("2050000元");
        assertThat(facts).anySatisfy(f->{assertThat(f.field()).isEqualTo("thirdPartyReviewAmount");assertThat(f.value()).isEqualTo("2100000元");});
        assertThat(snapshot.issues()).isEmpty();
        verifyNoInteractions(ai);
        var input=new BudgetUploadedFacts.Input(UUID.randomUUID(),"项目明细.xlsx","PARSED",snapshot);
        assertThat(new BudgetUploadedMaterialAnalyzer().analyzeBatch(List.of(input)).get(input.taskId()).lines())
                .singleElement().satisfies(line->{assertThat(line.subject()).isEqualTo("终端更新");assertThat(line.status()).isEqualTo("OVER_APPROVAL");
                    assertThat(line.declaredAmount()).isEqualByComparingTo("2200000");assertThat(line.approvalAmount()).isEqualByComparingTo("2050000");});
    }

    @Test void adjacentApplicantAndDepartmentLabelsKeepBothExactValuesWithoutReadingPreparerAsApplicant() throws Exception {
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(),"m","v"));
        var snapshot=reader.extract(text(new Segment("p1","申报单位：明远市事务局综合保障处　部门：综合保障处\n经办部门：财务科\n项目名称：设施维护项目\n预算科目：设备；申报金额：100元；批复金额：100元")),"id");
        assertThat(snapshot.rows().stream().flatMap(r->r.facts().stream()).filter(f->f.field().equals("departmentName")))
            .extracting(Fact::value).containsExactly("明远市事务局综合保障处","综合保障处");
        var input=new BudgetUploadedFacts.Input(UUID.randomUUID(),"预算.pdf","PARSED",snapshot);
        assertThat(new BudgetUploadedMaterialAnalyzer().analyzeBatch(List.of(input)).get(input.taskId()).lines())
            .singleElement().satisfies(l->{assertThat(l.status()).isEqualTo("WITHIN_APPROVAL");assertThat(l.project()).isEqualTo("设施维护项目");});
    }
    @Test void croppedModelQuoteCannotTurnBudgetSubjectIntoProjectName() throws Exception {
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(new Row("model",List.of(
            new Fact("projectName","设备更新","p1","设备更新")))),"m","v"));
        var snapshot=reader.extract(text(new Segment("p1","预算科目：设备更新\n本项用于办公。")),"id");
        assertThat(snapshot.rows().stream().flatMap(r->r.facts().stream())).noneMatch(f->f.field().equals("projectName"));
        assertThat(snapshot.rows().stream().flatMap(r->r.facts().stream())).anyMatch(f->f.field().equals("subjectName")&&f.value().equals("设备更新"));
    }

    @Test void wordTablesInheritTheirOwnHeaderUnitsAndKeepPhysicalEvidence() throws Exception {
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(new Row("model",List.of(
                new Fact("approvalAmount","110.00万元","docx/body/node:7/part:7","110.00")))),"m","v",
                List.of(new ExtractionIssue("approvalAmount","docx/body/node:7/part:7","EVIDENCE_UNVERIFIED"))));
        var document=text(
                new Segment("docx/body/node:1/part:1","项目编号：MY-2026-FW-017    申报年度：2026年"),
                new Segment("docx/body/node:2/part:2","序号\t支出项目\t测算依据\t申报金额\u2028万元"),
                new Segment("docx/body/node:3/part:3","1\t驻场运维服务\t年度服务\t120.00"),
                new Segment("docx/body/node:4/part:4","\t合计\t\t198.00"),
                new Segment("docx/body/node:5/part:5","项目预算批复"),
                new Segment("docx/body/node:6/part:6","序号\t支出项目\t批复金额\u2028万元\t支出范围"),
                new Segment("docx/body/node:7/part:7","1\t驻场运维服务\t110.00\t日常服务"),
                new Segment("docx/body/node:8/part:8","\t合计\t180.00\t"));
        var tables=new ArrayList<MaterialParsePort.Table>();
        for(int[] bounds:List.of(new int[]{1,3},new int[]{5,7})) {
            var cells=new ArrayList<MaterialParsePort.Cell>();
            for(int r=bounds[0];r<=bounds[1];r++) {
                var values=document.segments().get(r).text().split("\\t",-1);
                for(int c=0;c<values.length;c++)cells.add(new MaterialParsePort.Cell("cell",r-bounds[0]+1,c+1,values[c],"TEXT",null));
            }
            tables.add(new MaterialParsePort.Table("docx/table:"+tables.size(),cells,false));
        }
        var snapshot=reader.extract(new DocumentTextPort.Result(document.segments(),"OFFICE_TEXT","sha",tables,List.of()),"id");
        assertThat(snapshot.issues()).isEmpty();
        var facts=snapshot.rows().stream().flatMap(r->r.facts().stream()).toList();
        assertThat(facts).anyMatch(f->f.field().equals("projectCode")&&f.value().equals("MY-2026-FW-017"));
        assertThat(facts).anyMatch(f->f.field().equals("declaredAmount")&&f.value().equals("120.00万元")&&f.excerpt().contains("120.00"));
        assertThat(facts).anyMatch(f->f.field().equals("approvalAmount")&&f.value().equals("110.00万元"));
        assertThat(facts).anyMatch(f->f.field().equals("approvedTotalAmount")&&f.value().equals("180.00万元"));
        var input=new BudgetUploadedFacts.Input(UUID.randomUUID(),"预算.docx","PARSED",snapshot);
        var totals=BudgetBatchAmounts.totals(List.of(input));
        assertThat(BudgetBatchAmounts.checks(totals)).anyMatch(a->a.code().equals("TOTAL_VS_APPROVAL")&&a.explanation().contains("180000"));
        var wrongRole=new ExtractionIssue("approvalAmount","docx/body/node:3/part:3","EVIDENCE_UNVERIFIED");
        var wrongLocation=new ExtractionIssue("approvalAmount","unrelated/page:1","EVIDENCE_UNVERIFIED");
        var unresolved=new ExtractionIssue("approvalAmount","docx/body/node:7/part:7","ROW_UNRESOLVED");
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(),"m","v",List.of(wrongRole,wrongLocation,unresolved)));
        var guarded=reader.extract(new DocumentTextPort.Result(document.segments(),"OFFICE_TEXT","sha",tables,List.of()),"id");
        assertThat(guarded.issues()).containsExactly(wrongRole,wrongLocation,unresolved);
    }
    @Test void serviceDatesAndApprovalDepartmentCannotPolluteBudgetIdentity() throws Exception {
        String source="申报部门：综合保障部    项目类别：服务类\n服务期限2026年6月至2027年5月\n经办部门：信息管理科";
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(new Row("model",List.of(
                new Fact("budgetYear","2026年6月至2027年5月","p1","服务期限2026年6月至2027年5月"),
                new Fact("departmentName","信息管理科","p1","经办部门：信息管理科")))),"m","v"));
        var facts=reader.extract(text(new Segment("p1",source)),"id").rows().stream().flatMap(r->r.facts().stream()).toList();
        assertThat(facts).filteredOn(f->f.field().equals("departmentName")).extracting(Fact::value).containsExactly("综合保障部");
        assertThat(facts).noneMatch(f->f.field().equals("budgetYear"));
    }
    @Test void aPersonCandidateCannotInvalidateTheExplicitDepartmentAndBudget() throws Exception {
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(new Row("model",List.of(
                new Fact("departmentName","合成经办人","excel/sheet:1/row:4","申请人：合成经办人")))),"m","v"));
        var snapshot=reader.extract(text(new Segment("excel/sheet:1/row:1","项目名称：终端更新项目"),
                new Segment("excel/sheet:1/row:2","申请部门：采购管理部"),
                new Segment("excel/sheet:1/row:3","科目名称：设备；申报金额：31200元；批复参考金额：32760元"),
                new Segment("excel/sheet:1/row:4","申请人：合成经办人")),"id");
        assertThat(snapshot.rows().stream().flatMap(r->r.facts().stream())).noneMatch(f->f.field().equals("departmentName")&&f.value().equals("合成经办人"));
        var input=new BudgetUploadedFacts.Input(UUID.randomUUID(),"预算.xls","PARSED",snapshot);
        assertThat(new BudgetUploadedMaterialAnalyzer().analyze(List.of(input)).get(input.taskId()).lines()).anySatisfy(line->{
            assertThat(line.declaredAmount()).isEqualByComparingTo("31200");assertThat(line.approvalAmount()).isEqualByComparingTo("32760");
            assertThat(line.status()).isEqualTo("WITHIN_APPROVAL");
        });
    }
    @Test void rotatedScanFinancialAndParenthesizedArabicAmountsRemainComparable() throws Exception {
        String declared="人民币壹拾贰万元整（120000.00元）",approved="人民币壹拾万元整（100000.00元）";
        String source="项目名称：照明改造\n预算科目：办公设施维护\n本次申报"+declared+"。\n该项目批复"+approved+"。";
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(new Row("model",List.of(
            new Fact("declaredAmount",declared,"p1","本次申报"+declared),
            new Fact("approvalAmount",approved,"p1","该项目批复"+approved)))),"m","v"));
        var snapshot=reader.extract(text(new Segment("p1",source)),"id");
        assertThat(snapshot.issues()).isEmpty();
        var input=new BudgetUploadedFacts.Input(UUID.randomUUID(),"scan.png","PARSED",snapshot);
        assertThat(new BudgetUploadedMaterialAnalyzer().analyze(List.of(input)).get(input.taskId()).lines())
            .singleElement().satisfies(line->{assertThat(line.status()).isEqualTo("OVER_APPROVAL");
                assertThat(line.declaredAmount()).isEqualByComparingTo("120000");
                assertThat(line.approvalAmount()).isEqualByComparingTo("100000");});
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(new Row("model",List.of(
            new Fact("approvalAmount",declared,"p1","本次申报"+declared)))),"m","v"));
        var invalid=reader.extract(text(new Segment("p1",source)),"id");
        assertThat(invalid.issues()).extracting(ExtractionIssue::reasonCode).contains("AMOUNT_ROLE_UNVERIFIED");
        assertThat(invalid.rows().stream().flatMap(r->r.facts().stream())).noneMatch(f->f.field().endsWith("Amount"));
    }
    @Test void adjacentWordParagraphsGroupExplicitFieldsButHeadersAndNodeGapsCannotBridge() throws Exception {
        var result=reader.extract(text(new Segment("docx/body/node:1/part:1","科目名称：照明"),
            new Segment("docx/body/node:2/part:2","申报金额：15000元"),new Segment("docx/body/node:3/part:3","批复金额：12000元"),
            new Segment("docx/body/node:4/part:4","科目名称：绿化"),new Segment("docx/body/node:5/part:5","申报金额：3000元")),"id");
        assertThat(result.rows()).hasSize(2);
        var input=new BudgetUploadedFacts.Input(UUID.randomUUID(),"申请.docx","PARSED",result);
        assertThat(new BudgetUploadedMaterialAnalyzer().analyze(List.of(input)).get(input.taskId()).lines()).anySatisfy(l->{
            assertThat(l.subject()).isEqualTo("照明");assertThat(l.status()).isEqualTo("OVER_APPROVAL");});
        for(String locator:List.of("docx/header:1/part:2","docx/body/node:3/part:2","pdf/page:2/text/part:2")) {
            var separate=reader.extract(text(new Segment("docx/body/node:1/part:1","科目名称：照明"),new Segment(locator,"申报金额：15000元")),"id");
            assertThat(separate.rows()).hasSize(2);
        }
    }
    @Test void ambiguousOcrIdentifierIsReportedWithoutChangingEitherSourceCode() throws Exception {
        var result=reader.extract(text(new Segment("image/frame:1/part:1","项目编号：P-O1\n科目名称：设备；申报金额：100元；项目编号：P-01")),"id");
        assertThat(result.issues()).extracting(ExtractionIssue::reasonCode).containsExactly("OCR_IDENTITY_UNCERTAIN");
        assertThat(result.rows().stream().flatMap(r->r.facts().stream()).filter(f->f.field().equals("projectCode")).map(Fact::value)).containsExactly("P-O1","P-01");
        var inputs=List.of(new BudgetUploadedFacts.Input(UUID.randomUUID(),"扫描.png","PARSED",result));
        assertThat(new BudgetUploadedMaterialAnalyzer().analyze(inputs).values()).allSatisfy(f->{assertThat(f.summary()).contains("字母 O 与数字 0");assertThat(f.lines()).noneMatch(l->l.status().equals("WITHIN_APPROVAL"));});
    }
    @Test void ocrOZeroAmbiguityDropsOnlyTheCodeAndKeepsAmountUnderUniqueProjectName() throws Exception {
        var snapshot=reader.extract(text(new Segment("image/frame:1/part:1","项目名称：设备更新\n项目编号：P-O1\n科目名称：设备；申报金额：31200元；项目编号：P-01")),"id");
        var facts=snapshot.rows().stream().flatMap(r->r.facts().stream()).toList();
        assertThat(snapshot.issues()).extracting(ExtractionIssue::reasonCode).contains("OCR_IDENTITY_UNCERTAIN");
        assertThat(facts).noneMatch(f->f.field().equals("projectCode"));
        assertThat(facts).anySatisfy(f->{assertThat(f.field()).isEqualTo("declaredAmount");assertThat(f.value()).isEqualTo("31200元");});
        var input=new BudgetUploadedFacts.Input(UUID.randomUUID(),"扫描.png","PARSED",snapshot);
        var line=new BudgetUploadedMaterialAnalyzer().analyze(List.of(input)).get(input.taskId()).lines().stream()
            .filter(l->l.declaredAmount()!=null).findFirst().orElseThrow();
        assertThat(line.project()).isEqualTo("设备更新");assertThat(line.projectCode()).isNull();
        assertThat(line.declaredAmount()).isEqualByComparingTo("31200");assertThat(line.status()).isEqualTo("INSUFFICIENT");
    }
    @Test void repeatedPdfPagesAndNativeTextOcrEchoesAreDeduplicatedButDifferingValuesStayUncertain() throws Exception {
        String text="项目名称：仿真办公设备更新项目\n项目编号：SYN-OFFICE-2026\n科目名称：办公设备；申报金额：31200元";
        var exact=new DocumentTextPort.Result(List.of(new Segment("pdf/page:1/text/part:1",text),
            new Segment("pdf/page:2/ocr/part:2",text),new Segment("pdf/page:3/text/part:3",text),
            new Segment("pdf/page:3/ocr/part:4",text)),"OFFICE_TEXT_OCR","a".repeat(64));
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(),"m","v"));
        var snapshot=reader.extract(exact,"id");
        assertThat(snapshot.issues()).isEmpty();
        assertThat(snapshot.rows().stream().flatMap(r->r.facts().stream()).filter(f->f.field().equals("declaredAmount"))).hasSize(1);
        var conflicting=new DocumentTextPort.Result(List.of(new Segment("pdf/page:1/text/part:1",text),
            new Segment("pdf/page:1/ocr/part:2",text.replace("31200元","31800元"))),"OFFICE_TEXT_OCR","b".repeat(64));
        var conflict=reader.extract(conflicting,"id2");
        assertThat(conflict.rows().stream().flatMap(r->r.facts().stream()).filter(f->f.field().equals("declaredAmount")))
            .extracting(Fact::value).contains("31200元","31800元");
    }
    @Test void adjacentLabelledSubjectAndAmountsRemainOneRecordWithoutLosingPhysicalEvidence() throws Exception {
        var snapshot=reader.extract(text(new Segment("p1","项目编号：P1\n科目名称：照明\n申报金额：15000元\n批复金额：未提供\n\n科目名称：绿化\n申报金额：3000元")),"id");
        verifyNoInteractions(ai);
        assertThat(snapshot.rows()).hasSize(3);
        assertThat(snapshot.rows().get(1).facts()).extracting(Fact::field).containsExactly("subjectName","declaredAmount");
        assertThat(snapshot.rows().get(1).facts()).anySatisfy(f->{assertThat(f.field()).isEqualTo("declaredAmount");assertThat(f.locator()).isEqualTo("p1/line:2");});
        var approved=reader.extract(text(new Segment("p2","项目编号：P1\n科目名称：照明\n批复金额：12000元\n科目名称：消防\n批复金额：5000元")),"id2");
        var d=new BudgetUploadedFacts.Input(UUID.randomUUID(),"申报.txt","PARSED",snapshot);
        var a=new BudgetUploadedFacts.Input(UUID.randomUUID(),"批复.txt","PARSED",approved);
        var results=new BudgetUploadedMaterialAnalyzer().analyze(List.of(d,a));
        assertThat(results.get(d.taskId()).lines()).extracting(BudgetUploadedFacts.Line::status).containsExactly("OVER_APPROVAL","UNAPPROVED_SUBJECT");
        assertThat(results.get(a.taskId()).lines()).extracting(BudgetUploadedFacts.Line::status).containsExactly("OVER_APPROVAL","MISSING_DECLARATION");
    }
    @Test void modelEchoOfExplicitAmountDoesNotCreateASecondUnitlessAmountRow() throws Exception {
        var source=new Segment("text/body/part:1","项目名称：单位换算\n科目名称：排水维护\n申报金额（千元）：12.345\n批复金额（元）：12000");
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(new Row("model",List.of(
            new Fact("subjectName","排水维护",source.locator(),"科目名称：排水维护"),
            new Fact("declaredAmount","12.345",source.locator(),"申报金额（千元）：12.345"),
            new Fact("approvalAmount","12000",source.locator(),"批复金额（元）：12000")))),"m","v"));
        var snapshot=reader.extract(text(source),"id");
        assertThat(snapshot.issues()).isEmpty();
        assertThat(snapshot.rows().stream().flatMap(r->r.facts().stream()).filter(f->f.field().equals("declaredAmount"))).hasSize(1)
            .allSatisfy(f->{assertThat(f.value()).isEqualTo("12.345千元");assertThat(f.locator()).endsWith("/line:2");});
        var input=new BudgetUploadedFacts.Input(UUID.randomUUID(),"单位.xlsx","PARSED",snapshot);
        var lines=new BudgetUploadedMaterialAnalyzer().analyze(List.of(input)).get(input.taskId()).lines();
        assertThat(lines).hasSize(1);assertThat(lines.get(0).declaredAmount()).isEqualByComparingTo("12345");
        assertThat(lines.get(0).approvalAmount()).isEqualByComparingTo("12000");assertThat(lines.get(0).status()).isEqualTo("OVER_APPROVAL");
    }
    @Test void verticalGroupingCannotCrossProseProjectsOrRepeatedExpenseRoles() throws Exception {
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(),"m","v"));
        for(String boundary:List.of("项目编号：Q","另一个事项说明")) {
            var result=reader.extract(text(new Segment("p1","科目名称：设备\n"+boundary+"\n申报金额：100元")),"id");
            assertThat(result.rows()).noneSatisfy(r->assertThat(r.facts()).extracting(Fact::field).contains("subjectName","declaredAmount"));
        }
        var repeated=reader.extract(text(new Segment("p1","科目名称：设备\n申报金额：100元\n申报金额：100元")),"id");
        assertThat(repeated.rows()).hasSize(2);
        assertThat(repeated.rows().stream().flatMap(r->r.facts().stream()).filter(f->f.field().equals("declaredAmount"))).hasSize(2);
    }
    @Test void verticallyReadOcrTableRetainsDistinctVerifiableRowsAndRepeatedSupplierEvidence() throws Exception {
        String source="项目编号：P1 预算年度：2026申报部门：综合部\n科目名称\n申报金额\n31002\n设备购置\n供应商甲\nP1\n168000\n30213\n安装服务\n供应商甲\nP1\n16800";
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(
            new Row("a",List.of(new Fact("subjectCode","31002","p1","31002\n设备购置"),new Fact("subjectName","设备购置","p1","31002\n设备购置"),
                new Fact("supplierName","供应商甲","p1","供应商甲\nP1\n168000"),new Fact("projectCode","P1","p1","供应商甲\nP1\n168000"),new Fact("declaredAmount","168000","p1","P1\n168000"))),
            new Row("b",List.of(new Fact("subjectCode","30213","p1","30213\n安装服务"),new Fact("subjectName","安装服务","p1","30213\n安装服务"),
                new Fact("supplierName","供应商甲","p1","供应商甲\nP1\n16800"),new Fact("projectCode","P1","p1","供应商甲\nP1\n16800"),new Fact("declaredAmount","16800","p1","P1\n16800")))),"m","v7"));
        var snapshot=reader.extract(text(new Segment("p1",source)),"id");
        assertThat(snapshot.issues()).isEmpty();
        var result=new BudgetUploadedMaterialAnalyzer().analyze(List.of(new BudgetUploadedFacts.Input(UUID.randomUUID(),"仿真扫描.pdf","PARSED",snapshot)));
        assertThat(result.values()).allSatisfy(f->{assertThat(f.lines()).hasSize(2);
            assertThat(f.lines()).anySatisfy(l->{assertThat(l.subject()).isEqualTo("设备购置");assertThat(l.declaredAmount()).isEqualByComparingTo("168000");});
            assertThat(f.lines()).anySatisfy(l->{assertThat(l.subject()).isEqualTo("安装服务");assertThat(l.declaredAmount()).isEqualByComparingTo("16800");});
        });
        assertThat(snapshot.rows().stream().flatMap(r->r.facts().stream()).filter(f->f.field().equals("projectCode"))).allSatisfy(f->assertThat(f.value()).isEqualTo("P1"));
    }
    @Test void uniqueSubjectDoesNotAuthorizeStealingAmountFromAnotherSubjectRecord() throws Exception {
        String source="甲项目\n设备\n申请100元\n培训\n批复500元";
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(
            new Row("a",List.of(new Fact("subjectName","设备","p1","设备"),new Fact("declaredAmount","500元","p1","批复500元"))),
            new Row("b",List.of(new Fact("subjectName","培训","p1","培训"),new Fact("approvalAmount","500元","p1","批复500元")))),"m","v7"));
        var snapshot=reader.extract(text(new Segment("p1",source)),"id");
        assertThat(snapshot.rows().stream().flatMap(r->r.facts().stream())).noneMatch(f->f.field().equals("declaredAmount"));
        assertThat(snapshot.issues()).isNotEmpty();
    }
    @Test void croppedAmountKeepsExplicitUnitButCannotBecomeAPrefixOfAnotherNumber() throws Exception {
        String source="办公设备本次申请12万元，批复10万元。";
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(new Row("a",List.of(
            new Fact("subjectName","办公设备","p1","办公设备"),new Fact("declaredAmount","12","p1","12"),
            new Fact("approvalAmount","10","p1","10")))),"m","v7"));
        var snapshot=reader.extract(text(new Segment("p1",source)),"id");
        var result=new BudgetUploadedMaterialAnalyzer().analyze(List.of(new BudgetUploadedFacts.Input(UUID.randomUUID(),"仿真.txt","PARSED",snapshot)));
        assertThat(result.values()).allSatisfy(f->assertThat(f.lines()).anySatisfy(l->{assertThat(l.declaredAmount()).isEqualByComparingTo("120000");assertThat(l.approvalAmount()).isEqualByComparingTo("100000");}));
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(new Row("a",List.of(
            new Fact("declaredAmount","16800","p1","168000元")))),"m","v7"));
        var bad=reader.extract(text(new Segment("p1","本次申请168000元")),"id");
        assertThat(bad.rows()).isEmpty();assertThat(bad.issues()).isNotEmpty();
    }
    @Test void narrativeSentenceTerminatorDoesNotBecomeAConflictingSubjectIdentity() throws Exception {
        String source="项目编号：P1；项目名称：设备更新；科目名称：办公设备。\n"
            +"本次申请人民币壹拾贰万元整，经研究核准人民币壹拾万元整。";
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(
            new Row("heading",List.of(new Fact("subjectName","办公设备","p1","科目名称：办公设备"))),
            new Row("amount",List.of(
                new Fact("declaredAmount","人民币壹拾贰万元整","p1","本次申请人民币壹拾贰万元整"),
                new Fact("approvalAmount","人民币壹拾万元整","p1","经研究核准人民币壹拾万元整")))),"m","v"));
        var snapshot=reader.extract(text(new Segment("p1",source)),"id");
        var result=new BudgetUploadedMaterialAnalyzer().analyze(List.of(new BudgetUploadedFacts.Input(UUID.randomUUID(),"test.txt","PARSED",snapshot)));
        assertThat(result.values()).hasSize(1).allSatisfy(file->assertThat(file.lines()).hasSize(1).allSatisfy(line->{
            assertThat(line.subject()).isEqualTo("办公设备");
            assertThat(line.status()).isEqualTo("OVER_APPROVAL");
            assertThat(line.declaredAmount()).isEqualByComparingTo("120000");
            assertThat(line.approvalAmount()).isEqualByComparingTo("100000");
        }));
    }
    @Test void sentenceBoundariesPreserveDistinctProjectsAndDecimalAmounts() throws Exception {
        var snapshot=reader.extract(text(new Segment("p1","项目名称：项目甲。科目名称：设备。申报金额：100.01元。\n"
            +"项目名称：项目乙。科目名称：设备。批复金额：100.00元。")),"id");
        verifyNoInteractions(ai);
        assertThat(snapshot.rows()).hasSize(2);
        var result=new BudgetUploadedMaterialAnalyzer().analyze(List.of(new BudgetUploadedFacts.Input(UUID.randomUUID(),"test.txt","PARSED",snapshot)));
        assertThat(result.values()).allSatisfy(file->assertThat(file.lines()).hasSize(2).allSatisfy(line->assertThat(line.status()).isEqualTo("INSUFFICIENT")));
        assertThat(snapshot.rows().get(0).facts()).anySatisfy(f->{assertThat(f.field()).isEqualTo("declaredAmount");assertThat(f.value()).isEqualTo("100.01元");});
    }
    @Test void verticalSheetHeadingsDoNotBecomeProjectValuesOrHideOverspending() throws Exception {
        var result=reader.extract(text(new Segment("excel/sheet:1/row:1","项目名称 | 明辉信息平台运维采购项目"),
            new Segment("excel/sheet:1/row:2","申报单位 | 明辉市公共服务中心"),
            new Segment("excel/sheet:1/row:3","预算科目 | 申报金额（元） | 批复金额（元）"),
            new Segment("excel/sheet:1/row:4","信息平台维护费 | 730000 | 700000")),"id");
        assertThat(result.rows().stream().flatMap(r->r.facts().stream()))
            .noneMatch(f->f.field().equals("projectName")&&f.value().equals("申报单位"));
        var input=new BudgetUploadedFacts.Input(UUID.randomUUID(),"预算.xlsx","PARSED",result);
        assertThat(new BudgetUploadedMaterialAnalyzer().analyze(List.of(input)).get(input.taskId()).lines())
            .singleElement().satisfies(line->{assertThat(line.project()).isEqualTo("明辉信息平台运维采购项目");
                assertThat(line.declaredAmount()).isEqualByComparingTo("730000");
                assertThat(line.approvalAmount()).isEqualByComparingTo("700000");
                assertThat(line.status()).isEqualTo("OVER_APPROVAL");});
        verifyNoInteractions(ai);
    }
    @Test void sheetUnitAndAmountHeaderAliasesAreReadWithoutCallingTheModel() throws Exception {
        var result=reader.extract(text(new Segment("excel/sheet:1/row:1","单位：万元"),
            new Segment("excel/sheet:1/row:2","科目名称 | 本次申报金额 | 批复参考额度"),
            new Segment("excel/sheet:1/row:3","设备 | 12 | 10")),"id");
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).facts()).anySatisfy(f->{assertThat(f.field()).isEqualTo("declaredAmount");assertThat(f.value()).isEqualTo("12万元");});
        verifyNoInteractions(ai);
    }
    @Test void unitDoesNotLeakAcrossSheetsAndExplicitColumnUnitWins() throws Exception {
        var result=reader.extract(text(new Segment("excel/sheet:1/row:1","单位：万元"),
            new Segment("excel/sheet:1/row:2","科目名称 | 申报金额（元）"),new Segment("excel/sheet:1/row:3","设备 | 12"),
            new Segment("excel/sheet:2/row:1","科目名称 | 申报金额"),new Segment("excel/sheet:2/row:2","设备 | 10")),"id");
        assertThat(result.rows().get(0).facts()).anySatisfy(f->{assertThat(f.field()).isEqualTo("declaredAmount");assertThat(f.value()).isEqualTo("12元");});
        assertThat(result.rows().get(1).facts()).anySatisfy(f->{assertThat(f.field()).isEqualTo("declaredAmount");assertThat(f.value()).isEqualTo("10");});
    }
    @Test void unknownColumnsStillReachAiWithTheirOriginalHeaderAndCells() throws Exception {
        var h=new Segment("excel/sheet:1/row:1","科目名称 | 本次所需经费");var r=new Segment("excel/sheet:1/row:2","设备 | 12000元");
        when(ai.extract(eq("BUDGET"),eq("id"),anyString(),eq(List.of(h,r)))).thenReturn(new Result(List.of(new Row("row",List.of(new Fact("declaredAmount","12000元",r.locator(),r.text())))),"m","v"));
        var result=reader.extract(text(h,r),"id");
        assertThat(result.rows().stream().flatMap(row->row.facts().stream())).anySatisfy(f->assertThat(f.field()).isEqualTo("declaredAmount"));
        verify(ai).extract(eq("BUDGET"),eq("id"),anyString(),eq(List.of(h,r)));
    }
    @Test void narrativeColumnAmountUsesItsActualSpreadsheetUnitWithoutInventingAValue() throws Exception {
        var header=new Segment("excel/sheet:1/row:1","科目名称 | 本次所需经费（万元）");
        var data=new Segment("excel/sheet:1/row:2","设备 | 12");
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(new Row("a",List.of(
            new Fact("declaredAmount","12",data.locator(),"12")))),"m","v7"));
        var snapshot=reader.extract(text(header,data),"id");
        assertThat(snapshot.issues()).isEmpty();
        assertThat(snapshot.rows().stream().flatMap(r->r.facts().stream())).anySatisfy(f->{
            assertThat(f.field()).isEqualTo("declaredAmount");assertThat(f.value()).isEqualTo("12万元");
            assertThat(f.locator()).endsWith("/col:2");});
        var result=new BudgetUploadedMaterialAnalyzer().analyze(List.of(new BudgetUploadedFacts.Input(UUID.randomUUID(),"仿真.xlsx","PARSED",snapshot)));
        assertThat(result.values()).allSatisfy(f->assertThat(f.lines()).anySatisfy(l->assertThat(l.declaredAmount()).isEqualByComparingTo("120000")));
    }
    @Test void severalUnusualTableRowsKeepTheirOwnSubjectAndUnit() throws Exception {
        var header=new Segment("excel/sheet:1/row:1","科目名称 | 本次所需经费（万元）");
        var a=new Segment("excel/sheet:1/row:2","设备 | 12");var b=new Segment("excel/sheet:1/row:3","培训 | 3");
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(
            new Row("a",List.of(new Fact("declaredAmount","12",a.locator(),"12"))),
            new Row("b",List.of(new Fact("declaredAmount","3",b.locator(),"3")))),"m","v7"));
        var snapshot=reader.extract(text(header,a,b),"id");
        var result=new BudgetUploadedMaterialAnalyzer().analyze(List.of(new BudgetUploadedFacts.Input(UUID.randomUUID(),"仿真.xlsx","PARSED",snapshot)));
        assertThat(snapshot.issues()).isEmpty();
        assertThat(result.values()).allSatisfy(f->{assertThat(f.lines()).hasSize(2);
            assertThat(f.lines()).anySatisfy(l->{assertThat(l.subject()).isEqualTo("设备");assertThat(l.declaredAmount()).isEqualByComparingTo("120000");});
            assertThat(f.lines()).anySatisfy(l->{assertThat(l.subject()).isEqualTo("培训");assertThat(l.declaredAmount()).isEqualByComparingTo("30000");});});
    }
    @Test void swappedNarrativeAmountsCannotPassGroundingMerelyBecauseBothNumbersAreInTheExcerpt() throws Exception {
        String source="设备本次申请9000元，经研究核准5000元。";
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(new Row("1",List.of(new Fact("declaredAmount","5000元","pdf/1",source)))),"m","v"));
        var result=reader.extract(text(new Segment("pdf/1",source)),"id");
        assertThat(result.rows()).isEmpty();assertThat(result.issues()).extracting(ExtractionIssue::reasonCode).contains("AMOUNT_ROLE_UNVERIFIED");
    }
    final MaterialFieldAiPort ai=mock(MaterialFieldAiPort.class);
    final BudgetUploadedMaterialReader reader=new BudgetUploadedMaterialReader(null,ai,null);
    DocumentTextPort.Result text(Segment... segments){return new DocumentTextPort.Result(List.of(segments),"TEXT","a".repeat(64));}
    @Test void knownTableDetailsDoNotReextractExplicitAmountsAndUnitHeadingKeepsIdentity() throws Exception {
        var snapshot=reader.extract(text(
            new Segment("excel/sheet:1/row:1","预算年度：2026   申报部门：综合管理部   金额单位：人民币元"),
            new Segment("excel/sheet:1/row:2","科目名称 | 数量 | 单价（元） | 申报金额 | 批复参考金额"),
            new Segment("excel/sheet:1/row:3","网络巡检 | 12 | 8500 | 120000 | 107100")),"id");
        var facts=snapshot.rows().stream().flatMap(r->r.facts().stream()).toList();
        assertThat(facts).anySatisfy(f->{assertThat(f.field()).isEqualTo("budgetYear");assertThat(f.value()).isEqualTo("2026");});
        assertThat(facts).anySatisfy(f->{assertThat(f.field()).isEqualTo("departmentName");assertThat(f.value()).isEqualTo("综合管理部");});
        assertThat(facts).anySatisfy(f->{assertThat(f.field()).isEqualTo("declaredAmount");assertThat(f.value()).isEqualTo("120000元");});
        verifyNoInteractions(ai);
    }
    @Test void narrativeRemarksRemainModelInputEvenBesideKnownColumns() throws Exception {
        var header=new Segment("excel/sheet:1/row:1","科目名称 | 备注");
        var row=new Segment("excel/sheet:1/row:2","设备 | 本次拟安排经费12万元");
        when(ai.extract(eq("BUDGET"),eq("id"),any(),eq(List.of(header,row))))
            .thenReturn(new Result(List.of(),"m","v7"));
        reader.extract(text(header,row),"id");
        verify(ai).extract(eq("BUDGET"),eq("id"),any(),eq(List.of(header,row)));
    }
    @Test void projectDetailRemarksDoNotResubmitNativeAmountsButKeepSubstantiveExceptions() throws Exception {
        var header=new Segment("excel/sheet:1/row:1","科目名称 | 申报金额（元） | 批复金额（元） | 备注");
        var rows=new ArrayList<Segment>();
        rows.add(header);
        for(int i=1;i<=120;i++) {
            String remark=i%2==0?"已列入本项目预算明细表。":"纳入项目明细";
            rows.add(new Segment("excel/sheet:1/row:"+(i+1),"设备"+i+" | 120 | 110 | "+remark));
        }
        var exception=new Segment("excel/sheet:1/row:122","培训 | 120 | 110 | 列入本项目预算明细表，但须补办审批");
        var missingAmount=new Segment("excel/sheet:1/row:123","运输 | | | 纳入项目明细");
        rows.add(exception);
        rows.add(missingAmount);
        when(ai.extract(eq("BUDGET"),eq("id"),any(),any())).thenReturn(new Result(List.of(),"m","v7"));
        var snapshot=reader.extract(text(rows.toArray(Segment[]::new)),"id");
        verify(ai).extract(eq("BUDGET"),eq("id"),any(),eq(List.of(header,exception,missingAmount)));
        assertThat(snapshot.rows()).hasSize(122);
        assertThat(snapshot.rows().get(119).facts()).anySatisfy(f->{
            assertThat(f.value()).isEqualTo("110元");
            assertThat(f.locator()).isEqualTo("excel/sheet:1/row:121/col:3");
        });
    }
    @Test void spreadsheetGrandTotalIsASeparateTotalAndNeverAnUnnamedExpense() throws Exception {
        var snapshot=reader.extract(text(
            new Segment("excel/sheet:1/row:1","序号 | 一级名称 | 三级名称 | 申报金额 | 第三方审定金额 | 下达金额 | 单位"),
            new Segment("excel/sheet:1/row:2","1 | 设施项目 | 电气维修 | 120 | 115 | 110 | 元"),
            new Segment("excel/sheet:1/row:3","合计 | | | 120 | 115 | 110 | 元")),"grand-total");
        verifyNoInteractions(ai);
        var input=new BudgetUploadedFacts.Input(UUID.randomUUID(),"明细表.xlsx","PARSED",snapshot);
        var result=new BudgetUploadedMaterialAnalyzer().analyzeBatch(List.of(input)).get(input.taskId());
        assertThat(result.lines()).singleElement().satisfies(l->assertThat(l.subject()).isEqualTo("电气维修"));
        assertThat(BudgetBatchAmounts.totals(List.of(input)))
            .anySatisfy(t->{assertThat(t.role()).isEqualTo("declaredTotalAmount");assertThat(t.amount()).isEqualByComparingTo("120");})
            .anySatisfy(t->{assertThat(t.role()).isEqualTo("approvedTotalAmount");assertThat(t.amount()).isEqualByComparingTo("110");});
    }
    @Test void aLabelledExpenseBesideATotalMarkerIsNotReinterpretedAsGrandTotal() throws Exception {
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(),"m","v"));
        var snapshot=reader.extract(text(
            new Segment("excel/sheet:1/row:1","备注 | 科目名称 | 申报金额 | 下达金额 | 单位"),
            new Segment("excel/sheet:1/row:2","合计 | 电气维修 | 120 | 110 | 元")),"named-total");
        var input=new BudgetUploadedFacts.Input(UUID.randomUUID(),"明细.xlsx","PARSED",snapshot);
        assertThat(new BudgetUploadedMaterialAnalyzer().analyzeBatch(List.of(input)).get(input.taskId()).lines())
            .singleElement().satisfies(l->{assertThat(l.subject()).isEqualTo("电气维修");assertThat(l.declaredAmount()).isEqualByComparingTo("120");});
    }
    @Test void workloadEstimateRemarksKeepAllNativeRowsWithoutRepeatedExtraction() throws Exception {
        var segments=new ArrayList<Segment>();
        segments.add(new Segment("excel/sheet:1/row:1","一级名称 | 三级名称 | 申报金额 | 第三方审定金额 | 下达金额 | 备注 | 单位"));
        var remarks=List.of("按工作量核定","按工程量测算。","依据清单估算","按定额核算");
        for(int i=0;i<48;i++)segments.add(new Segment("excel/sheet:1/row:"+(i+2),"设施维护 | 维护子项"+i+" | 120 | 115 | 110 | "+remarks.get(i%4)+" | 元"));
        var snapshot=reader.extract(text(segments.toArray(Segment[]::new)),"native-estimates");
        verifyNoInteractions(ai);
        assertThat(snapshot.rows()).hasSize(48);
        assertThat(snapshot.segments()).containsAll(segments);
        for(var row:snapshot.rows())assertThat(row.facts()).anySatisfy(f->{
            assertThat(f.field()).isEqualTo("approvalAmount");assertThat(f.value()).isEqualTo("110元");
        });
    }
    @Test void workloadRemarksWithAmountsConditionsOrMissingAmountsStillNeedInterpretation() throws Exception {
        var header=new Segment("excel/sheet:1/row:1","科目名称 | 申报金额 | 下达金额 | 备注 | 单位");
        var values=List.of("按工作量核定，但须先完成审批","按工程量测算，另加120元","依据清单估算，未获批准","按工作量核定为暂定价");
        var segments=new ArrayList<Segment>();segments.add(header);
        for(int i=0;i<values.size();i++)segments.add(new Segment("excel/sheet:1/row:"+(i+2),"维护子项"+i+" | 120 | 110 | "+values.get(i)+" | 元"));
        segments.add(new Segment("excel/sheet:1/row:6","运输 | | | 按工作量核定 | 元"));
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(),"m","v"));
        reader.extract(text(segments.toArray(Segment[]::new)),"estimate-exceptions");
        verify(ai).extract(eq("BUDGET"),eq("estimate-exceptions"),any(),eq(segments));
    }
    @Test void excelBothAmountsColumnUnitsAndEveryRowAreReadWithoutAi() throws Exception {
        var document=text(new Segment("excel/sheet:1/row:1","科目名称 | 申报金额 | 批复参考金额（万元） | 项目编号"),
            new Segment("excel/sheet:1/row:2","设备 | 31200 | 3.276 | P1"),new Segment("excel/sheet:1/row:3","培训 | 0 | 0 | P1"));
        var result=reader.extract(document,"id");assertThat(result.rows()).hasSize(2);assertThat(result.rows().get(0).facts()).anySatisfy(f->{assertThat(f.field()).isEqualTo("approvalAmount");assertThat(f.value()).isEqualTo("3.276万元");assertThat(f.locator()).endsWith("/col:3");});verifyNoInteractions(ai);
    }
    @Test void explicitOnlyApprovalAndIncompleteLinesArePreserved() throws Exception {
        var result=reader.extract(text(new Segment("word/1","项目名称：项目甲\n科目名称：设备；批复金额：32760元"),new Segment("word/2","科目名称：培训")),"id");
        assertThat(result.rows()).hasSize(3);verifyNoInteractions(ai);
    }
    @Test void narrativeRequestsAllSourceTextAndAcceptsOnlyGroundedPartialFacts() throws Exception {
        var source=new Segment("pdf/page:1","本项目称为甲项目，设备购置获批32760元。");
        when(ai.extract(eq("BUDGET"),eq("id"),anyString(),eq(List.of(source)))).thenReturn(new Result(List.of(new Row("1",List.of(new Fact("approvalAmount","32760元",source.locator(),"设备购置获批32760元")))),"model","v4"));
        assertThat(reader.extract(text(source),"id").rows()).hasSize(1);
    }
    @Test void hallucinatedAndWrongLocatorEvidenceIsExcludedWithCompletenessWarning() throws Exception {
        for(var fact:List.of(new Fact("approvalAmount","999", "pdf/1","999"),new Fact("approvalAmount","100","foreign","100"))) {
            when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(new Row("1",List.of(fact))),"model","v4"));
            var result=reader.extract(text(new Segment("pdf/1","正文100")),"id");
            assertThat(result.rows()).isEmpty();assertThat(result.issues()).extracting(ExtractionIssue::reasonCode).containsExactly("EVIDENCE_UNVERIFIED");
        }
    }
    @Test void aiCannotUseApprovalAmountAsDeclaration() throws Exception {
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(new Row("1",List.of(new Fact("declaredAmount","200","pdf/1","批复金额：200")))),"model","v4"));
        var result=reader.extract(text(new Segment("pdf/1","说明。批复金额：200")),"id");
        assertThat(result.issues()).extracting(ExtractionIssue::reasonCode).contains("AMOUNT_ROLE_UNVERIFIED");
        assertThat(result.rows().stream().flatMap(r->r.facts().stream())).noneMatch(f->f.field().equals("declaredAmount"));
    }
    @Test void croppedAmountExcerptCannotHideTheOriginalApprovalRole() throws Exception {
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(new Row("1",List.of(
            new Fact("declaredAmount","5000元","p1","5000元")))) ,"model","v"));
        var result=reader.extract(text(new Segment("p1","设备本次申请9000元，经研究核准5000元。")),"id");
        assertThat(result.rows()).isEmpty();assertThat(result.issues()).extracting(ExtractionIssue::reasonCode).contains("AMOUNT_ROLE_UNVERIFIED");
    }
    @Test void labelledPhysicalRowsInOneSpanRemainSeparate() throws Exception {
        var result=reader.extract(text(new Segment("p1","项目名称：甲；科目名称：设备；申报金额：100元\n项目名称：乙；科目名称：设备；批复金额：200元")),"id");
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0).facts()).noneMatch(f->f.value().equals("乙"));
        var compared=new BudgetUploadedMaterialAnalyzer().analyze(List.of(new BudgetUploadedFacts.Input(UUID.randomUUID(),"test.txt","PARSED",result)));
        assertThat(compared.values()).allSatisfy(v->assertThat(v.lines()).allSatisfy(line->assertThat(line.status()).isEqualTo("INSUFFICIENT")));
    }
    @Test void modelRowKeyCannotJoinDifferentPhysicalRowsInOneLocator() throws Exception {
        String source="甲项目设备本次申请9000元。\n乙项目设备获批5000元。";
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(new Row("same",List.of(
            new Fact("projectName","甲项目","p1","甲项目"),new Fact("declaredAmount","9000元","p1","本次申请9000元"),
            new Fact("projectName","乙项目","p1","乙项目"),new Fact("approvalAmount","5000元","p1","获批5000元")))),"m","v"));
        var snapshot=reader.extract(text(new Segment("p1",source)),"id");
        assertThat(snapshot.rows()).hasSize(2);
        assertThat(snapshot.rows()).noneMatch(row->row.facts().stream().map(Fact::field).toList().containsAll(List.of("declaredAmount","approvalAmount")));
    }
    @Test void broadMultilineOrAmbiguousRepeatedExcerptCannotProveRowAssociation() throws Exception {
        String source="甲设备申请100元\n乙设备批复200元";
        for(var excerpt:List.of("设备",source)) {
            when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(new Row("same",List.of(
                new Fact("subjectName","设备","p1",excerpt)))),"m","v"));
            var result=reader.extract(text(new Segment("p1",source)),"id");
            assertThat(result.rows()).isEmpty();assertThat(result.issues()).extracting(ExtractionIssue::reasonCode).contains("ROW_UNRESOLVED");
        }
    }

    @Test void broadModelDuplicateUsesAlreadyReadPhysicalLabelEvidenceWithoutDroppingOtherRows() throws Exception {
        String source="申请部门：采购管理部\n项目名称：甲\n科目名称：设备；申报金额：100元\n科目名称：设备；申报金额：100元\n补充说明：验收后交付";
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(new Row("model",List.of(
                new Fact("departmentName","采购管理部","p1",source),
                new Fact("declaredAmount","100元","p1",source)))),"m","v"));
        var snapshot=reader.extract(text(new Segment("p1",source)),"id");
        assertThat(snapshot.rows()).hasSize(4);
        assertThat(snapshot.rows().stream().flatMap(row->row.facts().stream()).filter(f->f.field().equals("declaredAmount")))
                .hasSize(2).allSatisfy(f->assertThat(f.excerpt()).isEqualTo("申报金额：100元"));
    }

    @Test void broadModelFactCannotHideRoleOrValueDriftBehindOtherLocallyReadFacts() throws Exception {
        String source="项目名称：甲\n科目名称：设备；申报金额：100元\n批复金额：200元\n补充说明";
        for(var fact:List.of(new Fact("approvalAmount","100元","p1",source),
                new Fact("declaredAmount","200元","p1",source))) {
            when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(new Row("model",List.of(fact))),"m","v"));
            var result=reader.extract(text(new Segment("p1",source)),"id");
            assertThat(result.issues()).extracting(ExtractionIssue::reasonCode).contains("AMOUNT_ROLE_UNVERIFIED");
        }
    }

    @Test void multilineQuotationIsNarrowedToEachUniqueVerbatimValueWithoutJoiningProjects() throws Exception {
        String source="甲项目设备本次申请9000元。\n乙项目设备获批5000元。";
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(new Row("same",List.of(
            new Fact("projectName","甲项目","p1",source),new Fact("declaredAmount","9000元","p1",source),
            new Fact("projectName","乙项目","p1",source),new Fact("approvalAmount","5000元","p1",source)))),"m","v"));
        var snapshot=reader.extract(text(new Segment("p1",source)),"id");
        assertThat(snapshot.rows()).hasSize(2);
        assertThat(snapshot.rows()).noneMatch(row->row.facts().stream().map(Fact::field).toList().containsAll(List.of("declaredAmount","approvalAmount")));
        assertThat(snapshot.rows().stream().flatMap(row->row.facts().stream())).allSatisfy(f->assertThat(f.excerpt()).doesNotContain("\n"));
    }
    @Test void multilineQuotationWithRepeatedAmountsMustRemainUnresolved() throws Exception {
        String source="甲项目设备本次申请9000元。\n乙项目设备获批9000元。";
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(new Row("same",List.of(
            new Fact("declaredAmount","9000元","p1",source)))),"m","v"));
        var result=reader.extract(text(new Segment("p1",source)),"id");
        assertThat(result.rows()).isEmpty();assertThat(result.issues()).extracting(ExtractionIssue::reasonCode).contains("ROW_UNRESOLVED");
    }

    @Test void repeatedSubjectCanUseUniqueProjectOnSameLineButCannotBridgeProjects() throws Exception {
        String source="甲项目设备申请9000元\r\n乙项目设备获批5000元";
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(
            new Row("a",List.of(new Fact("projectName","甲项目","p1","甲项目"),new Fact("subjectName","设备","p1","设备"),new Fact("declaredAmount","9000元","p1","申请9000元"))),
            new Row("b",List.of(new Fact("projectName","乙项目","p1","乙项目"),new Fact("subjectName","设备","p1","设备"),new Fact("approvalAmount","5000元","p1","获批5000元")))),"m","v"));
        var snapshot=reader.extract(text(new Segment("p1",source)),"id");
        assertThat(snapshot.issues()).isEmpty();assertThat(snapshot.rows()).hasSize(2);
        var result=new BudgetUploadedMaterialAnalyzer().analyze(List.of(new BudgetUploadedFacts.Input(UUID.randomUUID(),"仿真.txt","PARSED",snapshot)));
        assertThat(result.values()).allSatisfy(f->assertThat(f.lines()).hasSize(2).allSatisfy(l->assertThat(l.status()).isEqualTo("INSUFFICIENT")));
    }

    @Test void oneUnverifiedAmountDoesNotDiscardAnotherVerifiedProjectOrPretendAllPassed() throws Exception {
        String source="甲项目设备申请120元，批复100元。\n乙项目培训申请50元。";
        when(ai.extract(any(),any(),any(),any())).thenReturn(new Result(List.of(
            new Row("a",List.of(new Fact("projectName","甲项目","p1","甲项目"),new Fact("subjectName","设备","p1","设备"),new Fact("declaredAmount","120元","p1","申请120元"),new Fact("approvalAmount","100元","p1","批复100元"))),
            new Row("b",List.of(new Fact("projectName","乙项目","p1","乙项目"),new Fact("subjectName","培训","p1","培训"),new Fact("declaredAmount","999元","p1","999元")))),"m","v"));
        var snapshot=reader.extract(text(new Segment("p1",source)),"id");
        assertThat(snapshot.issues()).hasSize(1);
        var result=new BudgetUploadedMaterialAnalyzer().analyze(List.of(new BudgetUploadedFacts.Input(UUID.randomUUID(),"仿真.txt","PARSED",snapshot)));
        assertThat(result.values()).allSatisfy(f->{assertThat(f.lines()).anySatisfy(l->{assertThat(l.status()).isEqualTo("OVER_APPROVAL");assertThat(l.opinion()).contains("20");});
            assertThat(f.lines()).noneMatch(l->"WITHIN_APPROVAL".equals(l.status()));assertThat(f.summary()).contains("未能与原文核实");});
    }
}
