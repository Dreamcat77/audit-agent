package com.fuquan.audit.contract.domain.project;

/** Stable deterministic integrity failure for immutable extraction-derived identities. */
public final class ProjectFactIdentityConflictException extends RuntimeException {
    public enum Reason {
        CANDIDATE_IDENTITY_CONFLICT,
        EXTRACTION_IDENTITY_CONFLICT,
        EXTRACTION_CANDIDATE_BINDING_CONFLICT
    }

    private final Reason reason;

    private ProjectFactIdentityConflictException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public static ProjectFactIdentityConflictException candidate() {
        return new ProjectFactIdentityConflictException(Reason.CANDIDATE_IDENTITY_CONFLICT);
    }

    public static ProjectFactIdentityConflictException extraction() {
        return new ProjectFactIdentityConflictException(Reason.EXTRACTION_IDENTITY_CONFLICT);
    }

    public static ProjectFactIdentityConflictException extractionCandidateBinding() {
        return new ProjectFactIdentityConflictException(Reason.EXTRACTION_CANDIDATE_BINDING_CONFLICT);
    }

    public String reasonCode() {
        return reason.name();
    }
}
