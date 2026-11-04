package com.fuquan.audit.review.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuquan.audit.review.domain.FindingAcknowledgement;
import com.fuquan.audit.security.application.port.UserIdentityQueryPort;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FindingAcknowledgementViewTest {

    @Test
    void retainsActorIdButResolvesItsCurrentDisplayNameAtReadTime() {
        FindingAcknowledgement acknowledgement = acknowledgement("user-42");
        UserIdentityQueryPort users = id -> Optional.of("Updated Display Name");

        FindingAcknowledgementView view = FindingAcknowledgementView.from(acknowledgement, users);

        assertThat(view.acknowledgedBy()).isEqualTo("user-42");
        assertThat(view.acknowledgedByDisplayName()).isEqualTo("Updated Display Name");
    }

    @Test
    void usesFixedFallbackForUnknownOrDeletedActorWithoutChangingActorId() {
        FindingAcknowledgement acknowledgement = acknowledgement("deleted-user");

        FindingAcknowledgementView view = FindingAcknowledgementView.from(acknowledgement, id -> Optional.empty());

        assertThat(view.acknowledgedBy()).isEqualTo("deleted-user");
        assertThat(view.acknowledgedByDisplayName()).isEqualTo("未知或已删除用户");
    }

    private FindingAcknowledgement acknowledgement(String actorId) {
        return new FindingAcknowledgement(
                UUID.randomUUID(), UUID.randomUUID(), actorId, Instant.parse("2026-08-25T08:15:30Z"),
                "request", "trace", 1);
    }
}
