package com.fuquan.audit.contract.domain.reviewrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContractReviewResultDomainTest {
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID STEP = UUID.randomUUID();
    private static final UUID ATTEMPT = UUID.randomUUID();
    private static final UUID FINDING = UUID.randomUUID();

    @Test
    void findingPreservesTypedAttentionAndAnalyzerStatusWithoutFabricatedRisk() {
        var finding = finding("BREACH", "NEEDS_REVIEW", "CLAUSE_MISMATCH", null, "核对违约条款");

        assertThat(finding.runId()).isEqualTo(RUN);
        assertThat(finding.stepId()).isEqualTo(STEP);
        assertThat(finding.attemptId()).isEqualTo(ATTEMPT);
        assertThat(finding.source()).isEqualTo(ReviewFindingSource.DETERMINISTIC);
        assertThat(finding.statusCode()).isEqualTo("NEEDS_REVIEW");
        assertThat(finding.attentionCode()).isEqualTo("CLAUSE_MISMATCH");
        assertThat(finding.riskLevel()).isNull();
    }

    @Test
    void modelAssistedHintNeedsAttentionButNoInventedStatusOrRisk() {
        var finding = new ContractReviewFinding(FINDING, RUN, STEP, ATTEMPT, 1,
                "SEMANTIC_HINT", "CONTRACT_STRUCTURE", null, "REVIEW", null,
                "核对合同结构", null, 1, "semantic-v1", ReviewFindingSource.MODEL_ASSISTED,
                null, "typed-model", null, null, "schema-v1", Instant.EPOCH, 0);
        assertThat(finding.statusCode()).isNull();
        assertThat(finding.attentionCode()).isEqualTo("REVIEW");
        assertThat(finding.riskLevel()).isNull();
    }

    @Test
    void findingRejectsOverlongTextAndInvalidVersionsInsteadOfTruncating() {
        assertThatThrownBy(() -> finding("BREACH", "NEEDS_REVIEW", null, null, "甲".repeat(1001)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> finding("X".repeat(129), "NEEDS_REVIEW", null, null, "短说明"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContractReviewFinding(FINDING, RUN, STEP, ATTEMPT, 0,
                "BREACH", "CLAUSE", "NEEDS_REVIEW", null, null, "短说明", null,
                1, "clause-v1", ReviewFindingSource.DETERMINISTIC, null, null, null,
                null, null, Instant.EPOCH, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void modelRecommendationPreservesTheValidatedTwoThousandCharacterContract() {
        var accepted = new ContractReviewFinding(FINDING, RUN, STEP, ATTEMPT, 1,
                "SEMANTIC_HINT", "CONTRACT_STRUCTURE", null, "REVIEW", null,
                "typed title", "x".repeat(2000), 1, "semantic-v1",
                ReviewFindingSource.MODEL_ASSISTED, null, "typed-model", null,
                null, "schema-v1", Instant.EPOCH, 0);
        assertThat(accepted.recommendation()).hasSize(2000);
        assertThatThrownBy(() -> new ContractReviewFinding(FINDING, RUN, STEP, ATTEMPT, 1,
                "SEMANTIC_HINT", "CONTRACT_STRUCTURE", null, "REVIEW", null,
                "typed title", "x".repeat(2001), 1, "semantic-v1",
                ReviewFindingSource.MODEL_ASSISTED, null, "typed-model", null,
                null, "schema-v1", Instant.EPOCH, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void evidenceKeepsExactParseTupleAndOnlyValidatedCodePointLocator() {
        var evidence = evidence(true, 3, 5, "a".repeat(64), "违约");

        assertThat(evidence.runId()).isEqualTo(RUN);
        assertThat(evidence.stepId()).isEqualTo(STEP);
        assertThat(evidence.attemptId()).isEqualTo(ATTEMPT);
        assertThat(evidence.fileSha256()).isEqualTo("b".repeat(64));
        assertThat(evidence.parseVersion()).isEqualTo(2);
        assertThat(evidence.canonicalTextSha256()).isEqualTo("c".repeat(64));
        assertThat(evidence.startOffset()).isEqualTo(3);
        assertThat(evidence.endOffset()).isEqualTo(5);
    }

    @Test
    void unavailableEvidenceCannotCarryFabricatedOffsetsButKeepsExcerptHash() {
        assertThatThrownBy(() -> evidence(false, 0, 2, "a".repeat(64), "候选"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> evidence(true, null, null, null, "候选"))
                .isInstanceOf(IllegalArgumentException.class);
        var unavailable = evidence(false, null, null, "a".repeat(64), "候选");
        assertThat(unavailable.locatorAvailable()).isFalse();
        assertThat(unavailable.excerptSha256()).isEqualTo("a".repeat(64));
    }

    @Test
    void evidenceRejectsOverlongExcerptAndMalformedParseIdentity() {
        assertThatThrownBy(() -> evidence(false, null, null, null, "甲".repeat(1001)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> evidence(false, null, null, null, "\u7532".repeat(334)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContractReviewEvidence(UUID.randomUUID(), FINDING, RUN, STEP,
                ATTEMPT, 1, UUID.randomUUID(), "bad", UUID.randomUUID(), 2, "c".repeat(64),
                "SUPPORT", "LOCATOR_UNAVAILABLE", "ground-v1", null, null, null, null,
                null, null, false, null, null, null, "候选", 1, Instant.EPOCH, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ContractReviewFinding finding(String type, String status, String attention,
            String risk, String summary) {
        return new ContractReviewFinding(FINDING, RUN, STEP, ATTEMPT, 1, type, "CLAUSE",
                status, attention, risk, summary, null, 1, "clause-v1",
                ReviewFindingSource.DETERMINISTIC, null, null, null, null, null,
                Instant.EPOCH, 0);
    }

    private static ContractReviewEvidence evidence(boolean available, Integer start, Integer end,
            String excerptSha, String excerpt) {
        return new ContractReviewEvidence(UUID.randomUUID(), FINDING, RUN, STEP, ATTEMPT, 1,
                UUID.randomUUID(), "b".repeat(64), UUID.randomUUID(), 2, "c".repeat(64),
                "SUPPORT", available ? "VALIDATED" : "LOCATOR_UNAVAILABLE", "ground-v1",
                null, null, null, null, null, null, available, start, end, excerptSha,
                excerpt, 1, Instant.EPOCH, 0);
    }
}
