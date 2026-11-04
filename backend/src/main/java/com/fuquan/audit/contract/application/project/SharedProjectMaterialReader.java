package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.material.application.port.MaterialParsePort;
import com.fuquan.audit.contract.application.project.parse.*;
import com.fuquan.audit.contract.domain.project.*;
import java.math.BigDecimal;
import java.util.*;

/** Converts the shared parser's evidence without re-reading or inventing source cells. */
public final class SharedProjectMaterialReader {
    private SharedProjectMaterialReader() {}
    public static ProjectDocumentExtraction convert(ProjectFile file,MaterialParsePort.ParseResult result,long durationMs) {
        if(!result.usable())throw new ProjectParseException("DOCUMENT_PARSE_FAILED");
        var segments=result.paragraphs().stream().map(p->new ParsedProjectDocument.Segment(p.locator(),p.text(),page(p.locator()),null)).toList();
        var tables=new ArrayList<ParsedProjectDocument.Table>();
        for(var table:result.tables()) {
            var rows=new LinkedHashMap<Integer,List<ParsedProjectDocument.Cell>>();
            for(var cell:table.cells())rows.computeIfAbsent(cell.rowNumber(),key->new ArrayList<>())
                    .add(new ParsedProjectDocument.Cell(cell.locator(),cell.value()));
            tables.add(new ParsedProjectDocument.Table(table.locator(),rows.entrySet().stream()
                    .map(e->new ParsedProjectDocument.Row(table.locator()+"/row:"+e.getKey(),e.getValue())).toList()));
        }
        boolean partial=result.coverageGaps().stream().anyMatch(g->g.startsWith("UNREAD:"));
        boolean recognized=!result.ocrProvenance().isEmpty();
        var method=recognized?ProjectFileParseMethod.OCR:file.originalFilename().toLowerCase(Locale.ROOT).endsWith(".pdf")
                ?ProjectFileParseMethod.PDF_TEXT:file.originalFilename().toLowerCase(Locale.ROOT).endsWith(".doc")
                ?ProjectFileParseMethod.DOC_TEXT:ProjectFileParseMethod.DOCX_TEXT;
        var scores=result.ocrProvenance().stream().map(MaterialParsePort.OcrProvenance::confidence).filter(Objects::nonNull).toList();
        BigDecimal confidence=scores.isEmpty()?null:scores.stream().map(BigDecimal::valueOf).reduce(BigDecimal.ZERO,BigDecimal::add)
                .divide(BigDecimal.valueOf(scores.size()),4,java.math.RoundingMode.HALF_UP);
        return new ProjectDocumentExtraction(new ParsedProjectDocument(segments,tables),new ProjectFileParseProvenance(
                method,partial?"SHARED_MATERIAL_PARTIAL":"SHARED_MATERIAL",confidence,
                result.pages().isEmpty()?null:result.pages().stream().mapToInt(MaterialParsePort.Page::pageNumber).max().orElse(0),durationMs));
    }
    private static Integer page(String locator){var match=java.util.regex.Pattern.compile("pdf/page:(\\d+)").matcher(locator);return match.find()?Integer.valueOf(match.group(1)):null;}
}
