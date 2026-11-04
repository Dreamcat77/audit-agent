package com.fuquan.audit.contract.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record FindingSourceProjection(
        boolean available,
        String reason,
        String comparisonMode,
        List<Source> sources) {

    public FindingSourceProjection {
        sources = List.copyOf(sources);
    }

    public static FindingSourceProjection unavailable(String reason) {
        return new FindingSourceProjection(false, reason, null, List.of());
    }

    public static FindingSourceProjection available(String comparisonMode, List<Source> sources) {
        return new FindingSourceProjection(true, null, comparisonMode, sources);
    }

    public record Source(String role, UUID projectId, UUID fileId, String fileName, Integer pageNumber,
                         Integer paragraphIndex, String locator, String excerpt) {
        public Source {
            Objects.requireNonNull(role, "role must not be null");
            Objects.requireNonNull(projectId, "projectId must not be null");
            Objects.requireNonNull(fileId, "fileId must not be null");
            Objects.requireNonNull(fileName, "fileName must not be null");
            Objects.requireNonNull(locator, "locator must not be null");
            Objects.requireNonNull(excerpt, "excerpt must not be null");
        }
    }
}
