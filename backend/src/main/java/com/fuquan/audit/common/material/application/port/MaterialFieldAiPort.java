package com.fuquan.audit.common.material.application.port;

import java.util.List;

/** Non-authoritative, evidence-backed field suggestions for one immutable material. */
public interface MaterialFieldAiPort {
    record Segment(String locator, String text) {}
    record Fact(String field, String value, String locator, String excerpt) {}
    record Row(String rowKey, List<Fact> facts) {}
    /** Content-free evidence exclusions, persisted with acknowledged candidates. */
    record ExtractionIssue(String field, String locator, String reasonCode) {
        public static final java.util.Set<String> REASONS=java.util.Set.of(
            "EVIDENCE_UNVERIFIED","ROW_UNRESOLVED","AMOUNT_ROLE_UNVERIFIED","OCR_IDENTITY_UNCERTAIN","DOCUMENT_PARTIALLY_READ");
        public ExtractionIssue {
            if(field==null||!field.matches("[A-Za-z]{1,40}")||locator==null||locator.isBlank()||locator.length()>300
                    ||!REASONS.contains(reasonCode))throw new IllegalArgumentException("invalid extraction issue");
        }
    }
    record Result(List<Row> rows, String model, String promptVersion, List<ExtractionIssue> issues) {
        public Result {issues=issues==null?List.of():List.copyOf(issues);}
        public Result(List<Row> rows,String model,String promptVersion){this(rows,model,promptVersion,List.of());}
    }
    Result extract(String businessType, String materialId, String sha256, List<Segment> segments);
}
