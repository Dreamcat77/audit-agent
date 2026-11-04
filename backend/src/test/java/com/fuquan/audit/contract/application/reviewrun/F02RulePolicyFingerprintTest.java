package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.domain.ContractRuleConfiguration;
import com.fuquan.audit.review.domain.RiskLevel;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class F02RulePolicyFingerprintTest {
    private final F02RulePolicyFingerprint policy = new F02RulePolicyFingerprint();

    @Test
    void sortedEnabledExecutionFieldsHaveOneStableFingerprintIndependentOfRowOrderAndDescriptions() {
        var a = config("F02_A", true, RiskLevel.RED, true, 1, "first", Instant.parse("2026-09-15T00:00:00Z"));
        var b = config("F02_B", true, RiskLevel.YELLOW, false, 2, "second", Instant.parse("2026-09-15T00:00:00Z"));
        var changedMetadata = config("F02_A", true, RiskLevel.RED, true, 1, "rewritten",
                Instant.parse("2026-09-16T00:00:00Z"));
        String original = policy.fingerprint(List.of(b, a));
        assertThat(original).isEqualTo(policy.fingerprint(List.of(changedMetadata, b)))
                .startsWith("f02-policy-v2:").hasSize(78);
        String frozenManifest = "{\"enginePolicySchemaVersion\":2,\"frozenSourcePolicy\":\"f02-frozen-evidence-v3\",\"rules\":["
                + "{\"ruleCode\":\"F02_A\",\"enabled\":true,\"failLevel\":\"RED\",\"blockSign\":true,\"versionNo\":1},"
                + "{\"ruleCode\":\"F02_B\",\"enabled\":true,\"failLevel\":\"YELLOW\",\"blockSign\":false,\"versionNo\":2}]}";
        assertThat(original).isEqualTo("f02-policy-v2:" + ContractSnapshotCanonicalizer.sha256(frozenManifest));
    }

    @Test
    void changedEnabledRiskBlockingOrVersionAltersExecutionAuthority() {
        var original = config("F02_A", true, RiskLevel.RED, true, 1, "same", Instant.EPOCH);
        String before = policy.fingerprint(List.of(original));
        assertThat(policy.fingerprint(List.of(original, config("F02_B", true, RiskLevel.RED, true,
                1, "same", Instant.EPOCH)))).isNotEqualTo(before);
        assertThat(policy.fingerprint(List.of(config("F02_A", true, RiskLevel.YELLOW, true,
                1, "same", Instant.EPOCH)))).isNotEqualTo(before);
        assertThat(policy.fingerprint(List.of(config("F02_A", true, RiskLevel.RED, false,
                1, "same", Instant.EPOCH)))).isNotEqualTo(before);
        assertThat(policy.fingerprint(List.of(config("F02_A", true, RiskLevel.RED, true,
                2, "same", Instant.EPOCH)))).isNotEqualTo(before);
        assertThatThrownBy(() -> policy.fingerprint(List.of(config("F02_A", false,
                RiskLevel.RED, true, 1, "same", Instant.EPOCH))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void duplicatedEnabledRuleCodeCannotProduceAnAmbiguousManifest() {
        var one = config("F02_A", true, RiskLevel.RED, true, 1, "same", Instant.EPOCH);
        assertThatThrownBy(() -> policy.fingerprint(List.of(one, one)))
                .isInstanceOf(BusinessException.class);
    }

    private static ContractRuleConfiguration config(String code, boolean enabled, RiskLevel level,
            boolean blockSign, long version, String description, Instant updatedAt) {
        return new ContractRuleConfiguration(code, code, enabled, level, blockSign,
                version, description, updatedAt);
    }
}
