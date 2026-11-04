package com.fuquan.audit.contract.application.project.parse;

import com.fuquan.audit.common.material.application.OfficeMaterialTypes;
import com.fuquan.audit.common.material.application.port.MaterialParsePort;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Adapts the mature intake parser without rereading the file or moving its project/ZIP workflow. */
public final class ContractMaterialParseAdapter {
    private ContractMaterialParseAdapter() {}

    public static MaterialParsePort.ParseResult adapt(ProjectFile file, ProjectDocumentExtraction extraction) {
        var document = extraction.document();
        var provenance = extraction.provenance();
        var paragraphs = document.segments().stream()
                .map(s -> new MaterialParsePort.Paragraph(s.sourceLocator(), s.text())).toList();
        var tables = new ArrayList<MaterialParsePort.Table>();
        for (var table : document.tables()) {
            var cells = new ArrayList<MaterialParsePort.Cell>();
            int rowNumber = 0;
            for (var row : table.rows()) {
                rowNumber++;
                int column = 0;
                for (var cell : row.cells()) cells.add(new MaterialParsePort.Cell(cell.sourceLocator(),
                        rowNumber, ++column, cell.text(), "TEXT", null));
            }
            tables.add(new MaterialParsePort.Table(table.sourceLocator(), cells, false));
        }
        var pages = document.segments().stream().filter(s -> s.pageNumber() != null)
                .map(s -> new MaterialParsePort.Page(s.sourceLocator(), s.pageNumber(), s.text(), provenance.method().name())).toList();
        var ocr = document.segments().stream().filter(s -> s.sourceLocator().contains("ocr"))
                .map(s -> new MaterialParsePort.OcrProvenance(s.sourceLocator(), provenance.engine(), null,
                        "USED", provenance.confidence() == null ? null : provenance.confidence().doubleValue())).toList();
        String text = document.fullText();
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
            return new MaterialParsePort.ParseResult(file.id().toString(), file.sha256(),
                    OfficeMaterialTypes.mediaType(file.originalFilename()), MaterialParsePort.ParseStatus.PARSED,
                    text, digest, pages, paragraphs, tables, tables.stream().flatMap(t -> t.cells().stream()).toList(),
                    ocr, MaterialParsePort.PARSER_VERSION, Map.of("provider", "CONTRACT_INTAKE", "engine", provenance.engine()),
                    List.of("OCR_MODEL_VERSION_NOT_EXPOSED", "WORD_PHYSICAL_PAGE_LAYOUT_NOT_AVAILABLE"), null);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
