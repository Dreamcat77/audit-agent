package com.fuquan.audit.procurement.domain;

import java.util.List;
import java.util.Objects;

/** 招标文件文本及其可追溯定位信息。 */
public record ProcurementTenderParseResult(String fullText, List<Segment> segments,
        List<com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact> extractedFields,
        FieldExtractionState fieldExtraction) {
    public ProcurementTenderParseResult(String fullText, List<Segment> segments) {
        this(fullText, segments, List.of(), null);
    }
    public ProcurementTenderParseResult(String fullText, List<Segment> segments,
            List<com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Fact> extractedFields) {
        this(fullText, segments, extractedFields, null);
    }
    public ProcurementTenderParseResult {
        Objects.requireNonNull(fullText, "fullText must not be null");
        segments = List.copyOf(Objects.requireNonNull(segments, "segments must not be null"));
        extractedFields = extractedFields == null ? List.of() : List.copyOf(extractedFields);
    }

    /** Durable extraction outcome for automatic reopen/debugging; never contains source text or credentials. */
    public record FieldExtractionState(String status, String model, String promptVersion,
            String reasonCode, int candidateCount, int acceptedCount) {
        public FieldExtractionState {
            if (status == null || !java.util.Set.of("AVAILABLE", "EMPTY", "UNAVAILABLE", "UNKNOWN").contains(status))
                throw new IllegalArgumentException("invalid field extraction status");
            if (candidateCount < 0 || acceptedCount < 0 || acceptedCount > candidateCount)
                throw new IllegalArgumentException("invalid field extraction counts");
            if (model != null && model.length() > 120 || promptVersion != null && promptVersion.length() > 120
                    || reasonCode != null && !reasonCode.matches("[A-Z0-9_]{1,80}"))
                throw new IllegalArgumentException("invalid field extraction metadata");
        }
    }

    public record Segment(String sourceLocator, String text, Integer pageNumber, Double confidence) {
        public Segment {
            if (sourceLocator == null || sourceLocator.isBlank()) {
                throw new IllegalArgumentException("sourceLocator must not be blank");
            }
            Objects.requireNonNull(text, "text must not be null");
            if (pageNumber != null && pageNumber < 1) {
                throw new IllegalArgumentException("pageNumber must be positive");
            }
            if (confidence != null && (!Double.isFinite(confidence) || confidence < 0 || confidence > 1)) {
                throw new IllegalArgumentException("confidence must be between 0 and 1");
            }
        }
    }
}
