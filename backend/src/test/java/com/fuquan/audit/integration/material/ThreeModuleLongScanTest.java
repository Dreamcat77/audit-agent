package com.fuquan.audit.integration.material;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fuquan.audit.budget.application.*;
import com.fuquan.audit.budget.application.port.BudgetMaterialStoragePort;
import com.fuquan.audit.budget.domain.BudgetReviewTask;
import com.fuquan.audit.common.material.application.port.*;
import com.fuquan.audit.contract.application.project.SharedProjectMaterialReader;
import com.fuquan.audit.contract.domain.project.*;
import com.fuquan.audit.review.application.port.*;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.Test;

/** Native OCR is exercised separately on 26; these tests prove business adapters visit the tail. */
class ThreeModuleLongScanTest {
    byte[] scanned(int pages)throws Exception {
        try(var pdf=new PDDocument();var out=new ByteArrayOutputStream()) {
            for(int n=1;n<=pages;n++) {
                var page=new PDPage();page.getCOSObject().setInt("TEST_PAGE",n);pdf.addPage(page);
                try(var stream=new PDPageContentStream(pdf,page)){stream.addRect(20,20,100,200);stream.stroke();}
            }
            pdf.save(out);return out.toByteArray();
        }
    }
    OfficeDocumentTextReader reader(AtomicInteger calls,int tail,String text) {
        return new OfficeDocumentTextReader(in->new byte[0],request->{
            try(var in=request.source().openStream();var pdf=PDDocument.load(in)) {
                calls.incrementAndGet();int page=pdf.getPage(0).getCOSObject().getInt("TEST_PAGE");
                String value=page==tail?text:"合成阅读验证第"+page+"页的服务安排说明。";
                return new OcrResult(request.documentId(),value,.99,List.of(new OcrPage(1,value,.99)),false,"SYNTHETIC_TEST",1);
            }catch(IOException e){throw new UncheckedIOException(e);}
        });
    }
    @Test void budget162ScannedPagesReachTailAndCompareUppercaseAmounts()throws Exception {
        byte[] source=scanned(162);var count=new AtomicInteger();
        var store=mock(BudgetMaterialStoragePort.class);when(store.open("synthetic")).thenReturn(new ByteArrayInputStream(source));
        var task=mock(BudgetReviewTask.class);UUID id=UUID.randomUUID();when(task.id()).thenReturn(id);when(task.fileName()).thenReturn("预算.pdf");when(task.tempFilePath()).thenReturn("synthetic");
        MaterialFieldAiPort ai=(kind,material,sha,segments)->new MaterialFieldAiPort.Result(List.of(),"TEST","TEST");
        var read=new BudgetUploadedMaterialReader(reader(count,162,"项目名称：合成设施更新\n科目名称：设备\n申报金额：人民币壹拾贰万元整\n批复金额：人民币壹拾万元整"),ai,store);
        var snapshot=read.read(task);assertThat(count.get()).isEqualTo(162);
        var result=new BudgetUploadedMaterialAnalyzer().analyze(List.of(new BudgetUploadedFacts.Input(id,"预算.pdf","PARSED",snapshot))).get(id);
        assertThat(result.lines()).anySatisfy(line->{assertThat(line.declaredAmount()).isEqualByComparingTo("120000");
            assertThat(line.approvalAmount()).isEqualByComparingTo("100000");assertThat(line.status()).isEqualTo("OVER_APPROVAL");});
    }
    @Test void contract240ScannedPagesRemainAvailableToContractFacts()throws Exception {
        byte[] source=scanned(240);var count=new AtomicInteger();UUID id=UUID.randomUUID(),project=UUID.randomUUID();
        var result=new OfficeMaterialParseAdapter(reader(count,240,"合同书\n合同金额：人民币贰佰贰拾万元整（2200000元）\n签约日期：2026年10月30日"))
            .parse(new MaterialParsePort.ParseRequest(id.toString(),"合同书.pdf","application/pdf",new ByteArrayInputStream(source),10000000,512,1000000,Map.of()));
        var now=Instant.now();var file=new ProjectFile(id,project,null,null,"合同书.pdf","application/pdf",ProjectFileCategory.UNKNOWN,
            ProjectFileIngestionStatus.AVAILABLE,ProjectFileParseStatus.PENDING,"a".repeat(64),result.fileSha256(),source.length,null,null,now,now);
        var extraction=SharedProjectMaterialReader.convert(file,result,1);
        assertThat(count.get()).isEqualTo(240);assertThat(result.coverageGaps()).isEmpty();
        assertThat(extraction.document().fullText()).contains("合同书","贰佰贰拾万元","2200000","2026年10月30日");
        assertThat(extraction.document().segments()).hasSize(240);
    }
    @Test void over512PagesStillStopsBeforeOcr()throws Exception {
        var count=new AtomicInteger();byte[] bytes=scanned(513);
        assertThatThrownBy(()->reader(count,513,"不可读取").read(new ByteArrayInputStream(bytes),"x.pdf","doc",10000000,512,1000000))
            .isInstanceOf(DocumentTextPort.ReadFailure.class).hasMessage("PAGE_LIMIT_EXCEEDED");assertThat(count.get()).isZero();
    }
}
