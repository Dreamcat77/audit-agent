package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.*;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisPort.*;
import com.fuquan.audit.contract.domain.ContractRuleConfiguration;
import com.fuquan.audit.review.domain.RiskLevel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ContractReviewHierarchyV2PolicyTest {
    private final UUID project=UUID.randomUUID();
    private ContractReviewExecutionPolicyResolver resolver(String version,String mode) {
        var env=new MockEnvironment();env.setActiveProfiles("local-dev");
        if(version!=null)env.setProperty("AUDIT_CONTRACT_SEMANTIC_HIERARCHY_VERSION",version);
        return new ContractReviewExecutionPolicyResolver(()->List.of(new ContractRuleConfiguration("F02_A", "F02_A", true,
                RiskLevel.RED, true, 1, null, Instant.EPOCH)),new F02RulePolicyFingerprint(),true,mode,project.toString(),env);
    }
    @Test void explicitV2OnlyChangesNewObserveAllowlistedRunsAndFrozenV1StillResolvesAsV1() {
        assertThat(resolver("v2","OBSERVE").resolveForNewRun(project).modelPolicyVersion())
                .isEqualTo("contract-semantic-work-v3-redaction-v1-grounding-v1-money-observe-v1-hierarchy-v2");
        assertThat(resolver(null,"OBSERVE").resolveForNewRun(project).modelPolicyVersion())
                .isEqualTo("contract-semantic-work-v3-redaction-v1-grounding-v1-money-observe-v1-hierarchy-v1");
        for(var candidate:List.of(resolver("v2","OBSERVE").resolveForNewRun(UUID.randomUUID()),resolver("v2","STRICT").resolveForNewRun(project)))
            assertThat(candidate.modelPolicyVersion()).isEqualTo("contract-semantic-work-v3-redaction-v1-grounding-v1");
        assertThat(ContractReviewExecutionPolicyResolver.hierarchyProtocolForPolicy("contract-semantic-work-v3-redaction-v1-grounding-v1-money-observe-v1-hierarchy-v1"))
                .isEqualTo("contract-bounded-hierarchy-v1");
        assertThat(ContractReviewExecutionPolicyResolver.hierarchyProtocolForPolicy("contract-semantic-work-v3-redaction-v1-grounding-v1-money-observe-v1-hierarchy-v2"))
                .isEqualTo("contract-bounded-hierarchy-v2");
        assertThat(ContractReviewExecutionPolicyResolver.isDurableModelPolicy("contract-semantic-work-v3-redaction-v1-grounding-v1-money-observe-v1-hierarchy-v2")).isTrue();
        assertThat(ContractReviewExecutionPolicyResolver.moneyModeForPolicy("contract-semantic-work-v3-redaction-v1-grounding-v1-money-observe-v1-hierarchy-v2")).isEqualTo("OBSERVE");
    }
    @Test void unapprovedVersionFailsClosedInsteadOfSilentlyChoosingAContract() {
        for(String value:List.of("v3","","V2"))assertThatThrownBy(()->resolver(value,"OBSERVE")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void portAcceptsExplicitObserveV2ButNotStrictOrUnknownProtocols() {
        var materials=List.of(new ContractTextAnalysisMaterial(UUID.randomUUID(),"CONTRACT_BODY","Public synthetic clause"));
        assertThatCode(()->new ContractTextAnalysisRequest(project,materials,"contract-semantic-quality-v1","OBSERVE","contract-bounded-hierarchy-v2"))
                .doesNotThrowAnyException();
        assertThatThrownBy(()->new ContractTextAnalysisRequest(project,materials,"contract-semantic-quality-v1","STRICT","contract-bounded-hierarchy-v2"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ContractTextAnalysisRequest(project,materials,"contract-semantic-quality-v1","OBSERVE","contract-bounded-hierarchy-v3"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
