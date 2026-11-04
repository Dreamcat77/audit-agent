package com.fuquan.audit.contract.application;

import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import java.util.UUID;

/** Verified navigation identity for one durable F02 AuditTask. */
public record ContractAuditTaskNavigation(
        Kind kind, UUID projectId, UUID runId, ContractReviewRunStatus status) {
    public enum Kind { INTEGRATED, INDEPENDENT_F02, UNAVAILABLE }

    public static ContractAuditTaskNavigation integrated(
            UUID projectId, UUID runId, ContractReviewRunStatus status) {
        return new ContractAuditTaskNavigation(Kind.INTEGRATED, projectId, runId, status);
    }

    public static ContractAuditTaskNavigation independent() {
        return new ContractAuditTaskNavigation(Kind.INDEPENDENT_F02, null, null, null);
    }

    public static ContractAuditTaskNavigation unavailable() {
        return new ContractAuditTaskNavigation(Kind.UNAVAILABLE, null, null, null);
    }

    public ContractAuditTaskNavigation {
        if (kind == Kind.INTEGRATED && (projectId == null || runId == null || status == null)) {
            throw new IllegalArgumentException("integrated task requires verified run identity");
        }
        if (kind != Kind.INTEGRATED && (projectId != null || runId != null || status != null)) {
            throw new IllegalArgumentException("unverified task must not expose run identity");
        }
    }
}
