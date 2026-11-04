package com.fuquan.audit.contract.domain.project;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.HexFormat;

/** Immutable fact describing one parse attempt. Reparse creates another instance. */
public record ProjectFileParseResult(
        UUID parseResultId,
        UUID projectId,
        UUID fileId,
        String fileSha256,
        int parseVersion,
        ProjectFileParseOrigin parseOrigin,
        ProjectFileParseStatus parseStatus,
        ProjectFileParseMethod parseMethod,
        String engine,
        String engineVersion,
        String canonicalText,
        String canonicalTextSha256,
        Integer pageCount,
        Long durationMs,
        BigDecimal confidence,
        UUID supersedesParseResultId,
        Instant createdAt,
        Instant completedAt,
        int versionNo) {

    public ProjectFileParseResult {
        Objects.requireNonNull(parseResultId, "parseResultId must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(fileId, "fileId must not be null");
        Objects.requireNonNull(parseOrigin, "parseOrigin must not be null");
        Objects.requireNonNull(parseStatus, "parseStatus must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        Objects.requireNonNull(fileSha256, "fileSha256 must not be null");
        if (!fileSha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("file SHA-256 must be lowercase");
        if (parseVersion <= 0 || versionNo < 0) throw new IllegalArgumentException("invalid versions");
        if (completedAt.isBefore(createdAt)) throw new IllegalArgumentException("completedAt before createdAt");
        Objects.requireNonNull(parseMethod, "parseMethod must not be null");
        if (engine == null || engine.isBlank()) throw new IllegalArgumentException("engine must not be blank");
        if (engineVersion != null && engineVersion.isBlank()) throw new IllegalArgumentException("engineVersion must not be blank");
        if (confidence != null && (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0)) throw new IllegalArgumentException("confidence must be between zero and one");
        if (pageCount != null && pageCount < 0) throw new IllegalArgumentException("pageCount must be non-negative");
        if (durationMs != null && durationMs < 0) throw new IllegalArgumentException("durationMs must be non-negative");
        if (parseStatus == ProjectFileParseStatus.PARSED) {
            if (canonicalText == null || canonicalText.isBlank() || canonicalTextSha256 == null) throw new IllegalArgumentException("PARSED result requires canonical text and hash");
            if (!canonicalTextSha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("canonical SHA-256 must be lowercase");
            if (!canonicalTextSha256.equals(sha256(canonicalText))) throw new IllegalArgumentException("canonical SHA-256 does not match text");
        } else if (canonicalText != null || canonicalTextSha256 != null) {
            throw new IllegalArgumentException("non-PARSED result must not contain canonical text");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
