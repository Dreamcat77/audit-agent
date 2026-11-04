package com.fuquan.audit.contract.application.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.contract.application.reviewrun.ContractReviewRunCreateResult;
import com.fuquan.audit.contract.domain.reviewrun.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class DebugManifestBuilderTest {
    static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID CONTRACT = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-000000000003");
    static final Instant AT = Instant.parse("2026-09-16T00:00:00Z");
    static DebugScope scope() { return new DebugScope(PROJECT, CONTRACT, RUN, null, null); }
    static ContractReviewRunCreateResult source() {
        var run = new ContractReviewRun(RUN, PROJECT, CONTRACT, 1, null, null, 1,
                "SECRET_CANONICAL_BODY", "a".repeat(64), "prompt-secret", "rule-secret", "provider-secret",
                "b".repeat(64), "customer-name", ContractReviewRunStatus.PENDING, AT,
                null, null, null, null, null, 0);
        return new ContractReviewRunCreateResult(run, List.of(), List.of(), List.of(), false);
    }
    @ParameterizedTest
    @ValueSource(strings={"prompt","rawResponse","fullText","canonicalText","ocrText","stack","cookie","token","password","path","filename","customerName"})
    void rejectsFieldsOutsidePositiveAllowlist(String field) {
        assertThatThrownBy(() -> DebugManifestBuilder.validateMetadata(Map.of(field,"CANARY")))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsTextDisguisedAsStatusOrHash() {
        assertThatThrownBy(() -> DebugManifestBuilder.validateMetadata(Map.of("status","private-data")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DebugManifestBuilder.validateMetadata(Map.of("sha256","/private/path")))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void scopeRequiresRunAndStepBeforeAttempt() {
        assertThatThrownBy(() -> new DebugScope(PROJECT,CONTRACT,null,null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DebugScope(PROJECT,CONTRACT,RUN,null,UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void futureRedactedPolicyAlwaysFailsClosed() {
        assertThatThrownBy(() -> DebugContentLevel.CUSTOMER_POLICY_APPROVED_REDACTED.requireAvailable())
                .isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class);
    }
    @Test void projectsOnlySafeMetadataAndDoesNotInferVerification() throws Exception {
        String json = new DebugManifestBuilder(DebugHashPolicy.internal()).build(RUN, scope(), AT, "private-user", source());
        assertThat(json).doesNotContain("SECRET_CANONICAL_BODY","customer-name","prompt-secret","provider-secret","private-user");
        var tree = new ObjectMapper().readTree(json);
        assertThat(tree.path("verificationLabels").isEmpty()).isTrue();
        assertThat(tree.path("contentLevel").asText()).isEqualTo("METADATA_ONLY");
        assertThat(tree.path("entries").get(0).path("safeMetadata").path("status").asText()).isEqualTo("PENDING");
        assertThat(json).isEqualTo(new DebugManifestBuilder(DebugHashPolicy.internal()).build(RUN, scope(), AT, "private-user", source()));
    }
    @Test void hashRemovalDoesNotChangeScopeIdentity() {
        String json = new DebugManifestBuilder(hash -> Optional.empty()).build(RUN,scope(),AT,"actor",source());
        assertThat(json).doesNotContain("a".repeat(64),"b".repeat(64)).contains(RUN.toString());
    }
}
