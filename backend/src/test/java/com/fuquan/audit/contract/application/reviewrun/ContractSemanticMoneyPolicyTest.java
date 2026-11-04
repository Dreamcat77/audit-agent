package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.assertThat;
import com.fuquan.audit.contract.application.port.ContractRuleConfigRepository;
import com.fuquan.audit.contract.domain.ContractRuleConfiguration;
import com.fuquan.audit.review.domain.RiskLevel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ContractSemanticMoneyPolicyTest {
    private static final String PROJECT = "11111111-1111-4111-8111-111111111111";
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(ContractRuleConfigRepository.class, () -> () -> List.of(
                    new ContractRuleConfiguration("F02_A", "测试规则", true, RiskLevel.RED,
                            true, 1, null, Instant.EPOCH)))
            .withBean(F02RulePolicyFingerprint.class)
            .withBean(ContractReviewExecutionPolicyResolver.class)
            .withPropertyValues("audit.contract.semantic-checkpoints.enabled=true");

    @Test void observeConfigurationCannotStartOutsideLocalDevelopment() {
        context.withPropertyValues("AUDIT_CONTRACT_SEMANTIC_MONEY_MODE=OBSERVE",
                "AUDIT_CONTRACT_SEMANTIC_OBSERVE_PROJECT_IDS=" + PROJECT,
                "spring.profiles.active=customer").run(result -> assertThat(result).hasFailed());
    }

    @Test void observeConfigurationRequiresExplicitProjectsAndDurableCheckpoints() {
        context.withPropertyValues("AUDIT_CONTRACT_SEMANTIC_MONEY_MODE=OBSERVE",
                "spring.profiles.active=local-dev").run(result -> assertThat(result).hasFailed());
        context.withPropertyValues("AUDIT_CONTRACT_SEMANTIC_MONEY_MODE=OBSERVE",
                "AUDIT_CONTRACT_SEMANTIC_OBSERVE_PROJECT_IDS=" + PROJECT,
                "spring.profiles.active=local-dev", "audit.contract.semantic-checkpoints.enabled=false")
                .run(result -> assertThat(result).hasFailed());
    }

    @Test void unknownModeFailsInsteadOfSelectingTheWiderMode() {
        context.withPropertyValues("AUDIT_CONTRACT_SEMANTIC_MONEY_MODE=unexpected")
                .run(result -> assertThat(result).hasFailed());
    }

    @Test void strictDefaultRetainsTheExistingFrozenIdentity() {
        context.run(result -> assertThat(result.getBean(ContractReviewExecutionPolicyResolver.class)
                .resolveForNewRun().modelPolicyVersion())
                .isEqualTo("contract-semantic-work-v3-redaction-v1-grounding-v1"));
    }

    @Test void onlyAllowlistedProjectGetsAnObserveIdentityAndF02ResolutionStillWorks() {
        context.withPropertyValues("AUDIT_CONTRACT_SEMANTIC_MONEY_MODE=OBSERVE",
                "AUDIT_CONTRACT_SEMANTIC_OBSERVE_PROJECT_IDS=" + PROJECT,
                "spring.profiles.active=local-dev").run(result -> {
                    var policy = result.getBean(ContractReviewExecutionPolicyResolver.class);
                    assertThat(policy.resolveForNewRun(UUID.fromString(PROJECT)).modelPolicyVersion())
                            .isEqualTo("contract-semantic-work-v3-redaction-v1-grounding-v1-money-observe-v1-hierarchy-v1");
                    assertThat(policy.resolveForNewRun(UUID.fromString("22222222-2222-4222-8222-222222222222")).modelPolicyVersion())
                            .isEqualTo("contract-semantic-work-v3-redaction-v1-grounding-v1");
                    assertThat(policy.resolveCurrentF02().modelPolicyVersion())
                            .isEqualTo("contract-semantic-work-v3-redaction-v1-grounding-v1");
                });
    }

    @Test void historicalObserveIdentityRemainsDurableAndDoesNotBecomeStrict() {
        assertThat(ContractReviewExecutionPolicyResolver.isDurableModelPolicy(
                "contract-semantic-work-v3-redaction-v1-grounding-v1-money-observe-v1")).isTrue();
        assertThat(ContractReviewExecutionPolicyResolver.moneyModeForPolicy(
                "contract-semantic-work-v3-redaction-v1-grounding-v1-money-observe-v1")).isEqualTo("OBSERVE");
        assertThat(ContractReviewExecutionPolicyResolver.isDurableModelPolicy(
                "contract-semantic-work-v3-redaction-v1-grounding-v1-money-observe-v1-hierarchy-v1")).isTrue();
        assertThat(ContractReviewExecutionPolicyResolver.moneyModeForPolicy(
                "contract-semantic-work-v3-redaction-v1-grounding-v1-money-observe-v1-hierarchy-v1")).isEqualTo("OBSERVE");
    }
}
