package com.fuquan.audit.contract.application.project.factai;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ContractFactExtractionCandidate(UUID candidateId, UUID attemptId, String candidateKey,
        String factType, String groupKey, String valueText, BigDecimal valueDecimal,
        String segmentId, String sourceLocator, String excerpt, int excerptStart, int excerptEnd,
        String contextCode, BigDecimal confidence, String validationStatus,
        String validationReason, UUID publishedExtractionId, Instant createdAt) {
    public ContractFactExtractionCandidate {
        Objects.requireNonNull(candidateId); Objects.requireNonNull(attemptId);
        required(candidateKey, "candidateKey"); required(factType, "factType");
        required(segmentId, "segmentId"); required(sourceLocator, "sourceLocator");
        required(excerpt, "excerpt"); required(contextCode, "contextCode");
        required(validationStatus, "validationStatus"); required(validationReason, "validationReason");
        Objects.requireNonNull(confidence); Objects.requireNonNull(createdAt);
        if (excerptStart < 0 || excerptEnd <= excerptStart) throw new IllegalArgumentException("invalid excerpt offsets");
        if (("ACCEPTED".equals(validationStatus)) != (publishedExtractionId != null)) {
            throw new IllegalArgumentException("accepted candidate publication identity is incoherent");
        }
    }

    private static void required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
