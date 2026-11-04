package com.fuquan.audit.contract.domain.project;

import java.math.BigDecimal;

public record ProjectFileParseProvenance(
        ProjectFileParseMethod method,
        String engine,
        BigDecimal confidence,
        Integer pageCount,
        Long durationMs) {

    private static final ProjectFileParseProvenance NONE =
            new ProjectFileParseProvenance(null, null, null, null, null);

    public ProjectFileParseProvenance {
        if (method == null) {
            if (engine != null || confidence != null || pageCount != null || durationMs != null) {
                throw new IllegalArgumentException("parse method is required when provenance is present");
            }
        } else {
            if (engine == null || engine.isBlank()) {
                throw new IllegalArgumentException("parse engine must not be blank");
            }
            engine = engine.trim();
            if (engine.length() > 100) {
                throw new IllegalArgumentException("parse engine must not exceed 100 characters");
            }
        }
        if (confidence != null
                && (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("parse confidence must be between zero and one");
        }
        if (pageCount != null && pageCount < 0) {
            throw new IllegalArgumentException("page count must be non-negative");
        }
        if (durationMs != null && durationMs < 0) {
            throw new IllegalArgumentException("parse duration must be non-negative");
        }
    }

    public static ProjectFileParseProvenance none() {
        return NONE;
    }

    public boolean present() {
        return method != null;
    }
}
