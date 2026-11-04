package com.fuquan.audit.contract.application.project.factai;

import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Segment;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import com.fuquan.audit.common.web.RequestIdFilter;

/** Creates deterministic code-point segments accepted by the strict FastAPI protocol. */
public final class ContractFactSegmenter {
    private static final Logger LOGGER = LoggerFactory.getLogger(ContractFactSegmenter.class);
    // Must match PROJECT_EXTRACTION_RESULT.SOURCE_TEXT and the v2 FastAPI
    // server-owned evidence limit. Evidence is split, never truncated.
    static final int MAX_SEGMENT_CODE_POINTS = 240;
    static final int MAX_SEGMENTS = 32;
    static final int MAX_DOCUMENT_CODE_POINTS = 122880;
    // Physical paragraphs/table rows are indivisible identities. V2's 48-line
    // contract has more than 256 short rows despite remaining below 30,720 CP.
    static final int MAX_DOCUMENT_SEGMENTS = 4096;

    public List<Segment> segment(UUID parseResultId, String canonicalText) {
        Objects.requireNonNull(parseResultId, "parseResultId must not be null");
        if (canonicalText == null || canonicalText.isBlank()) {
            throw new IllegalArgumentException("canonicalText must not be blank");
        }
        int total = canonicalText.codePointCount(0, canonicalText.length());
        int required = (total + MAX_SEGMENT_CODE_POINTS - 1) / MAX_SEGMENT_CODE_POINTS;
        if (total > MAX_DOCUMENT_CODE_POINTS) {
            log("REJECTED", "INPUT_TOO_LARGE", total, null, required);
            throw new ContractFactSegmentationException(total, required,
                    MAX_DOCUMENT_CODE_POINTS / MAX_SEGMENT_CODE_POINTS, MAX_SEGMENT_CODE_POINTS);
        }
        List<Segment> segments = new ArrayList<>(required);
        int rowStart = 0;
        List<String> activeColumns = null;
        List<String> rowColumns = null;
        for (int start = 0, ordinal = 1; start < total; ordinal++) {
            if (ordinal > MAX_DOCUMENT_SEGMENTS) {
                log("REJECTED", "INPUT_TOO_LARGE", total, ordinal, required);
                throw new ContractFactSegmentationException(total, ordinal,
                        MAX_DOCUMENT_SEGMENTS, MAX_SEGMENT_CODE_POINTS);
            }
            int startIndex = canonicalText.offsetByCodePoints(0, start);
            int newline = canonicalText.indexOf('\n', startIndex);
            int rowEnd = newline < 0 ? total : canonicalText.codePointCount(0, newline + 1);
            if (start == rowStart) {
                String physicalRow = slice(canonicalText, rowStart, rowEnd).strip();
                List<String> header = columnsFromHeader(physicalRow);
                rowColumns = null;
                if (header != null) {
                    activeColumns = header;
                } else if (validDataRow(physicalRow, activeColumns)) {
                    if (rowEnd - rowStart <= MAX_SEGMENT_CODE_POINTS) rowColumns = activeColumns;
                } else {
                    activeColumns = null;
                }
            }
            int end = Math.min(rowEnd, start + MAX_SEGMENT_CODE_POINTS);
            String text = slice(canonicalText, start, end);
            String id = "segment-%02d".formatted(ordinal);
            String locator = "parse-result:%s:canonical-codepoint:%d-%d"
                    .formatted(parseResultId, start, end);
            segments.add(new Segment(id, locator, sha256(text), start, end, text,
                    rowIdentity(parseResultId, rowStart, rowEnd), rowColumns));
            start = end;
            if (start == rowEnd) rowStart = start;
        }
        log("SEGMENTED", "NONE", total, segments.size(), required);
        return List.copyOf(segments);
    }

    // Only an explicit contiguous table header grants compact-field authority.
    // Unrecognised tables and fragmented rows retain the legacy evidence path.
    private static List<String> columnsFromHeader(String row) {
        String[] cells = row.split("\\t", -1);
        if (cells.length < 4 || cells.length > 5) return null;
        List<String> columns = new ArrayList<>();
        for (String cell : cells) {
            String label = java.text.Normalizer.normalize(cell, java.text.Normalizer.Form.NFKC)
                    .replaceAll("\\s+", "").replaceAll("\\([^)]*\\)", "");
            String column = switch (label) {
                case "序号", "编号", "行号", "行号/稳定编码", "明细ID", "ID" -> "rowId";
                case "名称", "明细名称", "项目名称", "货物名称", "服务名称", "软件或服务项目", "品名" -> "name";
                case "数量" -> "quantity";
                case "单价" -> "unitPrice";
                case "金额", "行金额", "小计", "合价", "总价" -> "declaredAmount";
                default -> null;
            };
            if (column == null || columns.contains(column)) return null;
            columns.add(column);
        }
        return columns.containsAll(List.of("name", "quantity", "unitPrice", "declaredAmount"))
                ? List.copyOf(columns) : null;
    }

    private static boolean validDataRow(String row, List<String> columns) {
        if (columns == null) return false;
        String[] cells = row.split("\\t", -1);
        if (cells.length != columns.size()) return false;
        for (int i = 0; i < cells.length; i++) {
            String cell = cells[i].strip();
            if (cell.isEmpty()) return false;
            if (List.of("quantity", "unitPrice", "declaredAmount").contains(columns.get(i))
                    && !cell.matches("(?:0|[1-9][0-9]*|[1-9][0-9]{0,2}(?:,[0-9]{3})+)(?:\\.[0-9]+)?")) return false;
        }
        return true;
    }

    static String rowIdentity(UUID parseResultId, int rowStart, int rowEnd) {
        return "row:" + sha256("contract-source-row-v1:" + parseResultId + ":" + rowStart + ":" + rowEnd);
    }

    public static String lineGroup(String rowIdentity) {
        return "line:" + sha256("[\"" + rowIdentity + "\"]");
    }

    private static void log(String outcome, String reasonCode, int codePointCount,
            Integer segmentCount, int requiredSegmentCount) {
        LOGGER.info("event=FACT_SEGMENTATION diagnosticSchemaVersion=1 module=CONTRACT "
                        + "layer=APPLICATION operation=segmentContractFacts stage=FACT_SEGMENTATION "
                        + "outcome={} reasonCode={} requestId={} traceId={} projectId=null fileId=null "
                        + "attemptId=null idempotencyScope=null operationKeyHash=null durationMs=0 "
                        + "canonicalCodePointCount={} segmentCount={} requiredSegmentCount={} "
                        + "maxDocumentCodePoints={} maxPhysicalSegments={} maxBatchSegments={} maxSegmentCodePoints={}",
                outcome, reasonCode, MDC.get(RequestIdFilter.MDC_KEY), MDC.get("traceId"),
                codePointCount, segmentCount, requiredSegmentCount,
                MAX_DOCUMENT_CODE_POINTS, MAX_DOCUMENT_SEGMENTS, MAX_SEGMENTS, MAX_SEGMENT_CODE_POINTS);
    }

    private static String slice(String value, int start, int end) {
        return value.substring(value.offsetByCodePoints(0, start), value.offsetByCodePoints(0, end));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is required", failure);
        }
    }
}
