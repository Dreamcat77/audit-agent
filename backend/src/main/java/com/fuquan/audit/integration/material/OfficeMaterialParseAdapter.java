package com.fuquan.audit.integration.material;

import com.fuquan.audit.common.material.application.OfficeMaterialTypes;
import com.fuquan.audit.common.material.application.port.DocumentTextPort;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Segment;
import com.fuquan.audit.common.material.application.port.MaterialParsePort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Compatibility adapter over the existing secure Office/PDF/image reader.
 * It adds a stable structural projection while preserving the old reader and
 * its OCR, signature, archive, and size checks.
 */
@Component
public final class OfficeMaterialParseAdapter implements MaterialParsePort {
    private static final Pattern PDF_PAGE = Pattern.compile("pdf/page:(\\d+)(?:/.*)?");
    private static final Pattern EXCEL_ROW = Pattern.compile("excel/sheet:(\\d+)/row:(\\d+)(?:/part:\\d+)?");
    private static final Pattern PPT_PAGE = Pattern.compile("(?:ppt|pptx)/slide:(\\d+)(?:/.*)?");
    private final DocumentTextPort reader;

    public OfficeMaterialParseAdapter(@Qualifier("officeDocumentTextReader") DocumentTextPort reader) {
        this.reader = reader;
    }

    @Override
    public ParseResult parse(ParseRequest request) throws IOException {
        return parse(request,null,null);
    }

    @Override
    public ParseResult parse(ParseRequest request, DocumentTextPort.PdfCheckpoint checkpoint,
            java.util.function.Consumer<DocumentTextPort.PdfCheckpoint> progress) throws IOException {
        String format = OfficeMaterialTypes.mediaType(request.fileName());
        if (format == null) {
            return failure(request, null, ParseStatus.UNSUPPORTED, "DOCUMENT_TYPE_UNSUPPORTED");
        }
        try {
            DocumentTextPort.Result source = checkpoint==null&&progress==null
                    ?reader.read(request.content(), request.fileName(), request.documentId(),request.maxBytes(),request.maxPages(),request.maxChars())
                    :reader.read(request.content(), request.fileName(), request.documentId(),
                        request.maxBytes(), request.maxPages(), request.maxChars(),checkpoint,progress);
            List<Segment> sourceSegments = source.segments() == null ? List.of() : source.segments();
            String canonical = sourceSegments.stream().map(Segment::text).filter(value -> value != null)
                    .collect(java.util.stream.Collectors.joining("\n"));
            if (canonical.isBlank()) return failure(request, source.sha256(), ParseStatus.PARSE_FAILED, "DOCUMENT_TEXT_EMPTY");

            List<Page> pages = pages(sourceSegments, source.method());
            List<Paragraph> paragraphs = sourceSegments.stream()
                    .map(segment -> new Paragraph(segment.locator(), segment.text())).toList();
            List<Table> tables = source.tables().isEmpty() ? tables(sourceSegments, format) : source.tables();
            List<Cell> cells = tables.stream().flatMap(table -> table.cells().stream()).toList();
            List<OcrProvenance> ocr = source.ocrProvenance().isEmpty() ? sourceSegments.stream()
                    .filter(segment -> segment.locator().contains("/ocr"))
                    .map(segment -> new OcrProvenance(segment.locator(), "UNKNOWN", null, "USED", null)).toList()
                    : source.ocrProvenance();
            List<String> gaps = new ArrayList<>(source.coverageGaps());
            if ((format.contains("spreadsheet") || format.equals("application/vnd.ms-excel")) && source.tables().isEmpty()) {
                gaps.add("FORMULA_AND_DISPLAY_VALUES_ARE_NORMALIZED_BY_LEGACY_READER");
            }
            if (tables.stream().anyMatch(Table::structureInferred)) {
                gaps.add("TABLE_STRUCTURE_INFERRED_FROM_CANONICAL_SEGMENTS");
            }
            if (format.contains("word") || format.contains("msword")) gaps.add("WORD_PAGE_LAYOUT_NOT_AVAILABLE_IN_TEXT_ADAPTER");
            if (format.contains("word") || format.contains("msword")) {
                gaps.add("WORD_MERGE_GRID_AND_NESTED_TABLE_GEOMETRY_NOT_EXPOSED");
                if (format.equals("application/msword")) gaps.add("LEGACY_WORD_TABLE_STRUCTURE_NOT_EXPOSED");
            }
            return new ParseResult(request.documentId(), source.sha256(), format, ParseStatus.PARSED, canonical,
                    sha256(canonical), pages, paragraphs, tables, cells, ocr, PARSER_VERSION,
                    request.parseOptions(), List.copyOf(gaps), null);
        } catch (DocumentTextPort.ReadFailure failure) {
            return failure(request, null, statusFor(failure.code()), failure.code());
        }
    }

    private static ParseResult failure(ParseRequest request, String sourceSha, ParseStatus status, String code) {
        return new ParseResult(request.documentId(), sourceSha, OfficeMaterialTypes.mediaType(request.fileName()),
                status, "", null, List.of(), List.of(), List.of(), List.of(), List.of(), PARSER_VERSION,
                request.parseOptions(), List.of("NO_CANONICAL_TEXT"), code);
    }

    private static ParseStatus statusFor(String code) {
        if (code == null || code.isBlank()) return ParseStatus.UNKNOWN;
        if (code.contains("UNKNOWN") || code.contains("UNPROVEN") || code.contains("INTERRUPTED")) return ParseStatus.UNKNOWN;
        if (code.contains("UNSUPPORTED")) return ParseStatus.UNSUPPORTED;
        return ParseStatus.PARSE_FAILED;
    }

    private static List<Page> pages(List<Segment> segments, String method) {
        Map<String, PageAccumulator> grouped = new LinkedHashMap<>();
        for (Segment segment : segments) {
            String locator = segment.locator();
            Matcher matcher = PDF_PAGE.matcher(locator);
            int number;
            String pageLocator;
            if (matcher.matches()) {
                number = Integer.parseInt(matcher.group(1));
                pageLocator = "pdf/page:" + number;
            } else {
                matcher = PPT_PAGE.matcher(locator);
                if (matcher.matches()) {
                    number = Integer.parseInt(matcher.group(1));
                    pageLocator = locator.substring(0, locator.indexOf("/slide:") + 7) + number;
                } else {
                    continue; // A paragraph or spreadsheet row is not a physical page.
                }
            }
            grouped.computeIfAbsent(pageLocator, ignored -> new PageAccumulator(number, pageLocator))
                    .append(segment.text());
        }
        return grouped.values().stream().map(value -> new Page(value.locator, value.number,
                value.text.toString(), method)).toList();
    }

    private static List<Table> tables(List<Segment> segments, String format) {
        Map<String, List<Cell>> grouped = new LinkedHashMap<>();
        for (Segment segment : segments) {
            Matcher matcher = EXCEL_ROW.matcher(segment.locator());
            if (!matcher.matches()) continue;
            String tableLocator = "excel/sheet:" + matcher.group(1);
            int row = Integer.parseInt(matcher.group(2));
            String[] values = (segment.text() == null ? "" : segment.text()).split(" \\| ", -1);
            for (int column = 0; column < values.length; column++) {
                grouped.computeIfAbsent(tableLocator, ignored -> new ArrayList<>())
                        .add(new Cell(segment.locator() + "/cell:" + (column + 1), row, column + 1,
                                values[column], "TEXT", null));
            }
        }
        return grouped.entrySet().stream().map(entry -> new Table(entry.getKey(), entry.getValue(), true)).toList();
    }

    private static String sha256(String value) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 unavailable", exception);
        }
    }

    private static final class PageAccumulator {
        private final int number;
        private final String locator;
        private final StringBuilder text = new StringBuilder();
        private PageAccumulator(int number, String locator) { this.number = number; this.locator = locator; }
        private void append(String value) { if (text.length() > 0) text.append('\n'); text.append(value == null ? "" : value); }
    }
}
