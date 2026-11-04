package com.fuquan.audit.contract.application.project.identity;

/** Machine-readable reason for an identity stage that has no current authoritative tuple. */
public enum EffectiveProjectIdentityRecoveryReason {
    PROJECT_METADATA_CONFIRMATION_REQUIRED,
    IDENTITY_ASSESSMENT_PENDING,
    IDENTITY_ASSESSMENT_STALE
}
