package com.fuquan.audit.integration.material;

import static org.assertj.core.api.Assertions.*;
import com.fuquan.audit.common.material.application.port.*;
import com.fuquan.audit.review.application.port.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.Test;

class PdfReadCheckpointTest {
    static class Crash extends Error {}
    byte[] pdf(int count) throws Exception {
        try(var pdf=new PDDocument();var out=new ByteArrayOutputStream()) {
            for(int n=1;n<=count;n++) {
                var page=new PDPage();page.getCOSObject().setInt("TEST_PAGE",n);pdf.addPage(page);
                try(var canvas=new PDPageContentStream(pdf,page)){canvas.addRect(20,20,100,200);canvas.stroke();}
            }
            pdf.save(out);return out.toByteArray();
        }
    }
    OfficeDocumentTextReader reader(List<Integer> calls) {
        return new OfficeDocumentTextReader(input->new byte[0],request->{
            try(var input=request.source().openStream();var page=PDDocument.load(input)) {
                int number=page.getPage(0).getCOSObject().getInt("TEST_PAGE");calls.add(number);
                String text="第"+number+"页原文金额"+number+"元";
                return new OcrResult(request.documentId(),text,.99,List.of(new OcrPage(1,text,.99)),false,"TEST",1);
            } catch(IOException failure){throw new UncheckedIOException(failure);}
        });
    }
    @Test void all162PagesResumeAfterCrashWithoutRepeatingAcknowledgedOcrOrChangingLocators() throws Exception {
        byte[] bytes=pdf(162);var calls=new ArrayList<Integer>();
        var saved=new AtomicReference<DocumentTextPort.PdfCheckpoint>();
        assertThatThrownBy(()->reader(calls).read(new ByteArrayInputStream(bytes),"招标文件.pdf","doc",10_000_000,512,768000,null,p->{
            saved.set(p);if(p.completedPages().size()==80)throw new Crash();
        })).isInstanceOf(Crash.class);
        assertThat(calls).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1,80).boxed().toList());
        var resumed=reader(calls).read(new ByteArrayInputStream(bytes),"招标文件.pdf","doc",10_000_000,512,768000,saved.get(),p->{});
        assertThat(calls).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1,162).boxed().toList());
        assertThat(resumed.segments()).hasSize(162);
        assertThat(resumed.ocrProvenance()).hasSize(162);
        assertThat(resumed.coverageGaps()).isEmpty();
        assertThat(resumed.segments().get(161).text()).contains("第162页");
        var fresh=reader(new ArrayList<>()).read(new ByteArrayInputStream(bytes),"招标文件.pdf","doc",10_000_000,512,768000);
        assertThat(resumed).isEqualTo(fresh);
    }
    @Test void changedSourceOrPolicyCannotReuseOrIssueAnyOcr() throws Exception {
        byte[] bytes=pdf(2);var calls=new ArrayList<Integer>();var saved=new AtomicReference<DocumentTextPort.PdfCheckpoint>();
        reader(calls).read(new ByteArrayInputStream(bytes),"x.pdf","doc",1000000,512,768000,null,saved::set);
        var previous=saved.get();calls.clear();
        for(var invalid:List.of(
            new DocumentTextPort.PdfCheckpoint("a".repeat(64),previous.policy(),2,previous.completedPages(),previous.segments(),previous.provenance()),
            new DocumentTextPort.PdfCheckpoint(previous.sourceSha256(),"unproven-policy",2,previous.completedPages(),previous.segments(),previous.provenance()),
            new DocumentTextPort.PdfCheckpoint(previous.sourceSha256(),previous.policy(),2,List.of(1,1),previous.segments(),previous.provenance()))) {
            assertThatThrownBy(()->reader(calls).read(new ByteArrayInputStream(bytes),"x.pdf","doc",1000000,512,768000,invalid,p->{}))
                .isInstanceOf(DocumentTextPort.ReadFailure.class);
        }
        assertThat(calls).isEmpty();
    }
    @Test void aLostDatabaseCheckpointFenceStopsReadingImmediatelyAndKeepsTheFailureType() throws Exception {
        byte[] bytes=pdf(2);var calls=new ArrayList<Integer>();var lost=new IllegalStateException("lease lost");
        assertThatThrownBy(()->reader(calls).read(new ByteArrayInputStream(bytes),"x.pdf","doc",1000000,512,768000,null,p->{throw lost;}))
            .isSameAs(lost);
        assertThat(calls).isEmpty();
    }
    @Test void mixedNativeAndScannedPagesKeepTheSameEvidenceLocationsAfterRestart() throws Exception {
        byte[] bytes;
        try(var pdf=new PDDocument();var out=new ByteArrayOutputStream()) {
            for(int n=1;n<=4;n++) {
                var page=new PDPage();page.getCOSObject().setInt("TEST_PAGE",n);pdf.addPage(page);
                try(var canvas=new PDPageContentStream(pdf,page)) {
                    if(n%2==0){canvas.beginText();canvas.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font.HELVETICA,12);
                        canvas.newLineAtOffset(50,700);canvas.showText("Native contract project budget evidence and details page "+n);canvas.endText();}
                    else{canvas.addRect(20,20,100,200);canvas.stroke();}
                }
            }
            pdf.save(out);bytes=out.toByteArray();
        }
        var calls=new ArrayList<Integer>();var saved=new AtomicReference<DocumentTextPort.PdfCheckpoint>();
        assertThatThrownBy(()->reader(calls).read(new ByteArrayInputStream(bytes),"x.pdf","doc",1000000,512,768000,null,p->{
            saved.set(p);if(p.completedPages().contains(1))throw new Crash();
        })).isInstanceOf(Crash.class);
        var resumed=reader(calls).read(new ByteArrayInputStream(bytes),"x.pdf","doc",1000000,512,768000,saved.get(),p->{});
        var fresh=reader(new ArrayList<>()).read(new ByteArrayInputStream(bytes),"x.pdf","doc",1000000,512,768000);
        assertThat(resumed).isEqualTo(fresh);assertThat(calls).containsExactly(1,3);
    }
}
