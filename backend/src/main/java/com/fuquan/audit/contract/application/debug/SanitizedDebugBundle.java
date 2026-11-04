package com.fuquan.audit.contract.application.debug;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import com.fuquan.audit.contract.application.reviewrun.ContractSnapshotCanonicalizer;
import com.fuquan.audit.common.exception.*;

/** Frozen allowlisted manifest and expected artifact identity; no original/document/model payload. */
public record SanitizedDebugBundle(UUID bundleId, DebugScope scope, DebugContentLevel contentLevel,
        Status status, String manifestJson, String storageKey, String artifactSha256, long artifactSize,
        String generatedBy, Instant requestedAt, String requestId, String traceId, long versionNo) {
    public enum Status { GENERATING, AVAILABLE, UNKNOWN }
    public SanitizedDebugBundle {
        Objects.requireNonNull(bundleId); Objects.requireNonNull(scope); Objects.requireNonNull(contentLevel);
        Objects.requireNonNull(status); Objects.requireNonNull(requestedAt); Objects.requireNonNull(generatedBy);
        contentLevel.requireAvailable();
        if(manifestJson==null || artifactSize<=0 || artifactSize>DebugManifestBuilder.MAX_BYTES || versionNo<0
                || artifactSize!=manifestJson.getBytes(StandardCharsets.UTF_8).length
                || !ContractSnapshotCanonicalizer.sha256(manifestJson).equals(artifactSha256)
                || !Objects.equals(storageKey,ContractSnapshotCanonicalizer.sha256("debug-bundle-v1:"+bundleId)))
            throw new IllegalArgumentException("invalid frozen debug artifact");
    }
    public SanitizedDebugBundle withStatus(Status next) {
        return new SanitizedDebugBundle(bundleId,scope,contentLevel,next,manifestJson,storageKey,artifactSha256,
                artifactSize,generatedBy,requestedAt,requestId,traceId,versionNo+1);
    }
    public static void requireTransition(SanitizedDebugBundle expected,SanitizedDebugBundle next) {
        if(expected.status()==Status.AVAILABLE || !expected.withStatus(next.status()).equals(next)
                || expected.status()==next.status()) throw new BusinessException(BusinessErrorCode.CONFLICT,"debug artifact state conflict");
    }
}
