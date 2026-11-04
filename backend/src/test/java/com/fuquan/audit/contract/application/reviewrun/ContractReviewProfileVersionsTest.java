package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContractReviewProfileVersionsTest {
    @Test
    void creationPolicyVersionsAreServerOwnedAndDoNotClaimAnExecutedModelOrRuleSet() {
        assertThat(ContractReviewProfileVersions.RUN_SCHEMA_VERSION).isEqualTo(1);
        assertThat(ContractReviewProfileVersions.REVIEW_PROFILE_VERSION).isNotBlank();
        assertThat(ContractReviewProfileVersions.RULE_VERSION).contains("manifest");
        assertThat(ContractReviewProfileVersions.MODEL_POLICY_VERSION).contains("no-execution");
    }
}
