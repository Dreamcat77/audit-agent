package com.fuquan.audit.contract.domain.reviewrun;

import com.fuquan.audit.contract.domain.project.DocumentRole;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Exact ProjectFile/parse/role/identity tuple frozen once at creation. */
public record ContractReviewRunMaterial(
        UUID runMaterialId, UUID runId, int ordinal, UUID projectId, UUID fileId,
        String fileSha256, UUID parseResultId, int parseVersion, String canonicalTextSha256,
        DocumentRole effectiveRole, UUID roleDecisionId, UUID identityDecisionId,
        UUID sourceRoleDispositionId, String selectedBy, Instant snapshottedAt) {
    public ContractReviewRunMaterial {
        Objects.requireNonNull(runMaterialId); Objects.requireNonNull(runId); Objects.requireNonNull(projectId);
        Objects.requireNonNull(fileId); Objects.requireNonNull(parseResultId);
        Objects.requireNonNull(effectiveRole); Objects.requireNonNull(identityDecisionId);
        Objects.requireNonNull(selectedBy); Objects.requireNonNull(snapshottedAt);
        if (ordinal <= 0 || parseVersion <= 0 || selectedBy.isBlank()
                || effectiveRole == DocumentRole.UNKNOWN) throw new IllegalArgumentException("invalid material snapshot");
        ContractReviewRun.requireHash(fileSha256);
        ContractReviewRun.requireHash(canonicalTextSha256);
    }
}
