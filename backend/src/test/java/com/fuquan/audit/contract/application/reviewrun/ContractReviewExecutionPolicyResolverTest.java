package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuquan.audit.contract.application.port.ContractRuleConfigRepository;
import com.fuquan.audit.contract.domain.ContractRuleConfiguration;
import com.fuquan.audit.review.domain.RiskLevel;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContractReviewExecutionPolicyResolverTest {
    @Test void durablePolicyIsExplicitAndLegacyDefaultDoesNotSilentlyChange() {
        ContractRuleConfigRepository source=() -> List.of(config("F02_A",1));
        assertThat(new ContractReviewExecutionPolicyResolver(source,new F02RulePolicyFingerprint(),true)
                .resolveForNewRun().modelPolicyVersion()).isEqualTo("contract-semantic-work-v3-redaction-v1-grounding-v1");
        assertThat(new ContractReviewExecutionPolicyResolver(source,new F02RulePolicyFingerprint(),false)
                .resolveForNewRun().modelPolicyVersion()).isEqualTo("contract-semantic-java-schema-v1-redaction-v1-grounding-v1");
    }
    @Test
    void freezesSortedRealRuleConfigurationAndJavaOwnedSemanticPolicyNotProviderRevision() {
        ContractRuleConfiguration a = config("F02_A", 1);
        ContractRuleConfiguration b = config("F02_B", 2);
        ContractRuleConfigRepository source = () -> List.of(b, a);
        var fingerprint = new F02RulePolicyFingerprint();
        var policy = new ContractReviewExecutionPolicyResolver(source, fingerprint).resolveForNewRun();
        assertThat(policy.enabledF02Configurations()).extracting(ContractRuleConfiguration::ruleCode)
                .containsExactly("F02_A", "F02_B");
        assertThat(policy.ruleVersion()).isEqualTo(fingerprint.fingerprint(List.of(a, b)));
        assertThat(policy.reviewProfileVersion()).isNotBlank();
        assertThat(policy.modelPolicyVersion()).isNotBlank()
                .doesNotContain("Qwen", "Paddle", "3090", "vLLM");
    }

    private static ContractRuleConfiguration config(String code, long version) {
        return new ContractRuleConfiguration(code, code, true, RiskLevel.RED, true,
                version, null, Instant.EPOCH);
    }
}
