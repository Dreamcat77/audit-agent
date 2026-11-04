package com.fuquan.audit.contract.application.project.role;

import com.fuquan.audit.contract.domain.project.DocumentRole;
import java.util.Objects;
import java.util.UUID;

public record EffectiveDocumentRoleResult(
        DocumentRole effectiveRole,
        EffectiveDocumentRoleSource source,
        UUID roleDecisionId,
        Long decisionVersionNo,
        UUID parseResultId,
        Integer parseVersion) {

    public EffectiveDocumentRoleResult {
        Objects.requireNonNull(effectiveRole, "effectiveRole must not be null");
        Objects.requireNonNull(source, "source must not be null");
        if (source.isAuthoritativeDecision()) {
            Objects.requireNonNull(roleDecisionId, "authoritative decision requires roleDecisionId");
            Objects.requireNonNull(decisionVersionNo, "authoritative decision requires decisionVersionNo");
        } else if (roleDecisionId != null || decisionVersionNo != null) {
            throw new IllegalArgumentException("non-human effective role cannot reference a decision");
        }
        if ((parseResultId == null) != (parseVersion == null)) {
            throw new IllegalArgumentException("parse identity must be complete");
        }
    }
}
