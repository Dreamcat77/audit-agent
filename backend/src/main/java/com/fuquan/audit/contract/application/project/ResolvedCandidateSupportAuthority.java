package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.ProjectIdentityResolution;
import java.util.List;
import java.util.UUID;

/** Server-resolved authority only; source text and business values never leave the validator. */
public record ResolvedCandidateSupportAuthority(
        UUID projectId,
        UUID sourceFileId,
        String fileSha256,
        UUID parseResultId,
        int parseVersion,
        long projectVersionNo,
        DocumentRole effectiveRole,
        UUID roleDecisionId,
        ProjectIdentityResolution identityResolution,
        UUID identityDecisionId,
        List<UUID> eligibleFactIds,
        String sourceFactFingerprint) {
    public ResolvedCandidateSupportAuthority {
        eligibleFactIds = List.copyOf(eligibleFactIds);
    }
}
