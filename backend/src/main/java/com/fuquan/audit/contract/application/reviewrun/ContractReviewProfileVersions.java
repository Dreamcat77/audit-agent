package com.fuquan.audit.contract.application.reviewrun;

/** Phase 2A creation policy only; no review engine, F02 rule configuration or model has run. */
public final class ContractReviewProfileVersions {
    public static final int RUN_SCHEMA_VERSION = 1;
    public static final String REVIEW_PROFILE_VERSION = "review-profile-foundation-v1";
    public static final String RULE_VERSION = "f02-rule-manifest-v1";
    public static final String MODEL_POLICY_VERSION = "model-policy-no-execution-v1";

    private ContractReviewProfileVersions() { }
}
