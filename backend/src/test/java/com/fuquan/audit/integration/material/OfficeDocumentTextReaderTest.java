package com.fuquan.audit.integration.material;

import static org.assertj.core.api.Assertions.*;
import com.fuquan.audit.common.material.application.port.*;
import com.fuquan.audit.review.application.port.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.hslf.usermodel.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;

class OfficeDocumentTextReaderTest {
    @Test void realContainerSelectsWordParserWithoutRenamingFilesOrFetchingHtmlImages()throws Exception{
        try(var doc=new XWPFDocument();var out=new ByteArrayOutputStream()){
            doc.createParagraph().createRun().setText("合同总价：10000元");doc.write(out);
            assertThat(read(out.toByteArray(),"WPS导出.doc").segments()).anyMatch(s->s.text().contains("合同总价"));
        }
        var html=read("<html><head><meta charset='utf-8'></head><body><p>项目名称：青松维护</p><p>合同金额：10000元</p><img src='http://127.0.0.1:9/private'></body></html>".getBytes(StandardCharsets.UTF_8),"网页导出.doc");
        assertThat(html.segments()).anyMatch(s->s.text().contains("青松维护"));assertThat(html.method()).contains("PARTIAL");assertThat(calls).isEmpty();
        var rtf=read("{\\rtf1\\ansi Contract amount 10000}".getBytes(StandardCharsets.US_ASCII),"富文本导出.doc");
        assertThat(rtf.segments()).anyMatch(s->s.text().contains("10000"));
    }
    @Test void nativeWordServiceRowsKeepEmptyColumnsAndMultilineCellsTogether() throws Exception {
        try(var doc=new XWPFDocument();var out=new ByteArrayOutputStream()) {
            var table=doc.createTable(3,4);
            String[][] values={{"服务事项","工作量（人月）","含税单价（元/人月）","含税经费（元）"},
                    {"同名服务","1.00","45,580","45,580"},{"合计","4.00","","182,320"}};
            for(int row=0;row<3;row++)for(int col=0;col<4;col++)table.getRow(row).getCell(col).setText(values[row][col]);
            var run=table.getRow(1).getCell(0).getParagraphs().get(0).createRun();run.addBreak();run.setText("第二行内容");
            doc.write(out);var result=read(out.toByteArray(),"service.docx");
            assertThat(result.segments()).extracting(MaterialFieldAiPort.Segment::text)
                    .contains("同名服务\u2028第二行内容\t1.00\t45,580\t45,580","合计\t4.00\t\t182,320");
            assertThat(calls).isEmpty();
        }
    }
    @Test void directJpegUploadKeepsOriginalBytesAndMimeForExifAwareOcr() throws Exception {
        byte[] bytes;
        try(var out=new ByteArrayOutputStream()) {
            ImageIO.write(new BufferedImage(80,40,BufferedImage.TYPE_INT_RGB),"jpg",out);
            bytes=out.toByteArray();
        }
        var requests=new ArrayList<OcrRequest>();var forwardedBytes=new ArrayList<byte[]>();
        OcrPort recording=request->{
            requests.add(request);
            try(var source=request.source().openStream()){forwardedBytes.add(source.readAllBytes());}
            catch(IOException failure){throw new UncheckedIOException(failure);}
            return new OcrResult(request.documentId(),"旋转扫描内容",0.99,
                    List.of(new OcrPage(1,"旋转扫描内容",0.99)),false,"TEST",1);
        };
        var result=reader(recording).read(new ByteArrayInputStream(bytes),"camera.jpg","file",10000,10,1000);
        assertThat(result.segments()).isNotEmpty();
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).contentType()).isEqualTo("image/jpeg");
        assertThat(forwardedBytes).singleElement().satisfies(actual->assertThat(actual).containsExactly(bytes));
    }

    @Test void sharedOcrBusyAndUnknownAreNotCorruptDocumentFailuresAndReleaseLocalSlot() throws Exception {
        byte[] bytes;
        try(var out=new ByteArrayOutputStream()){ImageIO.write(new BufferedImage(20,40,BufferedImage.TYPE_INT_RGB),"png",out);bytes=out.toByteArray();}
        var busy=new OcrCapacityUnavailableException();var unknown=new OcrOutcomeUnprovenException(new IOException());
        for(RuntimeException failure:List.of(busy,unknown)) {
            int[] calls={0};var instance=reader(r->{if(++calls[0]==1)throw failure;return ocr.recognize(r);});
            assertThatThrownBy(()->instance.read(new ByteArrayInputStream(bytes),"x.png","file",10000,10,1000)).isSameAs(failure);
            assertThat(instance.read(new ByteArrayInputStream(bytes),"x.png","file",10000,10,1000).segments()).isNotEmpty();
        }
    }
    private final List<OcrRequest> calls=new ArrayList<>();
    private final OcrPort ocr=request->{calls.add(request);return new OcrResult(request.documentId(),"扫描末页内容",0.99,
        List.of(new OcrPage(1,"扫描末页内容",0.99)),false,"TEST",1);};
    private OfficeDocumentTextReader reader(OcrPort port){return new OfficeDocumentTextReader(input->{try{return input.readAllBytes();}catch(IOException e){throw new UncheckedIOException(e);}},port);}
    private String text(DocumentTextPort.Result result){return String.join("",result.segments().stream().map(MaterialFieldAiPort.Segment::text).toList());}
    private DocumentTextPort.Result read(byte[] bytes,String name) throws Exception {
        return reader(ocr).read(new ByteArrayInputStream(bytes),name,UUID.randomUUID().toString(),32L*1024*1024,100,192000);
    }
    @Test void wordIncludesBodyTableNestedTableHeaderFooterAndEmbeddedScan() throws Exception {
        try(var doc=new XWPFDocument();var out=new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("正文开头");
            doc.createTable(1,2).getRow(0).getCell(1).setText("第三表格末尾");
            doc.createHeader(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT).createParagraph().createRun().setText("页眉信息");
            doc.createFooter(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT).createParagraph().createRun().setText("页脚批注");
            byte[] picture;
            try(var imageOut=new ByteArrayOutputStream()){ImageIO.write(new BufferedImage(200,100,BufferedImage.TYPE_INT_RGB),"png",imageOut);picture=imageOut.toByteArray();}
            doc.createParagraph().createRun().addPicture(new ByteArrayInputStream(picture),XWPFDocument.PICTURE_TYPE_PNG,"scan.png",200,100);
            doc.write(out);
            var result=read(out.toByteArray(),"budget.docx");
            assertThat(text(result)).contains("正文开头","第三表格末尾","页眉信息","页脚批注","扫描末页内容");
            assertThat(calls).hasSize(1);
            assertThat(result.method()).isEqualTo("OFFICE_TEXT_OCR");
        }
    }
    @Test void bothExcelFormatsIncludeEverySheetEveryCellFormulaAndTail() throws Exception {
        for(var book:List.of(new XSSFWorkbook(),new HSSFWorkbook()))try(book;var out=new ByteArrayOutputStream()) {
            for(int s=0;s<3;s++) {
                var sheet=book.createSheet("页"+s);sheet.createRow(0).createCell(0).setCellValue("SHEET_"+s);
                var row=sheet.createRow(1);row.createCell(0).setCellValue(12);row.createCell(1).setCellValue(2600);row.createCell(2).setCellFormula("A2*B2");
                sheet.createRow(201).createCell(4).setCellValue("TAIL_"+s);
            }
            book.write(out);var result=read(out.toByteArray(),book instanceof XSSFWorkbook?"file.xlsx":"file.xls");
            assertThat(text(result)).contains("SHEET_0","SHEET_1","SHEET_2","31200","TAIL_0","TAIL_1","TAIL_2");
        }
    }
    @Test void wordBudgetTablesKeepTheirOwnColumnLabelsOnEveryRowAcrossModelChunks() throws Exception {
        try(var doc=new XWPFDocument();var out=new ByteArrayOutputStream()) {
            for(boolean reverse:List.of(false,true)) {
                var table=doc.createTable(21,3);
                String[] headers=reverse?new String[]{"批复参考金额","科目名称","申报金额"}:new String[]{"科目名称","申报金额","批复参考金额"};
                for(int col=0;col<3;col++)table.getRow(0).getCell(col).setText(headers[col]);
                for(int r=1;r<=20;r++)for(int col=0;col<3;col++)table.getRow(r).getCell(col)
                    .setText(headers[col].equals("科目名称")?"办公设备"+r:headers[col].equals("申报金额")?"31200":"32760");
            }
            doc.write(out);var result=read(out.toByteArray(),"tables.docx");
            assertThat(result.segments()).filteredOn(s->s.text().contains("申报金额：31200"))
                .hasSize(40).allSatisfy(s->assertThat(s.text()).contains("批复参考金额：32760","科目名称：办公设备"));
            assertThat(text(result)).contains("办公设备20");
        }
    }
    @Test void budgetComparisonUsesActualWordParagraphBoundariesAndEverySubject() throws Exception {
        try(var doc=new XWPFDocument();var out=new ByteArrayOutputStream()) {
            for(String paragraph:List.of("项目名称：仿真维修项目","项目编号：DOCX-VERTICAL",
                "科目名称：屋面维修","申报金额：贰万伍仟元整","批复金额：24000元",
                "科目名称：门窗维修","申报金额：8000元","批复金额：玖仟元整"))doc.createParagraph().createRun().setText(paragraph);
            doc.write(out);var source=read(out.toByteArray(),"申请.docx");
            MaterialFieldAiPort noModel=(kind,id,sha,segments)->{throw new AssertionError("Explicit fields need no model call");};
            var facts=new com.fuquan.audit.budget.application.BudgetUploadedMaterialReader(null,noModel,null).extract(source,"synthetic");
            var input=new com.fuquan.audit.budget.application.BudgetUploadedFacts.Input(UUID.randomUUID(),"申请.docx","PARSED",facts);
            var result=new com.fuquan.audit.budget.application.BudgetUploadedMaterialAnalyzer().analyze(List.of(input)).get(input.taskId());
            assertThat(result.lines()).hasSize(2);
            assertThat(result.lines()).anySatisfy(l->{assertThat(l.subject()).isEqualTo("屋面维修");assertThat(l.declaredAmount()).isEqualByComparingTo("25000");assertThat(l.status()).isEqualTo("OVER_APPROVAL");});
            assertThat(result.lines()).anySatisfy(l->{assertThat(l.subject()).isEqualTo("门窗维修");assertThat(l.declaredAmount()).isEqualByComparingTo("8000");assertThat(l.status()).isEqualTo("WITHIN_APPROVAL");});
        }
    }
    @Test void bothPowerPointFormatsReadEverySlideAndLastShape() throws Exception {
        try(var show=new XMLSlideShow();var out=new ByteArrayOutputStream()) {
            for(int i=0;i<12;i++)show.createSlide().createTextBox().setText("SLIDE_"+i);
            show.write(out);assertThat(text(read(out.toByteArray(),"deck.pptx"))).contains("SLIDE_0","SLIDE_11");
        }
        try(var show=new HSLFSlideShow();var out=new ByteArrayOutputStream()) {
            for(int i=0;i<12;i++){var slide=show.createSlide();var box=new HSLFTextBox();box.setText("OLD_SLIDE_"+i);slide.addShape(box);}
            show.write(out);assertThat(text(read(out.toByteArray(),"deck.ppt"))).contains("OLD_SLIDE_0","OLD_SLIDE_11");
        }
    }
    @Test void mixedPdfReadsScannedPageAndImageOnATextPage() throws Exception {
        try(var doc=new PDDocument();var out=new ByteArrayOutputStream()) {
            for(int i=0;i<3;i++) {
                var page=new PDPage();doc.addPage(page);
                try(var stream=new PDPageContentStream(doc,page)) {
                    if(i!=1){stream.beginText();stream.setFont(PDType1Font.HELVETICA,12);stream.newLineAtOffset(30,700);stream.showText("TEXT_PAGE_"+i);stream.endText();}
                    if(i!=0)stream.drawImage(LosslessFactory.createFromImage(doc,new BufferedImage(200,100,BufferedImage.TYPE_INT_RGB)),30,400);
                }
            }
            doc.save(out);var result=read(out.toByteArray(),"mixed.pdf");
            assertThat(text(result)).contains("TEXT_PAGE_0","TEXT_PAGE_2","扫描末页内容");
            assertThat(calls).hasSize(2);
            assertThat(result.segments()).anyMatch(s->s.locator().startsWith("pdf/page:2/ocr"));
            assertThat(result.segments()).anyMatch(s->s.locator().startsWith("pdf/page:3/ocr"));
        }
    }
    @Test void scannedPdfPageWithNoRecognizedTextFailsInsteadOfPublishingPartialDocument() throws Exception {
        try(var doc=new PDDocument();var out=new ByteArrayOutputStream()) {
            var page=new PDPage();doc.addPage(page);
            try(var stream=new PDPageContentStream(doc,page)) {
                stream.drawImage(LosslessFactory.createFromImage(doc,new BufferedImage(200,100,BufferedImage.TYPE_INT_RGB)),30,400);
            }
            doc.save(out);
            OcrPort empty=request->new OcrResult(request.documentId(),"",0.0,
                    List.of(new OcrPage(1,"",0.0)),false,"TEST",1);
            assertThatThrownBy(()->reader(empty).read(new ByteArrayInputStream(out.toByteArray()),
                    "scan.pdf","scan",32L*1024*1024,100,192000))
                    .hasMessage("OCR_PAGE_TEXT_EMPTY");
        }
    }
    @Test void utf8Gb18030CsvAndRtfPreserveChineseAndFinalCharacters() throws Exception {
        String data="科目名称,申报金额\n办公设备,31200\n最后一行🌟";
        assertThat(text(read(data.getBytes(StandardCharsets.UTF_8),"file.csv"))).isEqualTo(data);
        String gb="科目名称\t申报金额\n最后一行\t32760";
        assertThat(text(read(gb.getBytes(Charset.forName("GB18030")),"file.txt"))).isEqualTo(gb);
        String rtf="{\\rtf1\\ansi First \\par TAIL_REFERENCE}";
        assertThat(text(read(rtf.getBytes(StandardCharsets.US_ASCII),"file.rtf"))).contains("First","TAIL_REFERENCE");
    }
    @Test void longTextPartitionHasNoTruncatedTailOrBrokenSupplementaryCharacters() throws Exception {
        String source="项目材料\n"+"文🌟".repeat(50000)+"\nTAIL_COMPLETE";
        var result=read(source.getBytes(StandardCharsets.UTF_8),"large.txt");
        assertThat(text(result)).isEqualTo(source);
        assertThat(result.segments()).allMatch(s->s.text().length()<=4000&&!Character.isHighSurrogate(s.text().charAt(s.text().length()-1)));
        assertThat(result.segments().stream().map(MaterialFieldAiPort.Segment::locator).distinct().count()).isEqualTo(result.segments().size());
        String lines=("完整科目名称：办公设备；申报金额：31200元；备注："+"材料".repeat(50)+"\n").repeat(100)+"TAIL_COMPLETE";
        var rows=read(lines.getBytes(StandardCharsets.UTF_8),"lines.txt");
        assertThat(text(rows)).isEqualTo(lines);
        assertThat(rows.segments().subList(0,rows.segments().size()-1)).allSatisfy(s->assertThat(s.text()).endsWith("\n"));
    }
    @Test void partialOcrAndExceededBoundsNeverReturnPartialSuccess() throws Exception {
        byte[] png;try(var out=new ByteArrayOutputStream()){ImageIO.write(new BufferedImage(20,20,BufferedImage.TYPE_INT_RGB),"png",out);png=out.toByteArray();}
        OcrPort partial=request->new OcrResult(request.documentId(),"",0,List.of(),false,"TEST",1);
        assertThatThrownBy(()->reader(partial).read(new ByteArrayInputStream(png),"scan.png","test",10000,10,1000)).hasMessage("OCR_PAGE_COVERAGE_INVALID");
        assertThatThrownBy(()->reader(ocr).read(new ByteArrayInputStream("x".repeat(5000).getBytes()),"long.txt","test",10000,10,100)).hasMessage("TEXT_LIMIT_EXCEEDED");
        assertThatThrownBy(()->reader(ocr).read(new ByteArrayInputStream(png),"renamed.docx","test",10000,10,100)).hasMessage("DOCUMENT_SIGNATURE_INVALID");
        assertThatThrownBy(()->reader(ocr).read(new ByteArrayInputStream(png),"archive.zip","test",10000,10,100)).hasMessage("DOCUMENT_TYPE_UNSUPPORTED");
    }
    @Test void multiFrameTiffReadsLastFrame() throws Exception {
        byte[] bytes;
        try(var out=new ByteArrayOutputStream();var images=ImageIO.createImageOutputStream(out)) {
            var writer=ImageIO.getImageWritersByFormatName("TIFF").next();writer.setOutput(images);writer.prepareWriteSequence(null);
            for(int i=0;i<3;i++)writer.writeToSequence(new javax.imageio.IIOImage(new BufferedImage(20,20,BufferedImage.TYPE_INT_RGB),null,null),null);
            writer.endWriteSequence();writer.dispose();images.flush();bytes=out.toByteArray();
        }
        var result=read(bytes,"scan.tiff");assertThat(calls).hasSize(3);
        assertThat(result.segments()).anyMatch(s->s.locator().contains("frame:3"));
    }
}
