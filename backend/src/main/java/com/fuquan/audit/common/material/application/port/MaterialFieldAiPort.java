package com.fuquan.audit.common.material.application.port;

import java.util.List;

/** Non-authoritative, evidence-backed field suggestions for one immutable material. */
public interface MaterialFieldAiPort {
    record Segment(String locator, String text) {}
    record Fact(String field, String value, String locator, String excerpt, String subjectScope, String amountScope) {
        public Fact(String field,String value,String locator,String excerpt){this(field,value,locator,excerpt,null,null);}
    }
    record Context(String header,String section) {}
    record Row(String rowKey, List<Fact> facts) {}
    /** Content-free evidence exclusions, persisted with acknowledged candidates. */
    record ExtractionIssue(String field, String locator, String reasonCode) {
        public static final java.util.Set<String> REASONS=java.util.Set.of(
            "EVIDENCE_UNVERIFIED","ROW_UNRESOLVED","AMOUNT_ROLE_UNVERIFIED","OCR_IDENTITY_UNCERTAIN","DOCUMENT_PARTIALLY_READ","MODEL_EXTRACTION_INCOMPLETE");
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
    /** Read only independently acknowledged units; never starts or retries an external call. */
    default java.util.Optional<Result> acknowledgedResult(String businessType,String materialId,String sha256,List<Segment> segments) {
        return java.util.Optional.empty();
    }
    default Result extractWithContext(String businessType,String materialId,String sha256,List<Segment> segments,Context context){
        return extract(businessType,materialId,sha256,segments);
    }
}
