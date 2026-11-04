package com.fuquan.audit.integration.dm8;
import java.time.LocalDateTime;
public record SanitizedDebugBundlePersistenceRow(String bundleId,String projectId,String contractId,String runId,
        String stepId,String attemptId,String contentLevel,String status,String manifestJson,String storageKey,
        String artifactSha256,long artifactSize,String generatedBy,LocalDateTime requestedAt,String requestId,String traceId,long versionNo) { }
