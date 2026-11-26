package com.fuquan.audit.budget.application;

import static org.assertj.core.api.Assertions.*;
import com.fuquan.audit.common.material.application.port.*;
import com.fuquan.audit.integration.material.OfficeDocumentTextReader;
import com.fuquan.audit.review.application.port.OcrPort;
import java.io.*;
import java.util.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class BudgetWordBudgetIntegrationTest {
    @Test void realWordContainerWithTwoTablesComparesSubjectsAndTotalsWithoutModel() throws Exception {
        byte[] bytes;
        try(var doc=new XWPFDocument();var out=new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("项目名称：信息化运维服务项目");
            doc.createParagraph().createRun().setText("项目编号：FW-017    申报年度：2026年");
            for(String[][] values:List.of(new String[][]{{"序号","支出项目","测算依据","申报金额\n万元"},
                    {"1","驻场运维服务","4人","120.00"},{"2","数据整理服务","1项","8.00"},{"","合计","","128.00"}},
                    new String[][]{{"序号","支出项目","批复金额\n万元","支出范围"},{"1","驻场运维服务","110.00","运维"},{"","合计","110.00",""}})) {
                var table=doc.createTable(values.length,4);
                for(int r=0;r<values.length;r++)for(int c=0;c<4;c++) {
                    var run=table.getRow(r).getCell(c).getParagraphs().get(0).createRun();
                    String[] parts=values[r][c].split("\n");for(int i=0;i<parts.length;i++){if(i>0)run.addBreak();run.setText(parts[i]);}
                }
                doc.createParagraph().createRun().setText("本页表格结束");
            }
            doc.write(out);bytes=out.toByteArray();
        }
        var parser=new OfficeDocumentTextReader(stream->{try{return stream.readAllBytes();}catch(IOException e){throw new UncheckedIOException(e);}},request->{throw new AssertionError("No OCR for native Word");});
        var document=parser.read(new ByteArrayInputStream(bytes),"预算.docx","test",1000000,20,100000);
        MaterialFieldAiPort ai=(operation,id,sha,segments)->new MaterialFieldAiPort.Result(List.of(),"none","test");
        var snapshot=new BudgetUploadedMaterialReader(parser,ai,null).extract(document,"test");
        var input=new BudgetUploadedFacts.Input(UUID.randomUUID(),"预算.docx","PARSED",snapshot);
        var result=new BudgetUploadedMaterialAnalyzer().analyzeBatch(List.of(input)).get(input.taskId());
        assertThat(result.lines()).anySatisfy(line->{assertThat(line.subject()).isEqualTo("驻场运维服务");assertThat(line.declaredAmount()).isEqualByComparingTo("1200000");assertThat(line.approvalAmount()).isEqualByComparingTo("1100000");assertThat(line.status()).isEqualTo("OVER_APPROVAL");});
        assertThat(result.lines()).anySatisfy(line->{assertThat(line.subject()).isEqualTo("数据整理服务");assertThat(line.declaredAmount()).isEqualByComparingTo("80000");assertThat(line.status()).isEqualTo("UNAPPROVED_SUBJECT");});
        assertThat(BudgetBatchAmounts.checks(BudgetBatchAmounts.totals(List.of(input)))).anyMatch(a->a.code().equals("TOTAL_VS_APPROVAL")&&a.explanation().contains("180000"));
    }
    @Test void adjacentUnrelatedTableCannotInheritBudgetColumnOrUnit() throws Exception {
        byte[] bytes;
        try(var doc=new XWPFDocument();var out=new ByteArrayOutputStream()) {
            // No total or separating paragraph: text adjacency is not a table boundary.
            for(String[][] values:List.of(new String[][]{{"序号","支出项目","申请金额（万元）"},{"1","运维","12"}},
                    new String[][]{{"序号","人员","服务天数"},{"2","巡检","200"}})) {
                var table=doc.createTable(values.length,3);
                for(int r=0;r<values.length;r++)for(int c=0;c<3;c++)table.getRow(r).getCell(c).setText(values[r][c]);
            }
            doc.write(out);bytes=out.toByteArray();
        }
        var parser=new OfficeDocumentTextReader(stream->{try{return stream.readAllBytes();}catch(IOException e){throw new UncheckedIOException(e);}},request->{throw new AssertionError("No OCR");});
        var document=parser.read(new ByteArrayInputStream(bytes),"预算.docx","test",1000000,20,100000);
        MaterialFieldAiPort ai=(operation,id,sha,segments)->new MaterialFieldAiPort.Result(List.of(),"none","test");
        var snapshot=new BudgetUploadedMaterialReader(parser,ai,null).extract(document,"test");
        var facts=snapshot.rows().stream().flatMap(r->r.facts().stream()).toList();
        assertThat(facts).anyMatch(f->f.field().equals("declaredAmount")&&f.value().equals("12万元"));
        assertThat(facts).noneMatch(f->f.field().equals("declaredAmount")&&f.value().contains("200"));
        var input=new BudgetUploadedFacts.Input(UUID.randomUUID(),"预算.docx","PARSED",snapshot);
        assertThat(facts).allSatisfy(f->{
            var located=BudgetSourceText.locate(new BudgetUploadedFacts.Evidence(input.taskId(),input.fileName(),f.field(),f.value(),f.locator(),f.excerpt()),snapshot);
            assertThat(located.start()).isNotNull();assertThat(located.end()).isNotNull();
            assertThat(BudgetSourceText.text(snapshot).substring(located.start(),located.end())).isEqualTo(f.excerpt());
        });
    }
}
