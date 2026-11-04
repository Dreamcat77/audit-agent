package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.contract.domain.project.CandidateAuthorityMode;
import java.util.List;

/** Safe immutable snapshot, independent of the command fingerprint and support cohort counter. */
public record ResolvedCandidateSupportAuthoritySet(
        CandidateAuthorityMode authorityMode,
        List<ResolvedCandidateSupportAuthority> supports,
        String supportAuthorityFingerprint) {
    public ResolvedCandidateSupportAuthoritySet {
        supports = List.copyOf(supports);
    }
}
