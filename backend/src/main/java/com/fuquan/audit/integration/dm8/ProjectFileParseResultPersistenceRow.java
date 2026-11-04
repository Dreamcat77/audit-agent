package com.fuquan.audit.integration.dm8;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ProjectFileParseResultPersistenceRow(
        String parseResultId, String projectId, String fileId, String fileSha256, int parseVersion,
        String parseOrigin, String parseStatus, String parseMethod, String engine, String engineVersion,
        String canonicalText, String canonicalTextSha256, Integer pageCount, Long durationMs,
        BigDecimal confidence, String supersedesParseResultId, LocalDateTime createdAt,
        LocalDateTime completedAt, long versionNo) {
}
