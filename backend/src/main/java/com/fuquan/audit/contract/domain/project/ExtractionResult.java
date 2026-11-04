package com.fuquan.audit.contract.domain.project;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ExtractionResult(
        UUID id,
        UUID projectId,
        UUID sourceFileId,
        UUID parseResultId,
        UUID candidateId,
        String extractionKey,
        String extractionVersion,
        String factType,
        String groupKey,
        String valueText,
        String normalizedValue,
        BigDecimal valueAmount,
        String sourceText,
        Integer pageNumber,
        Integer paragraphIndex,
        String sourceLocator,
        Instant createdAt) {

    public ExtractionResult {
        Objects.requireNonNull(id);
        Objects.requireNonNull(projectId);
        Objects.requireNonNull(sourceFileId);
        Objects.requireNonNull(extractionKey);
        Objects.requireNonNull(extractionVersion);
        Objects.requireNonNull(factType);
        Objects.requireNonNull(sourceText);
        Objects.requireNonNull(sourceLocator);
        Objects.requireNonNull(createdAt);
        if (!extractionKey.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("extractionKey must be lowercase SHA-256");
        }
        if (sourceText.isBlank() || sourceText.length() > 240) {
            throw new IllegalArgumentException("sourceText must contain 1..240 characters");
        }
        if (pageNumber != null && pageNumber < 1) throw new IllegalArgumentException("pageNumber must be positive");
        if (paragraphIndex != null && paragraphIndex < 1) throw new IllegalArgumentException("paragraphIndex must be positive");
    }

    public ExtractionResult(UUID id, UUID projectId, UUID sourceFileId, UUID candidateId,
            String extractionKey, String extractionVersion, String factType, String groupKey,
            String valueText, String normalizedValue, BigDecimal valueAmount, String sourceText,
            Integer pageNumber, Integer paragraphIndex, String sourceLocator, Instant createdAt) {
        this(id, projectId, sourceFileId, null, candidateId, extractionKey, extractionVersion,
                factType, groupKey, valueText, normalizedValue, valueAmount, sourceText,
                pageNumber, paragraphIndex, sourceLocator, createdAt);
    }

    public ExtractionResult bindCandidate(UUID expectedParseResultId, UUID value) {
        if (!Objects.equals(parseResultId, expectedParseResultId)) {
            throw ProjectFactIdentityConflictException.extractionCandidateBinding();
        }
        if (candidateId != null && !candidateId.equals(value)) {
            throw ProjectFactIdentityConflictException.extractionCandidateBinding();
        }
        return candidateId == null ? new ExtractionResult(id, projectId, sourceFileId, parseResultId,
                Objects.requireNonNull(value), extractionKey, extractionVersion, factType, groupKey,
                valueText, normalizedValue, valueAmount, sourceText, pageNumber, paragraphIndex,
                sourceLocator, createdAt) : this;
    }
}
