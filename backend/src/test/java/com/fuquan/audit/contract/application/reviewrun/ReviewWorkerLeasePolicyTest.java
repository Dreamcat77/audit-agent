package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.*;

import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.domain.reviewrun.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReviewWorkerLeasePolicyTest {
    static final Instant NOW = Instant.parse("2026-09-16T01:00:00Z");
    final ReviewWorkerLeasePolicy policy = new ReviewWorkerLeasePolicy();

    @Test void claimFreezesOwnerAndAdvancesVersion() {
        var claimed = policy.claim(pending(), "worker-a", NOW, NOW.plusSeconds(60));
        assertThat(claimed.status()).isEqualTo(ContractReviewStepStatus.RUNNING);
        assertThat(claimed.leaseOwner()).isEqualTo("worker-a");
        assertThat(claimed.versionNo()).isEqualTo(1);
        assertThat(claimed.claimedAt()).isEqualTo(NOW);
    }

    @Test void heartbeatInvalidatesOldFencingToken() {
        var claimed = policy.claim(pending(), "worker-a", NOW, NOW.plusSeconds(60));
        var renewed = policy.heartbeat(claimed, "worker-a", 1, NOW.plusSeconds(10), NOW.plusSeconds(70));
        assertThat(renewed.versionNo()).isEqualTo(2);
        assertThat(renewed.leaseUntil()).isEqualTo(NOW.plusSeconds(70));
        assertThatThrownBy(() -> policy.requireFence(renewed, "worker-a", 1, NOW.plusSeconds(11)))
                .isInstanceOf(BusinessException.class);
    }

    @Test void wrongOwnerAndExpiredLeaseCannotWrite() {
        var claimed = policy.claim(pending(), "worker-a", NOW, NOW.plusSeconds(60));
        assertThatThrownBy(() -> policy.requireFence(claimed, "worker-b", 1, NOW))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.requireFence(claimed, "worker-a", 1, NOW.plusSeconds(60)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.heartbeat(claimed, "worker-a", 1,
                NOW.plusSeconds(60), NOW.plusSeconds(120))).isInstanceOf(BusinessException.class);
    }

    @Test void scannerOnlyClassifiesExpiredLease() {
        var claimed = policy.claim(pending(), "worker-a", NOW, NOW.plusSeconds(60));
        assertThat(policy.recovery(claimed, NOW.plusSeconds(59)))
                .isEqualTo(ReviewWorkerLeasePolicy.Recovery.NONE);
        assertThat(policy.recovery(claimed, NOW.plusSeconds(60)))
                .isEqualTo(ReviewWorkerLeasePolicy.Recovery.RECLAIM_NOT_STARTED);
        assertThat(policy.recovery(pending(), NOW.plusSeconds(60)))
                .isEqualTo(ReviewWorkerLeasePolicy.Recovery.NONE);
    }

    @Test void escapedRequestAndUnprovableCompletedOutcomeNeverBecomeFailed() {
        var claimed = policy.claim(pending(), "worker-a", NOW, NOW.plusSeconds(60));
        for (var external : new ReviewStepExternalCallState[] {ReviewStepExternalCallState.REQUESTED,
                ReviewStepExternalCallState.OUTCOME_UNKNOWN, ReviewStepExternalCallState.COMPLETED}) {
            var attempt = new ContractReviewStepAttempt(claimed.attemptId(), claimed.runId(), claimed.stepId(),
                    1, claimed.operationIdentity(), claimed.status(), claimed.leaseOwner(), claimed.leaseUntil(),
                    claimed.claimedAt(), external, "b".repeat(64), null, null, null, NOW,
                    null, null, null, null, 2);
            assertThat(policy.recovery(attempt, NOW.plusSeconds(60)))
                    .isEqualTo(ReviewWorkerLeasePolicy.Recovery.UNKNOWN);
        }
    }

    @Test void cannotClaimRunningOrShortenHeartbeat() {
        var claimed = policy.claim(pending(), "worker-a", NOW, NOW.plusSeconds(60));
        assertThatThrownBy(() -> policy.claim(claimed, "worker-b", NOW, NOW.plusSeconds(60)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.heartbeat(claimed, "worker-a", 1, NOW, NOW.plusSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsUnboundedOrUnsafeTechnicalOwnerAndInvalidLease() {
        for (String owner : new String[] {"", "x".repeat(101), "worker\"injected", "worker\nother"})
            assertThatThrownBy(() -> policy.claim(pending(), owner, NOW, NOW.plusSeconds(60)))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.claim(pending(), "worker-a", NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static ContractReviewStepAttempt pending() {
        return new ContractReviewStepAttempt(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, "a".repeat(64), ContractReviewStepStatus.PENDING, null, null, null,
                ReviewStepExternalCallState.NOT_STARTED, null, null, null, null, null,
                null, null, null, null, 0);
    }
}
