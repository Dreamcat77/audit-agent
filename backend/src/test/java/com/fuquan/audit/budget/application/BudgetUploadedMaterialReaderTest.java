package com.fuquan.audit.budget.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fuquan.audit.common.material.application.port.*;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.*;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;

class BudgetUploadedMaterialReaderTest {
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
