package com.fuquan.audit.contract.domain;

import com.fuquan.audit.contract.domain.project.DocumentRole;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A reference snapshot. No binary or parsed text is duplicated into the session. */
public record ReviewSessionProjectFileBinding(UUID id, UUID sessionId, UUID projectId,
        UUID projectFileId, String fileSha256, UUID parseResultId, int parseVersion,
        String canonicalTextSha256, DocumentRole effectiveRole, UUID roleDecisionId,
        UUID identityDecisionId, String slotKey, int ordinal, boolean activeFlag,
        String boundBy, Instant boundAt, long selectionVersion, long versionNo) {
    public ReviewSessionProjectFileBinding {
        Objects.requireNonNull(id); Objects.requireNonNull(sessionId); Objects.requireNonNull(projectId);
        Objects.requireNonNull(projectFileId); Objects.requireNonNull(parseResultId);
        Objects.requireNonNull(effectiveRole); Objects.requireNonNull(identityDecisionId); Objects.requireNonNull(boundAt);
        if (effectiveRole == DocumentRole.UNKNOWN || parseVersion < 1 || ordinal < 1 || selectionVersion < 0 || versionNo < 0
                || fileSha256 == null || !fileSha256.matches("[0-9a-f]{64}")
                || canonicalTextSha256 == null || !canonicalTextSha256.matches("[0-9a-f]{64}")
                || boundBy == null || boundBy.isBlank() || boundBy.length() > 36
                || slotKey == null || slotKey.isBlank() || slotKey.length() > 128)
            throw new IllegalArgumentException("invalid project material binding");
    }
    public ReviewSessionProjectFileBinding deactivate() {
        if (!activeFlag) throw new IllegalStateException("binding is inactive");
        return new ReviewSessionProjectFileBinding(id, sessionId, projectId, projectFileId, fileSha256,
                parseResultId, parseVersion, canonicalTextSha256, effectiveRole, roleDecisionId,
                identityDecisionId, slotKey, ordinal, false, boundBy, boundAt, selectionVersion, versionNo + 1);
    }
}
