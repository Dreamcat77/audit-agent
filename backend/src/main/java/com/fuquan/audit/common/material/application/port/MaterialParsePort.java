package com.fuquan.audit.common.material.application.port;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Business-neutral immutable document parsing contract.
 *
 * <p>The port describes what was read and where it came from. It does not
 * classify procurement roles or make budget, contract, or supplier decisions.
 * Existing business-specific extractors may consume the result without
 * taking a dependency on Project or Contract.</p>
 */
public interface MaterialParsePort {
    String PARSER_VERSION = "material-parse-v5";

    ParseResult parse(ParseRequest request) throws IOException;
    default ParseResult parse(ParseRequest request, DocumentTextPort.PdfCheckpoint checkpoint,
                              java.util.function.Consumer<DocumentTextPort.PdfCheckpoint> progress) throws IOException {
        return parse(request);
    }

    record ParseRequest(String documentId, String fileName, String contentType,
                        InputStream content, long maxBytes, int maxPages, int maxChars,
                        Map<String, String> parseOptions) {
        public ParseRequest {
            if (documentId == null || documentId.isBlank()) throw new IllegalArgumentException("documentId must not be blank");
            if (fileName == null || fileName.isBlank()) throw new IllegalArgumentException("fileName must not be blank");
            if (content == null) throw new IllegalArgumentException("content must not be null");
            if (maxBytes <= 0 || maxPages <= 0 || maxChars <= 0) throw new IllegalArgumentException("parse limits must be positive");
            parseOptions = parseOptions == null ? Map.of() : Map.copyOf(parseOptions);
        }
    }

    record ParseResult(String documentId, String fileSha256, String recognizedFormat,
                       ParseStatus parseStatus, String canonicalText, String contentSha256,
                       List<Page> pages, List<Paragraph> paragraphs, List<Table> tables,
                       List<Cell> cells, List<OcrProvenance> ocrProvenance,
                       String parserVersion, Map<String, String> parseOptions,
                       List<String> coverageGaps, String errorCode) {
        public ParseResult {
            if (documentId == null || documentId.isBlank()) throw new IllegalArgumentException("documentId must not be blank");
            if (parseStatus == null) throw new IllegalArgumentException("parseStatus must not be null");
            canonicalText = canonicalText == null ? "" : canonicalText;
            pages = pages == null ? List.of() : List.copyOf(pages);
            paragraphs = paragraphs == null ? List.of() : List.copyOf(paragraphs);
            tables = tables == null ? List.of() : List.copyOf(tables);
            cells = cells == null ? List.of() : List.copyOf(cells);
            ocrProvenance = ocrProvenance == null ? List.of() : List.copyOf(ocrProvenance);
            parseOptions = parseOptions == null ? Map.of() : Map.copyOf(parseOptions);
            coverageGaps = coverageGaps == null ? List.of() : List.copyOf(coverageGaps);
        }

        public boolean usable() {
            return parseStatus == ParseStatus.PARSED && !canonicalText.isBlank();
        }

        /** Preserve the precise source locators and typed tables for existing consumers. */
        public DocumentTextPort.Result textResult() throws DocumentTextPort.ReadFailure {
            if (!usable()) throw new DocumentTextPort.ReadFailure(errorCode == null ? "DOCUMENT_PARSE_FAILED" : errorCode);
            return new DocumentTextPort.Result(paragraphs.stream()
                    .map(p -> new MaterialFieldAiPort.Segment(p.locator(), p.text())).toList(),
                    (ocrProvenance.isEmpty() ? "OFFICE_TEXT" : "OFFICE_TEXT_OCR")
                            + (coverageGaps.stream().anyMatch(g -> g.startsWith("UNREAD:")) ? "_PARTIAL" : ""),
                    fileSha256, tables, ocrProvenance, coverageGaps);
        }
    }

    enum ParseStatus { PARSED, UNSUPPORTED, PARSE_FAILED, UNKNOWN, CONFLICT }

    record Page(String locator, int pageNumber, String text, String extractionMethod) {}

    record Paragraph(String locator, String text) {}

    record Table(String locator, List<Cell> cells, boolean structureInferred) {
        public Table {
            cells = cells == null ? List.of() : List.copyOf(cells);
        }
    }

    record Cell(String locator, int rowNumber, int columnNumber, String value,
                String dataType, String formula, String rawValue, String cachedValue,
                String numberFormat, String mergedRange) {
        public Cell(String locator, int rowNumber, int columnNumber, String value, String dataType, String formula) {
            this(locator, rowNumber, columnNumber, value, dataType, formula, null, null, null, null);
        }
    }

    record OcrProvenance(String locator, String engine, String version,
                         String status, Double confidence) {}
}
