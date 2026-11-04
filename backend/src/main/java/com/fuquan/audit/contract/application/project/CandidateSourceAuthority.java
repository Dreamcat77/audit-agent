package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleSource;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.EffectiveProjectIdentityStatus;
import java.util.Objects;
import java.util.UUID;

/** Server-derived candidate source identity used by promotion validation and fingerprinting. */
public record CandidateSourceAuthority(
        UUID sourceFileId,
        String fileSha256,
        UUID parseResultId,
        long projectVersionNo,
        DocumentRole effectiveRole,
        EffectiveDocumentRoleSource effectiveRoleSource,
        UUID currentRoleDecisionId,
        EffectiveProjectIdentityStatus effectiveIdentityStatus,
        UUID currentIdentityDecisionId,
        String sourceFactFingerprint,
        boolean promotable,
        String rejectionReason) {

    public CandidateSourceAuthority {
        Objects.requireNonNull(sourceFileId, "sourceFileId must not be null");
        Objects.requireNonNull(fileSha256, "fileSha256 must not be null");
        Objects.requireNonNull(parseResultId, "parseResultId must not be null");
        Objects.requireNonNull(effectiveRole, "effectiveRole must not be null");
        Objects.requireNonNull(effectiveRoleSource, "effectiveRoleSource must not be null");
        Objects.requireNonNull(effectiveIdentityStatus, "effectiveIdentityStatus must not be null");
        if (projectVersionNo < 0) throw new IllegalArgumentException("invalid Project version");
        if (!promotable && (rejectionReason == null || rejectionReason.isBlank())) {
            throw new IllegalArgumentException("non-promotable authority requires a safe reason");
        }
    }

    public void requirePromotable() {
        if (!promotable) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, rejectionReason);
        }
    }
}
