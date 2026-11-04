package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.contract.domain.project.DocumentRole;
import java.util.UUID;

/** One exact immutable run-material/parse tuple supplied to every review engine. */
public record FrozenReviewMaterial(UUID runMaterialId, int ordinal, UUID fileId,
        String fileSha256, UUID parseResultId, int parseVersion, String canonicalTextSha256,
        String canonicalText, DocumentRole effectiveRole, UUID roleDecisionId,
        UUID identityDecisionId, UUID sourceRoleDispositionId, String originalFilename) {
    public FrozenReviewMaterial(UUID runMaterialId, int ordinal, UUID fileId,
            String fileSha256, UUID parseResultId, int parseVersion, String canonicalTextSha256,
            String canonicalText, DocumentRole effectiveRole, UUID roleDecisionId,
            UUID identityDecisionId, UUID sourceRoleDispositionId) {
        this(runMaterialId, ordinal, fileId, fileSha256, parseResultId, parseVersion,
                canonicalTextSha256, canonicalText, effectiveRole, roleDecisionId,
                identityDecisionId, sourceRoleDispositionId, null);
    }
}
