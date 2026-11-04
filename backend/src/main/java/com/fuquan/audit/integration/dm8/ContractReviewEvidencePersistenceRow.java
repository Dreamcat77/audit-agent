package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public record ContractReviewEvidencePersistenceRow(
        String evidenceId, String findingId, String runId, String stepId, String attemptId,
        int ordinal, String fileId, String fileSha256, String parseResultId, int parseVersion,
        String canonicalTextSha256, String evidenceRole, String validationStatus,
        String validationPolicyVersion, Integer pageNumber, Integer paragraphIndex,
        Integer tableIndex, Integer rowIndex, Integer cellIndex, Integer segmentIndex,
        int locatorAvailable, Integer startOffset, Integer endOffset, String excerptSha256,
        String boundedExcerpt, int locatorSchemaVersion, LocalDateTime createdAt, long versionNo) { }
