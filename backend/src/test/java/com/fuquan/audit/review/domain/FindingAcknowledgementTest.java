package com.fuquan.audit.review.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FindingAcknowledgementTest {

    @Test
    void retainsServerSuppliedFactFieldsExactly() {
        UUID id = UUID.randomUUID();
        UUID checkResultId = UUID.randomUUID();
        Instant acknowledgedAt = Instant.parse("2026-08-25T08:15:30.123456Z");

        FindingAcknowledgement acknowledgement = new FindingAcknowledgement(
                id, checkResultId, "user-42", acknowledgedAt, "request-42", "trace-42", 7L);

        assertThat(acknowledgement.id()).isEqualTo(id);
        assertThat(acknowledgement.checkResultId()).isEqualTo(checkResultId);
        assertThat(acknowledgement.acknowledgedBy()).isEqualTo("user-42");
        assertThat(acknowledgement.acknowledgedAt()).isEqualTo(acknowledgedAt);
        assertThat(acknowledgement.requestId()).isEqualTo("request-42");
        assertThat(acknowledgement.traceId()).isEqualTo("trace-42");
        assertThat(acknowledgement.ruleVersion()).isEqualTo(7L);
    }

    @Test
    void requiresEveryDurableFactFieldAndNonNegativeRuleVersion() {
        UUID id = UUID.randomUUID();
        UUID checkResultId = UUID.randomUUID();
        Instant acknowledgedAt = Instant.parse("2026-08-25T08:15:30Z");

        assertThatNullPointerException().isThrownBy(() -> new FindingAcknowledgement(
                null, checkResultId, "user", acknowledgedAt, "request", "trace", 0));
        assertThatNullPointerException().isThrownBy(() -> new FindingAcknowledgement(
                id, null, "user", acknowledgedAt, "request", "trace", 0));
        assertThatNullPointerException().isThrownBy(() -> new FindingAcknowledgement(
                id, checkResultId, null, acknowledgedAt, "request", "trace", 0));
        assertThatNullPointerException().isThrownBy(() -> new FindingAcknowledgement(
                id, checkResultId, "user", null, "request", "trace", 0));
        assertThatNullPointerException().isThrownBy(() -> new FindingAcknowledgement(
                id, checkResultId, "user", acknowledgedAt, null, "trace", 0));
        assertThatNullPointerException().isThrownBy(() -> new FindingAcknowledgement(
                id, checkResultId, "user", acknowledgedAt, "request", null, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new FindingAcknowledgement(
                id, checkResultId, "user", acknowledgedAt, "request", "trace", -1));
    }

    @Test
    void exposesNoMutationOrRevocationOperation() {
        assertThat(Arrays.stream(FindingAcknowledgement.class.getDeclaredMethods())
                .map(Method::getName))
                .doesNotContain("setAcknowledgedBy", "revoke", "delete", "update");
    }
}
