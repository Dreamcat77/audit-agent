package com.fuquan.audit.contract.application.project.parse;

import static org.assertj.core.api.Assertions.*;
import com.fuquan.audit.contract.application.project.ProjectIntakeProperties;
import com.fuquan.audit.contract.application.project.archive.SelectedProjectArchiveReader;
import com.fuquan.audit.contract.domain.project.ProjectFileParseMethod;
import com.fuquan.audit.review.application.port.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.util.Units;
import org.junit.jupiter.api.Test;

class ProjectDocxImageTest {
    @Test void headerImagesAreReadAndBrokenImagesCannotPublishNativeTextOnly() throws Exception {
        byte[] headerBytes;
        try(var doc=new XWPFDocument();var out=new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("合同正文");
            var header=doc.createHeader(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT);
            picture(header.createParagraph().createRun());doc.write(out);headerBytes=out.toByteArray();
        }
        var parsed=parser(r->result(r,"附件条款",false)).extract(ProjectDocumentParserTest.file("x.docx","application/octet-stream"),new ByteArrayInputStream(headerBytes));
        assertThat(parsed.document().segments().get(1).sourceLocator()).startsWith("docx/header:1/");
        byte[] broken;
        try(var doc=new XWPFDocument();var out=new ByteArrayOutputStream()) {
            var run=doc.createParagraph().createRun();run.setText("正文不能掩盖附件失败");
            run.addPicture(new ByteArrayInputStream(new byte[]{1,2,3}),XWPFDocument.PICTURE_TYPE_PNG,"broken.png",100,100);
            doc.write(out);broken=out.toByteArray();
        }
        assertThatThrownBy(()->parser(r->{throw new AssertionError("must reject before OCR");}).extract(
            ProjectDocumentParserTest.file("x.docx","application/octet-stream"),new ByteArrayInputStream(broken)))
            .isInstanceOf(ProjectParseException.class).hasMessageContaining("DOCX_IMAGE_FORMAT_UNSUPPORTED");
    }
    @Test void bodyAndCellImagesHaveSeparateEvidenceWithoutChangingNativeCells() throws Exception {
        var requests=new ArrayList<OcrRequest>();
        byte[] bytes=document(2);
        byte[] original=bytes.clone();
        var extraction=parser(request->{requests.add(request);return result(request,"图内合同金额120000元",false);})
                .extract(ProjectDocumentParserTest.file("contract.docx","application/octet-stream"),new ByteArrayInputStream(bytes));
        assertThat(bytes).isEqualTo(original);
        assertThat(requests).hasSize(2).allSatisfy(r->{assertThat(r.contentType()).isEqualTo("image/png");});
        assertThat(extraction.document().segments()).extracting(ParsedProjectDocument.Segment::text)
                .containsExactly("合同正文","图内合同金额120000元","原生条款","图内合同金额120000元");
        assertThat(extraction.document().segments().get(1).sourceLocator()).isEqualTo("docx/body/paragraph:1/image:1");
        assertThat(extraction.document().segments().get(3).sourceLocator()).contains("/cell:1/element:1/image:1");
        assertThat(extraction.document().tables().get(0).rows().get(0).cells().get(0).text()).isEqualTo("原生条款");
        assertThat(extraction.provenance().method()).isEqualTo(ProjectFileParseMethod.OCR);
        assertThat(extraction.provenance().engine()).contains("real-test");
    }
    @Test void unknownBusyAndLaterImageFailureNeverReturnPartialDocument() throws Exception {
        var busy=new OcrCapacityUnavailableException();
        var unknown=new OcrOutcomeUnprovenException(new IOException());
        for(RuntimeException failure:List.of(busy,unknown,new IllegalStateException("offline"))) {
            int[] count={0};var parser=parser(request->{if(++count[0]==2)throw failure;return result(request,"第一张",false);});
            var thrown=catchThrowable(()->parser.extract(ProjectDocumentParserTest.file("x.docx","application/octet-stream"),new ByteArrayInputStream(document(2))));
            if(failure==busy||failure==unknown)assertThat(thrown).isSameAs(failure);
            else assertThat(thrown).isInstanceOf(ProjectParseException.class).hasMessageContaining("DOCX_IMAGE_OCR_FAILED");
        }
    }
    @Test void simulatedMismatchedAndUncoveredResultsAreRejected() throws Exception {
        for(OcrPort ocr:List.<OcrPort>of(r->result(r,"text",true),
                r->new OcrResult("different","text",.9,List.of(new OcrPage(1,"text",.9)),false,"real-test",1),
                r->new OcrResult(r.documentId(),"text",.9,List.of(new OcrPage(1,"other",.9)),false,"real-test",1))) {
            assertThatThrownBy(()->parser(ocr).extract(ProjectDocumentParserTest.file("x.docx","application/octet-stream"),new ByteArrayInputStream(document(1))))
                .isInstanceOf(RuntimeException.class);
        }
    }
    @Test void emptyLogoDoesNotFabricateTextAndNoImageDoesNotCallOcr() throws Exception {
        var parsed=parser(r->result(r,"",false)).extract(ProjectDocumentParserTest.file("x.docx","application/octet-stream"),new ByteArrayInputStream(document(1)));
        assertThat(parsed.document().fullText()).isEqualTo("合同正文");
        assertThat(parsed.provenance().method()).isEqualTo(ProjectFileParseMethod.OCR);
        var plain=parser(r->{throw new AssertionError("no OCR for native document");}).extract(
            ProjectDocumentParserTest.file("x.docx","application/octet-stream"),new ByteArrayInputStream(document(0)));
        assertThat(plain.provenance().method()).isEqualTo(ProjectFileParseMethod.DOCX_TEXT);
        assertThat(plain.document().fullText()).isEqualTo("合同正文");
    }
    private static OcrResult result(OcrRequest request,String text,boolean simulated){return new OcrResult(request.documentId(),text,.95,List.of(new OcrPage(1,text,.95)),simulated,"real-test",1);}
    private static ProjectDocxTextParser parser(OcrPort ocr){var limits=ProjectIntakeProperties.defaults();return new ProjectDocxTextParser(new ProjectDocxContainerGuard(new SelectedProjectArchiveReader(limits),limits),ocr);}
    private static byte[] document(int images)throws Exception {
        try(var doc=new XWPFDocument();var out=new ByteArrayOutputStream()) {
            var paragraph=doc.createParagraph();paragraph.createRun().setText("合同正文");
            if(images>0)picture(paragraph.createRun());
            if(images>1){var cell=doc.createTable(1,1).getRow(0).getCell(0);cell.setText("原生条款");picture(cell.getParagraphs().get(0).createRun());}
            doc.write(out);return out.toByteArray();
        }
    }
    private static void picture(XWPFRun run)throws Exception {
        var image=new BufferedImage(20,40,BufferedImage.TYPE_INT_RGB);
        try(var bytes=new ByteArrayOutputStream()){ImageIO.write(image,"png",bytes);run.addPicture(new ByteArrayInputStream(bytes.toByteArray()),XWPFDocument.PICTURE_TYPE_PNG,"image.png",Units.toEMU(20),Units.toEMU(40));}
        finally{image.flush();}
    }
}
