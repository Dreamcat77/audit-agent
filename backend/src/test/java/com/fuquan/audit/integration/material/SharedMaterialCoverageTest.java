package com.fuquan.audit.integration.material;
import static org.assertj.core.api.Assertions.*;
import com.fuquan.audit.common.material.application.port.*;
import com.fuquan.audit.review.application.port.*;
import java.io.*;
import java.awt.image.BufferedImage;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;

class SharedMaterialCoverageTest {
    OfficeDocumentTextReader reader(OcrPort ocr){return new OfficeDocumentTextReader(in->{try{return in.readAllBytes();}catch(IOException e){throw new UncheckedIOException(e);}},ocr);}
    void nativePage(PDDocument pdf,String text,boolean logo) throws Exception {
        var page=new PDPage();pdf.addPage(page);
        try(var stream=new PDPageContentStream(pdf,page)) {
            stream.beginText();stream.setFont(PDType1Font.HELVETICA,12);stream.newLineAtOffset(50,700);stream.showText(text);stream.endText();
            if(logo)stream.drawImage(LosslessFactory.createFromImage(pdf,new BufferedImage(20,20,BufferedImage.TYPE_INT_RGB)),20,20,20,20);
        }
    }
    byte[] bytes(PDDocument pdf) throws IOException {var out=new ByteArrayOutputStream();pdf.save(out);return out.toByteArray();}
    @Test void sixtyFourNativePagesWithLogosNeverRequireOcr() throws Exception {
        try(var pdf=new PDDocument()) {
            for(int i=0;i<64;i++)nativePage(pdf,i==2?"Budget CNY 2200000":"Valid native procurement document page "+i,true);
            var calls=new int[1];var result=reader(request->{calls[0]++;throw new IllegalStateException("must not call");})
                .read(new ByteArrayInputStream(bytes(pdf)),"tender.pdf",UUID.randomUUID().toString(),10_000_000,100,192000);
            assertThat(calls[0]).isZero();assertThat(result.segments()).hasSize(64);
            assertThat(result.segments()).anyMatch(s->s.text().contains("2200000"));assertThat(result.coverageGaps()).isEmpty();
        }
    }
    @Test void unknownScanDoesNotEraseEarlierOrLaterNativePagesAndStopsFurtherOcr() throws Exception {
        try(var pdf=new PDDocument()) {
            nativePage(pdf,"Native evidence before scan",false);
            for(int i=0;i<2;i++){var page=new PDPage();pdf.addPage(page);try(var stream=new PDPageContentStream(pdf,page)){stream.drawImage(LosslessFactory.createFromImage(pdf,new BufferedImage(80,100,BufferedImage.TYPE_INT_RGB)),0,0,500,700);}}
            nativePage(pdf,"Budget CNY 2200000 after scan",false);
            var calls=new int[1];var result=reader(request->{calls[0]++;throw new OcrOutcomeUnprovenException(new IOException());})
                .read(new ByteArrayInputStream(bytes(pdf)),"mixed.pdf",UUID.randomUUID().toString(),10_000_000,100,192000);
            assertThat(calls[0]).isOne();assertThat(result.segments()).hasSize(2);
            assertThat(result.segments().get(1).text()).contains("2200000");assertThat(result.coverageGaps()).hasSize(2);
            assertThat(result.method()).contains("PARTIAL");
            var parsed=new OfficeMaterialParseAdapter((in,name,id,b,p,c)->result).parse(new MaterialParsePort.ParseRequest("id","mixed.pdf","application/pdf",InputStream.nullInputStream(),10,10,100,Map.of()));
            assertThat(parsed.usable()).isTrue();assertThat(parsed.textResult().coverageGaps()).hasSize(2);assertThat(parsed.textResult().method()).contains("PARTIAL");
        }
    }
    @Test void textLimitStillRejectsInsteadOfPublishingTruncatedNativeEvidence() throws Exception {
        try(var pdf=new PDDocument()) {
            nativePage(pdf,"A sufficiently long body of native text",false);
            assertThatThrownBy(()->reader(request->{throw new AssertionError();}).read(new ByteArrayInputStream(bytes(pdf)),"x.pdf","id",100000,10,8))
                .isInstanceOf(DocumentTextPort.ReadFailure.class).hasMessage("TEXT_LIMIT_EXCEEDED");
        }
    }
}
