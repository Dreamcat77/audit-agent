package com.fuquan.audit.contract.application.project.parse;

import com.fuquan.audit.contract.domain.project.ProjectFile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

@Component
public class ProjectDocxTextParser implements ProjectDocumentParser {
    public static final String CANONICAL_LAYOUT_VERSION="DOCX_BODY_ROW_V2";
    public static final String IMAGE_LAYOUT_VERSION="DOCX_BODY_IMAGE_V3";
    private final ProjectDocxContainerGuard guard;
    private final com.fuquan.audit.review.application.port.OcrPort ocr;
    public ProjectDocxTextParser(ProjectDocxContainerGuard guard) { this(guard,null); }
    @org.springframework.beans.factory.annotation.Autowired
    public ProjectDocxTextParser(ProjectDocxContainerGuard guard,com.fuquan.audit.review.application.port.OcrPort ocr) {
        this.guard = guard;this.ocr=ocr;
    }

    @Override public boolean supports(ProjectFile file) {
        return file.originalFilename().toLowerCase(Locale.ROOT).endsWith(".docx");
    }

    @Override public ParsedProjectDocument parse(ProjectFile file, InputStream input) {
        return extract(file,input).document();
    }

    public ProjectDocumentExtraction extract(ProjectFile file,InputStream input) {
        long started=System.nanoTime();
        byte[] guarded = guard.validate(input);
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(guarded))) {
            var images=new DocxImageExtraction(file,ocr,started);
            List<ParsedProjectDocument.Segment> segments = new ArrayList<>();
            List<ParsedProjectDocument.Table> tables = new ArrayList<>();
            int paragraphIndex = 0;
            int tableIndex = 0;
            for (org.apache.poi.xwpf.usermodel.IBodyElement element : document.getBodyElements()) {
              if (element instanceof XWPFParagraph paragraph) {
                paragraphIndex++;
                if (!paragraph.getText().isBlank()) segments.add(new ParsedProjectDocument.Segment(
                        "docx/body/paragraph:" + paragraphIndex, paragraph.getText(), null, paragraphIndex));
                images.paragraph(paragraph,"docx/body/paragraph:"+paragraphIndex,segments);
              } else if (element instanceof XWPFTable table) {
                tableIndex++; int rowIndex = 0;
                String tableLocator = "docx/table:" + tableIndex;
                var sharedTable = com.fuquan.audit.integration.material.WordTableStructure.parse(table,tableLocator);
                var sharedRows = sharedTable.cells().stream().collect(java.util.stream.Collectors.groupingBy(
                        com.fuquan.audit.common.material.application.port.MaterialParsePort.Cell::rowNumber));
                List<ParsedProjectDocument.Row> rows = new ArrayList<>();
                for (XWPFTableRow row : table.getRows()) {
                    rowIndex++;
                    String rowLocator = tableLocator + "/row:" + rowIndex;
                    List<ParsedProjectDocument.Cell> cells = new ArrayList<>();
                    int physicalRow = rowIndex;
                    var sharedCells = sharedRows.getOrDefault(physicalRow,List.of());
                    for (var cell : sharedCells) cells.add(new ParsedProjectDocument.Cell(cell.locator(),cell.value()));
                    rows.add(new ParsedProjectDocument.Row(rowLocator, cells));
                    // Canonical newlines delimit physical rows, not individual cells.
                    // Typed cells retain their original text/locators for deterministic extraction.
                    if(cells.stream().anyMatch(cell->!cell.text().isBlank())) {
                        String rowText=com.fuquan.audit.integration.material.WordTableStructure.canonicalRow(sharedCells);
                        segments.add(new ParsedProjectDocument.Segment(rowLocator,rowText,null,null));
                    }
                    int cellImageIndex=0;
                    for(XWPFTableCell cell:row.getTableCells())images.body(cell,
                            rowLocator+"/cell:"+(++cellImageIndex),segments);
                }
                tables.add(new ParsedProjectDocument.Table(tableLocator, rows));
              }
            }
            int sectionIndex=0;
            for(var header:document.getHeaderList())images.body(header,"docx/header:"+(++sectionIndex),segments);
            sectionIndex=0;
            for(var footer:document.getFooterList())images.body(footer,"docx/footer:"+(++sectionIndex),segments);
            return new ProjectDocumentExtraction(new ParsedProjectDocument(segments,tables),images.provenance());
        } catch (com.fuquan.audit.review.application.port.OcrCapacityUnavailableException
                | com.fuquan.audit.review.application.port.OcrOutcomeUnprovenException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof ProjectParseException parse) throw parse;
            throw new ProjectParseException("DOCX_PARSE_FAILED", exception);
        }
    }
}
