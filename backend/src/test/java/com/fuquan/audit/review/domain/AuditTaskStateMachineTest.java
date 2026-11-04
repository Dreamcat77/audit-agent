package com.fuquan.audit.review.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditTaskStateMachineTest {

    @Test
    void pendingCanTransitionToProcessing() {
        AuditTask processing = task().transitionTo(AuditTaskStatus.PROCESSING, instant(1));

        assertThat(processing.status()).isEqualTo(AuditTaskStatus.PROCESSING);
        assertThat(processing.startedAt()).isEqualTo(instant(1));
        assertThat(processing.versionNo()).isEqualTo(1);
    }

    @Test
    void processingCanTransitionToDone() {
        AuditTask done = task().transitionTo(AuditTaskStatus.PROCESSING, instant(1))
                .transitionTo(AuditTaskStatus.DONE, instant(2));

        assertThat(done.status()).isEqualTo(AuditTaskStatus.DONE);
        assertThat(done.finishedAt()).isEqualTo(instant(2));
        assertThat(done.versionNo()).isEqualTo(2);
    }

    @Test
    void processingCanTransitionToFailed() {
        AuditTask failed = task().transitionTo(AuditTaskStatus.PROCESSING, instant(1))
                .transitionTo(AuditTaskStatus.FAILED, instant(2));

        assertThat(failed.status()).isEqualTo(AuditTaskStatus.FAILED);
    }

    @Test
    void doneCannotReturnToProcessing() {
        AuditTask done = task().transitionTo(AuditTaskStatus.PROCESSING, instant(1))
                .transitionTo(AuditTaskStatus.DONE, instant(2));

        assertThatThrownBy(() -> done.transitionTo(AuditTaskStatus.PROCESSING, instant(3)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failedCannotBecomeDone() {
        AuditTask failed = task().transitionTo(AuditTaskStatus.PROCESSING, instant(1))
                .transitionTo(AuditTaskStatus.FAILED, instant(2));

        assertThatThrownBy(() -> failed.transitionTo(AuditTaskStatus.DONE, instant(3)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void newLifecycleCannotTransitionIntoLegacyStatuses() {
        assertThat(AuditTaskStatus.PENDING.canTransitionTo(AuditTaskStatus.PARSING)).isFalse();
        assertThat(AuditTaskStatus.PROCESSING.canTransitionTo(AuditTaskStatus.REVIEWING)).isFalse();
        assertThat(AuditTaskStatus.PROCESSING.canTransitionTo(AuditTaskStatus.NEEDS_MANUAL_REVIEW)).isFalse();
    }

    private AuditTask task() {
        return AuditTask.pending(
                UUID.randomUUID(), "CONTRACT", UUID.randomUUID(), 1, "F02_DETERMINISTIC",
                "user-1", "request-1", "0123456789abcdef0123456789abcdef", instant(0));
    }

    private Instant instant(int seconds) {
        return Instant.parse("2026-08-17T00:00:00Z").plusSeconds(seconds);
    }
}
