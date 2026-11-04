package com.fuquan.audit.integration.material;

import com.fuquan.audit.common.material.application.OfficeMaterialTypes;
import com.fuquan.audit.common.material.application.port.DocumentTextPort;
import com.fuquan.audit.common.material.application.port.MaterialContainerPort;
import com.fuquan.audit.common.material.application.port.MaterialParsePort;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Segment;
import com.fuquan.audit.common.observability.PipelineDiagnostics;
import com.fuquan.audit.review.application.port.*;
import com.fuquan.audit.contract.application.project.parse.ProjectParseException;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.hslf.usermodel.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.rendering.*;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.w3c.dom.Node;

/** Office parsing and page/image OCR adapter. Never invokes macros, external links or a desktop office app. */
@Component
public class OfficeDocumentTextReader implements DocumentTextPort {
    private static final String PDF_CHECKPOINT_POLICY="office-pdf-pages-v1";
    private final MaterialContainerPort docxGuard;
    private final OcrPort ocr;
    private final java.util.concurrent.Semaphore ocrCapacity=new java.util.concurrent.Semaphore(1,true);
    public OfficeDocumentTextReader(@Qualifier("procurementDocxGuard") MaterialContainerPort docxGuard, OcrPort ocr) {
        this.docxGuard=docxGuard; this.ocr=ocr;
    }
    @Override public Result read(InputStream input,String fileName,String id,long maxBytes,int maxPages,int maxChars)
            throws IOException {
        return read(input,fileName,id,maxBytes,maxPages,maxChars,null,null);
    }
    @Override public Result read(InputStream input,String fileName,String id,long maxBytes,int maxPages,int maxChars,
            PdfCheckpoint checkpoint,java.util.function.Consumer<PdfCheckpoint> progress) throws IOException {
        String media=OfficeMaterialTypes.mediaType(fileName);
        if(media==null) throw new ReadFailure("DOCUMENT_TYPE_UNSUPPORTED");
        String extension=OfficeMaterialTypes.extension(fileName);
        Path temporary=Files.createTempFile("audit-office-", "."+extension);
        try(var span=PipelineDiagnostics.start("MATERIAL","OFFICE_TEXT",PipelineDiagnostics.resourceId(id),Map.of("byteLimit",maxBytes,"textLimit",maxChars))) {
            try {
            var digest=MessageDigest.getInstance("SHA-256"); long size=0;
            try(var out=Files.newOutputStream(temporary)) {
                byte[] buffer=new byte[65536]; int count;
                while((count=input.read(buffer))!=-1) {
                    size+=count; if(size>maxBytes) throw new ReadFailure("FILE_LIMIT_EXCEEDED");
                    out.write(buffer,0,count); digest.update(buffer,0,count);
                }
            }
            try(var in=Files.newInputStream(temporary)) {
                if(!OfficeMaterialTypes.validSignature(media,in.readNBytes(16))) throw new ReadFailure("DOCUMENT_SIGNATURE_INVALID");
            }
            String sourceSha=HexFormat.of().formatHex(digest.digest());
            if(checkpoint!=null&&(!sourceSha.equals(checkpoint.sourceSha256())||!extension.equals("pdf")))
                throw new ReadFailure("READ_CHECKPOINT_SOURCE_MISMATCH");
            var collector=new Collector(maxChars,maxPages,id);
            if(progress!=null)collector.ocrBudgetNanos=java.util.concurrent.TimeUnit.HOURS.toNanos(4);
            String ext=OfficeMaterialTypes.extension(fileName);
            switch(ext) {
                case "docx" -> word(temporary,collector);
                case "doc" -> legacyWord(temporary,collector);
                case "xls","xlsx" -> spreadsheet(temporary,collector);
                case "ppt","pptx" -> slides(temporary,collector,ext.equals("pptx"));
                case "pdf" -> pdf(temporary,collector,sourceSha,checkpoint,progress);
                case "txt","csv" -> collector.add("text/body",decode(temporary,maxChars));
                case "rtf" -> {
                    if(size>32L*1024*1024)throw new ReadFailure("RTF_RESOURCE_LIMIT_EXCEEDED");
                    var kit=new javax.swing.text.rtf.RTFEditorKit(); var doc=kit.createDefaultDocument();
                    try(var in=Files.newInputStream(temporary)) { kit.read(in,doc,0); }
                    collector.add("rtf/body",doc.getText(0,doc.getLength()));
                    // RTF pictures and embedded objects cannot be silently omitted.
                    try(var in=Files.newInputStream(temporary)) {
                        String raw=new String(in.readNBytes((int)Math.min(size,32L*1024*1024)),StandardCharsets.ISO_8859_1);
                        if(size>32L*1024*1024 || raw.contains("\\pict") || raw.contains("\\object")) throw new ReadFailure("RTF_EMBEDDED_CONTENT_UNSUPPORTED");
                    }
                }
                default -> image(temporary,collector, "png".equals(ext) || "jpg".equals(ext) || "jpeg".equals(ext));
            }
            if(collector.segments.isEmpty()) {if(collector.firstOcrFailure!=null)throw collector.firstOcrFailure;throw new ReadFailure("DOCUMENT_TEXT_EMPTY");}
            span.success(Map.of("outputChars",collector.chars,"segmentCount",collector.segments.size(),
                    "pageCount",collector.units,"itemCount",collector.ocrUnits,
                    "nativeTextChars",collector.chars-collector.ocrChars,"ocrTextChars",collector.ocrChars,
                    "emptyOcrPages",collector.emptyOcrPages));
            return new Result(List.copyOf(collector.segments),(collector.ocrUnits>0?"OFFICE_TEXT_OCR":"OFFICE_TEXT")+(collector.gaps.isEmpty()?"":"_PARTIAL"),sourceSha,collector.tables,collector.provenance,collector.gaps);
            } catch(Exception | Error failure) { span.fail(failure); throw failure; }
        } catch(ProgressFailure e) {throw (RuntimeException)e.getCause();}
          catch(OcrCapacityUnavailableException|OcrOutcomeUnprovenException e) { throw e; }
          catch(ReadFailure e) { throw e; }
          catch(ProjectParseException|org.apache.poi.ooxml.POIXMLException e) { throw new ReadFailure("DOCUMENT_CONTAINER_INVALID",e); }
          catch(IOException e) { throw new ReadFailure("DOCUMENT_READ_FAILED",e); }
          catch(Exception e) { throw new ReadFailure("DOCUMENT_PARSE_FAILED",e); }
        finally { Files.deleteIfExists(temporary); }
    }
    private void word(Path file,Collector c) throws IOException {
        try(var input=Files.newInputStream(file);var doc=new XWPFDocument(new ByteArrayInputStream(docxGuard.validate(input)))) {
            c.unit();
            checkWordObjects(doc.getDocument().getDomNode());
            int tableIndex=0;
            for(var table:doc.getTables()) {
                var parsed=WordTableStructure.parse(table,"docx/table:"+(++tableIndex));
                if(c.tables.stream().mapToLong(t->t.cells().size()).sum()+parsed.cells().size()>100000)
                    throw new ReadFailure("WORD_TABLE_CELL_LIMIT");
                c.tables.add(parsed);
            }
            // DOM includes nested tables, textboxes and document order, unlike getParagraphs()+getTables().
            wordNodes(doc.getDocument().getDomNode(),c,"docx/body",new int[]{0});
            for(var relation:doc.getRelations())if(relation.getPackagePart().getPartName().getName().contains("/embeddings/"))
                throw new ReadFailure("EMBEDDED_OBJECT_UNSUPPORTED");
            int index=0;
            for(var header:doc.getHeaderList()) c.add("docx/header:"+(++index),wordXml(header._getHdrFtr().getDomNode()));
            index=0;
            for(var footer:doc.getFooterList()) c.add("docx/footer:"+(++index),wordXml(footer._getHdrFtr().getDomNode()));
            index=0;
            for(var note:doc.getFootnotes()) c.add("docx/footnote:"+(++index),wordXml(note.getCTFtnEdn().getDomNode()));
            index=0;
            for(var note:doc.getEndnotes()) c.add("docx/endnote:"+(++index),wordXml(note.getCTFtnEdn().getDomNode()));
            index=0;
            for(var picture:doc.getAllPictures()) imageBytes(picture.getData(),c,"docx/image:"+(++index));
        }
    }
    private static String wordXml(Node node) {
        var out=new StringBuilder(); appendWord(node,out); return out.toString();
    }
    private static void wordNodes(Node node,Collector c,String prefix,int[] counter) throws ReadFailure {
        String name=node.getLocalName();
        if("altChunk".equals(name)||"object".equals(name))throw new ReadFailure("EMBEDDED_OBJECT_UNSUPPORTED");
        if("tbl".equals(name)&&budgetTableRows(node,c,prefix,counter))return;
        if("tr".equals(name)||"p".equals(name)) {
            c.add(prefix+"/node:"+(++counter[0]),wordXml(node));return;
        }
        for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling())wordNodes(child,c,prefix,counter);
    }
    private static boolean budgetTableRows(Node table,Collector c,String prefix,int[] counter) throws ReadFailure {
        var rows=new ArrayList<Node>();
        for(Node child=table.getFirstChild();child!=null;child=child.getNextSibling())
            if("tr".equals(child.getLocalName()))rows.add(child);
        if(rows.isEmpty())return false;
        var headers=wordCells(rows.get(0));
        if(headers.stream().noneMatch(h->h.contains("科目"))
                ||headers.stream().noneMatch(h->h.contains("申报金额")||h.contains("申请金额")))return false;
        // Carry the table's own labels into each row, so bounded model chunks
        // cannot lose column meaning after the first chunk. Never guess labels
        // from data rows or flatten a nested table into assigned columns.
        for(Node row:rows) {
            var cells=wordCells(row);
            if(cells.size()!=headers.size()||hasWordTable(row))return false;
        }
        c.add(prefix+"/node:"+(++counter[0]),wordXml(rows.get(0)));
        for(int r=1;r<rows.size();r++) {
            var cells=wordCells(rows.get(r));var labelled=new ArrayList<String>();
            for(int col=0;col<cells.size();col++)labelled.add(headers.get(col)+"："+cells.get(col));
            c.add(prefix+"/node:"+(++counter[0]),String.join("；",labelled));
        }
        return true;
    }
    private static List<String> wordCells(Node row) {
        var cells=new ArrayList<String>();
        for(Node cell=row.getFirstChild();cell!=null;cell=cell.getNextSibling())
            if("tc".equals(cell.getLocalName())) {
                var value=new StringBuilder();
                for(Node child=cell.getFirstChild();child!=null;child=child.getNextSibling())appendWord(child,value);
                cells.add(value.toString().strip());
            }
        return cells;
    }
    private static boolean hasWordTable(Node node) {
        for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling())
            if("tbl".equals(child.getLocalName())||hasWordTable(child))return true;
        return false;
    }
    private static void checkWordObjects(Node node) throws ReadFailure {
        if("altChunk".equals(node.getLocalName())||"object".equals(node.getLocalName()))throw new ReadFailure("EMBEDDED_OBJECT_UNSUPPORTED");
        for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling())checkWordObjects(child);
    }
    private static void appendWord(Node node,StringBuilder out) {
        String name=node.getLocalName();
        if("t".equals(name)) { for(Node text=node.getFirstChild();text!=null;text=text.getNextSibling())
            if(text.getNodeValue()!=null)out.append(text.getNodeValue()); return; }
        if("tab".equals(name)) out.append('\t');
        if("br".equals(name)||"cr".equals(name)) out.append('\n');
        for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling()) appendWord(child,out);
        if("tc".equals(name)) out.append(" | ");
        if("p".equals(name)||"tr".equals(name)) out.append('\n');
    }
    private void legacyWord(Path file,Collector c) throws IOException {
        try(var in=Files.newInputStream(file);var doc=new HWPFDocument(in);var extractor=new WordExtractor(doc)) {
            c.unit(); c.add("doc/body",extractor.getText());
            int index=0;
            for(var picture:doc.getPicturesTable().getAllPictures()) imageBytes(picture.getContent(),c,"doc/image:"+(++index));
        }
    }
    private void spreadsheet(Path file,Collector c) throws IOException {
        com.fuquan.audit.common.material.infra.SpreadsheetSafety.verify(file);
        try(var book=WorkbookFactory.create(file.toFile(),null,true)) {
            var formatter=new DataFormatter(Locale.ROOT); var evaluator=book.getCreationHelper().createFormulaEvaluator();
            for(int s=0;s<book.getNumberOfSheets();s++) {
                c.unit(); var sheet=book.getSheetAt(s);
                var typedCells=new ArrayList<MaterialParsePort.Cell>();
                for(var row:sheet) {
                    var cells=new ArrayList<String>();
                    for(int col=0;col<row.getLastCellNum();col++) {
                        var cell=row.getCell(col);
                        String sourceRaw=rawCell(cell);
                        String sourceCached=cell!=null&&cell.getCellType()==CellType.FORMULA?rawCachedCell(cell):null;
                        String displayed=formatter.formatCellValue(cell,evaluator);
                        cells.add(displayed);
                        if(typedCells.size()>=100000)throw new ReadFailure("SPREADSHEET_CELL_LIMIT_EXCEEDED");
                        String merged=null;
                        for(var range:sheet.getMergedRegions())if(range.isInRange(row.getRowNum(),col)){merged=range.formatAsString();break;}
                        typedCells.add(new MaterialParsePort.Cell("excel/sheet:"+(s+1)+"/row:"+(row.getRowNum()+1)+"/col:"+(col+1),
                                row.getRowNum()+1,col+1,displayed,cell==null?"BLANK":cell.getCellType().name(),
                                cell!=null&&cell.getCellType()==CellType.FORMULA?cell.getCellFormula():null,
                                sourceRaw,sourceCached,
                                cell==null?null:cell.getCellStyle().getDataFormatString(),merged));
                    }
                    c.add("excel/sheet:"+(s+1)+"/row:"+(row.getRowNum()+1),String.join(" | ",cells));
                }
                c.tables.add(new MaterialParsePort.Table("excel/sheet:"+(s+1),typedCells,false));
                var drawing=sheet.getDrawingPatriarch();
                if(drawing!=null) for(Object shape:drawing) {
                    if(shape instanceof org.apache.poi.ss.usermodel.Picture picture)
                        imageBytes(picture.getPictureData().getData(),c,"excel/sheet:"+(s+1)+"/image:"+(++c.images));
                }
            }
        }
    }
    private static String rawCell(org.apache.poi.ss.usermodel.Cell cell) {
        if(cell==null)return null;
        if(cell instanceof org.apache.poi.xssf.usermodel.XSSFCell x)return x.getRawValue();
        return cell.toString();
    }
    private static String rawCachedCell(org.apache.poi.ss.usermodel.Cell cell) {
        if(cell instanceof org.apache.poi.xssf.usermodel.XSSFCell x)return x.getRawValue();
        return switch(cell.getCachedFormulaResultType()) {
            case NUMERIC -> org.apache.poi.ss.util.NumberToTextConverter.toText(cell.getNumericCellValue());
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            default -> null;
        };
    }
    private void slides(Path file,Collector c,boolean xml) throws IOException {
        if(xml) try(var in=Files.newInputStream(file);var show=new XMLSlideShow(in)) {
            int index=0;
            for(var slide:show.getSlides()) {
                c.unit(); String prefix="pptx/slide:"+(++index); int shape=0;
                for(var element:slide.getShapes()) slideShape(element,c,prefix+"/shape:"+(++shape));
                if(slide.getNotes()!=null) for(var element:slide.getNotes().getShapes())
                    if(element instanceof XSLFTextShape text) c.add(prefix+"/note:"+(++shape),text.getText());
            }
        } else try(var in=Files.newInputStream(file);var show=new HSLFSlideShow(in)) {
            int index=0;
            for(var slide:show.getSlides()) {
                c.unit();String prefix="ppt/slide:"+(++index);int shape=0;
                for(var element:slide.getShapes()) legacySlideShape(element,c,prefix+"/shape:"+(++shape));
                if(slide.getNotes()!=null) for(var paragraph:slide.getNotes().getTextParagraphs()) {
                    var text=new StringBuilder();for(var p:paragraph) for(var run:p.getTextRuns())text.append(run.getRawText());
                    c.add(prefix+"/note:"+(++shape),text.toString());
                }
            }
        }
    }
    private void slideShape(XSLFShape shape,Collector c,String locator) throws IOException {
        if(shape instanceof XSLFTextShape text) c.add(locator,text.getText());
        else if(shape instanceof XSLFTable table) {
            int i=0;for(var row:table.getRows())c.add(locator+"/row:"+(++i),String.join(" | ",row.getCells().stream().map(XSLFTableCell::getText).toList()));
        } else if(shape instanceof XSLFPictureShape picture) imageBytes(picture.getPictureData().getData(),c,locator+"/image");
        else if(shape instanceof XSLFGroupShape group) {int i=0;for(var child:group.getShapes())slideShape(child,c,locator+"/child:"+(++i));}
        else if(shape instanceof XSLFObjectShape) throw new ReadFailure("EMBEDDED_OBJECT_UNSUPPORTED");
    }
    private void legacySlideShape(HSLFShape shape,Collector c,String locator) throws IOException {
        if(shape instanceof HSLFTextShape text)c.add(locator,text.getText());
        else if(shape instanceof HSLFPictureShape picture)imageBytes(picture.getPictureData().getData(),c,locator+"/image");
        else if(shape instanceof HSLFGroupShape group){int i=0;for(var child:group.getShapes())legacySlideShape(child,c,locator+"/child:"+(++i));}
        else if(shape instanceof HSLFObjectShape)throw new ReadFailure("EMBEDDED_OBJECT_UNSUPPORTED");
    }
    private void pdf(Path file,Collector c,String sourceSha,PdfCheckpoint checkpoint,
            java.util.function.Consumer<PdfCheckpoint> progress) throws IOException {
        try(var document=PDDocument.load(file.toFile(),MemoryUsageSetting.setupTempFileOnly())) {
            if(document.isEncrypted())throw new ReadFailure("DOCUMENT_ENCRYPTED");
            if(document.getNumberOfPages()>c.maxPages)throw new ReadFailure("PAGE_LIMIT_EXCEEDED");
            int pageCount=document.getNumberOfPages();
            var completed=new TreeSet<Integer>();
            if(checkpoint!=null) {
                if(!PDF_CHECKPOINT_POLICY.equals(checkpoint.policy())||checkpoint.pageCount()!=pageCount
                    ||checkpoint.completedPages().size()!=new HashSet<>(checkpoint.completedPages()).size()
                    ||checkpoint.completedPages().stream().anyMatch(p->p==null||p<1||p>pageCount)
                    ||checkpoint.segments().stream().anyMatch(s->!checkpoint.completedPages().contains(pdfPage(s.locator())))
                    ||checkpoint.provenance().stream().anyMatch(p->!checkpoint.completedPages().contains(pdfPage(p.locator()))))
                    throw new ReadFailure("READ_CHECKPOINT_INVALID");
                completed.addAll(checkpoint.completedPages());
                for(var segment:checkpoint.segments())c.addExact(segment);
                c.provenance.addAll(checkpoint.provenance());c.ocrUnits=c.provenance.size();
                c.ocrChars=c.segments.stream().filter(s->s.locator().contains("/ocr/")).mapToInt(s->s.text().length()).sum();
            }
            var stripper=new PDFTextStripper(); stripper.setSortByPosition(true);
            var scanPages=new ArrayList<Integer>();
            // Read every native page first. A failing scanned page cannot discard
            // native evidence elsewhere, including pages later in the document.
            for(int page=0;page<document.getNumberOfPages();page++) {
                c.unit();if(completed.contains(page+1))continue;
                stripper.setStartPage(page+1);stripper.setEndPage(page+1);
                String text=stripper.getText(document);c.add("pdf/page:"+(page+1)+"/text",text);
                boolean useful=new PdfTextUsefulness().isUseful(text);
                boolean hasContent=document.getPage(page).hasContents();
                boolean supplemental=useful&&PdfImageCoverage.hasLargeImage(document.getPage(page));
                if(hasContent&&(!useful||supplemental))scanPages.add(page);
                else completed.add(page+1);
                try(var span=PipelineDiagnostics.start("MATERIAL","PDF_PAGE_TEXT",PipelineDiagnostics.resourceId(c.id),
                        Map.of("pageCount",page+1,"inputChars",text.length()))) {
                    span.outcome(supplemental?"MIXED_PAGE_OCR":useful?"NATIVE_TEXT":hasContent?"OCR_REQUIRED":"BLANK_PAGE",Map.of());
                }
            }
            checkpointPdf(c,sourceSha,pageCount,completed,progress);
            // Process a page as a PDF, retaining crop/rotation and the OCR
            // adapter's own rendering/orientation policy rather than re-encoding.
            for(int page:scanPages) {
                int pageNumber=page+1;
                Path selected=Files.createTempFile("audit-office-page-", ".pdf");
                try {
                    try(var single=new PDDocument()) {
                        single.importPage(document.getPage(page));single.save(selected.toFile());
                    }
                    c.tryOcr(selected,"pdf/page:"+(page+1)+"/ocr",true,"page.pdf","application/pdf");
                    if(c.gaps.stream().noneMatch(g->g.startsWith("UNREAD:pdf/page:"+pageNumber+"/")))completed.add(pageNumber);
                    checkpointPdf(c,sourceSha,pageCount,completed,progress);
                } finally {Files.deleteIfExists(selected);}
            }
            // Canonical evidence follows page order even though OCR was deferred.
            c.segments.sort(Comparator.comparingInt(segment -> pdfPage(segment.locator())));
        }
    }
    private static void checkpointPdf(Collector c,String sha,int count,Set<Integer> completed,
            java.util.function.Consumer<PdfCheckpoint> progress) {
        if(progress==null)return;
        try {progress.accept(new PdfCheckpoint(sha,PDF_CHECKPOINT_POLICY,count,List.copyOf(completed),
                c.segments.stream().filter(s->completed.contains(pdfPage(s.locator())))
                    .sorted(Comparator.comparingInt(s->pdfPage(s.locator()))).toList(),
                c.provenance.stream().filter(p->completed.contains(pdfPage(p.locator()))).toList()));}
        catch(RuntimeException failure){throw new ProgressFailure(failure);}
    }
    private static final class ProgressFailure extends RuntimeException {ProgressFailure(RuntimeException cause){super(cause);}}
    private static int pdfPage(String locator) {
        var matcher=java.util.regex.Pattern.compile("pdf/page:(\\d+)").matcher(locator);
        return matcher.find()?Integer.parseInt(matcher.group(1)):Integer.MAX_VALUE;
    }
    private static boolean hasImages(PDResources resources,Set<PDResources> visited) throws IOException {
        if(resources==null||!visited.add(resources))return false;
        for(var name:resources.getXObjectNames()) {
            var object=resources.getXObject(name);
            if(object instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject)return true;
            if(object instanceof org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject form&&hasImages(form.getResources(),visited))return true;
        }
        return false;
    }
    private static boolean hasInlineImages(PDPage page) throws IOException {
        var parser=new org.apache.pdfbox.pdfparser.PDFStreamParser(page);parser.parse();
        return parser.getTokens().stream().anyMatch(token->token instanceof org.apache.pdfbox.contentstream.operator.Operator op&&"BI".equals(op.getName()));
    }
    private void imageBytes(byte[] bytes,Collector c,String locator) throws IOException {
        try {if(!c.imageHashes.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))return;}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        String suffix = isJpeg(bytes) ? ".jpg" : isPng(bytes) ? ".png" : ".image";
        Path image=Files.createTempFile("audit-office-image-", suffix);
            try {Files.write(image,bytes);image(image,c,locator,suffix.equals(".jpg")||suffix.equals(".png"));}finally{Files.deleteIfExists(image);}
    }
    private static boolean isJpeg(byte[] bytes) {return bytes.length>=3&&(bytes[0]&255)==255&&(bytes[1]&255)==216&&(bytes[2]&255)==255;}
    private static boolean isPng(byte[] bytes) {return bytes.length>=8&&(bytes[0]&255)==137&&bytes[1]==80&&bytes[2]==78&&bytes[3]==71&&bytes[4]==13&&bytes[5]==10&&bytes[6]==26&&bytes[7]==10;}
    private void image(Path file,Collector c) throws IOException {image(file,c,"image",false);}
    private void image(Path file,Collector c,String locator) throws IOException {image(file,c,locator,false);}
    private void image(Path file,Collector c,boolean preserveSourceOrientation) throws IOException {
        image(file,c,"image",preserveSourceOrientation);
    }
    private void image(Path file,Collector c,String locator,boolean preserveSourceOrientation) throws IOException {
        try(var input=ImageIO.createImageInputStream(file.toFile())) {
            var readers=ImageIO.getImageReaders(input);
            if(!readers.hasNext())throw new ReadFailure("IMAGE_FORMAT_UNSUPPORTED");
            var reader=readers.next();
            try {
                reader.setInput(input);int frames=reader.getNumImages(true);
                for(int i=0;i<frames;i++) {
                    c.unit();if((long)reader.getWidth(i)*reader.getHeight(i)>40_000_000)throw new ReadFailure("IMAGE_PIXEL_LIMIT_EXCEEDED");
                    // Keep EXIF orientation for a single-frame JPEG/PNG. The
                    // Python OCR adapter applies the orientation before OCR;
                    // decoding and re-encoding here would discard that metadata.
                    if(preserveSourceOrientation && frames==1 && i==0) {
                        c.tryOcr(file,locator+"/frame:1",true,null,null);
                        continue;
                    }
                    var image=reader.read(i);Path png=Files.createTempFile("audit-office-frame-", ".png");
                    try {ImageIO.write(image,"png",png.toFile());c.tryOcr(png,locator+"/frame:"+(i+1),true,null,null);}
                    finally {image.flush();Files.deleteIfExists(png);}
                }
            } finally {reader.dispose();}
        }
    }
    private static String decode(Path file,int maxChars) throws IOException {
        if(Files.size(file)>4L*maxChars+4)throw new ReadFailure("TEXT_LIMIT_EXCEEDED");
        byte[] bytes=Files.readAllBytes(file);String text=null;
        var charsets=List.of(StandardCharsets.UTF_8,Charset.forName("GB18030"));
        if(bytes.length>=2&&((bytes[0]==(byte)0xff&&bytes[1]==(byte)0xfe)||(bytes[0]==(byte)0xfe&&bytes[1]==(byte)0xff)))charsets=List.of(StandardCharsets.UTF_16);
        for(var charset:charsets)try {text=charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();break;}catch(CharacterCodingException ignored){}
        if(text==null||text.indexOf('\0')>=0)throw new ReadFailure("TEXT_ENCODING_INVALID");
        return text.startsWith("\ufeff")?text.substring(1):text;
    }
    private final class Collector {
        final List<MaterialParsePort.Table> tables=new ArrayList<>();
        final List<MaterialParsePort.OcrProvenance> provenance=new ArrayList<>();
        final List<String> gaps=new ArrayList<>();
        final Set<String> imageHashes=new HashSet<>();
        final long startedNanos=System.nanoTime();
        long ocrBudgetNanos=java.util.concurrent.TimeUnit.HOURS.toNanos(4);
        boolean ocrStopped;
        ReadFailure firstOcrFailure;
        final List<Segment> segments=new ArrayList<>();final int maxChars,maxPages;final String id;
        int chars,units,ocrUnits,images,ocrChars,emptyOcrPages;
        Collector(int maxChars,int maxPages,String id){this.maxChars=maxChars;this.maxPages=maxPages;this.id=id;}
        void unit() throws ReadFailure {if(++units>maxPages)throw new ReadFailure("PAGE_LIMIT_EXCEEDED");}
        void add(String locator,String text) throws ReadFailure {
            if(text==null||text.isBlank())return;
            if(text.length()>maxChars-chars)throw new ReadFailure("TEXT_LIMIT_EXCEEDED");
            chars+=text.length();
            int part=0;
            for(int start=0;start<text.length();) {
                int end=Math.min(text.length(),start+4000);
                if(end<text.length()) {
                    int newline=text.lastIndexOf('\n',end-1);
                    if(newline>=start)end=newline+1;
                }
                if(end<text.length()&&Character.isHighSurrogate(text.charAt(end-1)))end--;
                if(segments.size()>=10000)throw new ReadFailure("SEGMENT_LIMIT_EXCEEDED");
                // PDF offsets must remain stable when completed pages are
                // restored before newly read pages. Other formats keep history.
                segments.add(new Segment(locator+"/part:"+(locator.startsWith("pdf/page:")?++part:segments.size()+1),text.substring(start,end)));start=end;
            }
        }
        void addExact(Segment segment) throws ReadFailure {
            if(segment.text()==null||segment.text().isBlank()||segment.text().length()>maxChars-chars
                ||segments.size()>=10000||segments.stream().anyMatch(s->s.locator().equals(segment.locator())))
                throw new ReadFailure("READ_CHECKPOINT_INVALID");
            chars+=segment.text().length();segments.add(segment);
        }
        void tryOcr(Path file,String locator,boolean requiredText,String fileName,String contentType) throws IOException {
            if(Thread.currentThread().isInterrupted())throw new ReadFailure("DOCUMENT_INTERRUPTED");
            if(ocrStopped||System.nanoTime()-startedNanos>ocrBudgetNanos) {
                gaps.add("UNREAD:"+locator+":OCR_BUDGET_EXHAUSTED");return;
            }
            try {
                if(fileName==null)ocr(file,locator,requiredText);
                else ocr(file,locator,requiredText,fileName,contentType);
            } catch(OcrCapacityUnavailableException|OcrOutcomeUnprovenException failure) {
                if(segments.isEmpty())throw failure;
                ocrStopped=true;gaps.add("UNREAD:"+locator+":OCR_OUTCOME_UNPROVEN");
            } catch(ReadFailure failure) {
                if(!failure.code().startsWith("OCR_"))throw failure;
                if(firstOcrFailure==null)firstOcrFailure=failure;
                gaps.add("UNREAD:"+locator+":"+failure.code());
            }
            if(!gaps.isEmpty())try(var span=PipelineDiagnostics.start("MATERIAL","READ_COVERAGE",PipelineDiagnostics.resourceId(id),Map.of("itemCount",gaps.size(),"outputChars",chars))) {
                span.outcome("PARTIAL",Map.of());
            }
        }
        void ocr(Path file,String locator,boolean requiredText) throws IOException {
            String name=file.getFileName().toString().toLowerCase(Locale.ROOT);
            String contentType=name.endsWith(".jpg")||name.endsWith(".jpeg")?"image/jpeg":"image/png";
            ocr(file,locator,requiredText,name,contentType);
        }
        void ocr(Path file,String locator,boolean requiredText,String fileName,String contentType) throws IOException {
            var source=new OcrDocumentSource(){
                public InputStream openStream() throws IOException{return Files.newInputStream(file);}
                public long contentLength(){try{return Files.size(file);}catch(IOException e){throw new UncheckedIOException(e);}}
            };
            OcrResult result;boolean acquired=false;
            try{
                acquired=ocrCapacity.tryAcquire(120,java.util.concurrent.TimeUnit.SECONDS);
                if(!acquired)throw new ReadFailure("OCR_CAPACITY_TIMEOUT");
                result=ocr.recognize(new OcrRequest(id,fileName,contentType,source));
            } catch(InterruptedException e){Thread.currentThread().interrupt();throw new ReadFailure("DOCUMENT_INTERRUPTED",e);}
              catch(OcrCapacityUnavailableException|OcrOutcomeUnprovenException e){throw e;}
              catch(com.fuquan.audit.common.exception.ExternalServiceException e){throw new OcrOutcomeUnprovenException(e);}
              catch(RuntimeException e){throw new ReadFailure("OCR_UNAVAILABLE",e);}
            finally {if(acquired)ocrCapacity.release();}
            if(result==null||!id.equals(result.documentId())||result.simulated()||result.pages().size()!=1||result.pages().get(0).pageNumber()!=1)throw new ReadFailure("OCR_PAGE_COVERAGE_INVALID");
            ++ocrUnits;
            provenance.add(new MaterialParsePort.OcrProvenance(locator,result.engine(),null,"USED",result.confidence()));
            String text=result.pages().get(0).text();
            int outputChars=text.codePointCount(0,text.length());
            ocrChars+=outputChars;
            try(var span=PipelineDiagnostics.start("MATERIAL","OCR_PAGE_TEXT",PipelineDiagnostics.resourceId(id),
                    Map.of("pageCount",ocrUnits,"outputChars",outputChars))) {
                span.outcome(text.isBlank()?"EMPTY":"TEXT_RETURNED",Map.of());
            }
            if(text.isBlank()) {
                ++emptyOcrPages;
                if(requiredText)throw new ReadFailure("OCR_PAGE_TEXT_EMPTY");
            }
            add(locator,text);
        }
    }
}
