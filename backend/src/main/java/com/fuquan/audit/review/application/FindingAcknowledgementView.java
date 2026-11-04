package com.fuquan.audit.review.application;

import com.fuquan.audit.review.domain.FindingAcknowledgement;
import com.fuquan.audit.security.application.port.UserIdentityQueryPort;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Read-time acknowledgement projection with a live security-owned display name. */
public record FindingAcknowledgementView(
        UUID id,
        UUID checkResultId,
        String acknowledgedBy,
        String acknowledgedByDisplayName,
        Instant acknowledgedAt,
        String requestId,
        String traceId,
        long ruleVersion) {

    public static final String UNKNOWN_OR_DELETED_USER_DISPLAY_NAME = "未知或已删除用户";

    public static FindingAcknowledgementView from(
            FindingAcknowledgement acknowledgement,
            UserIdentityQueryPort userIdentityQueryPort) {
        Objects.requireNonNull(acknowledgement, "acknowledgement must not be null");
        Objects.requireNonNull(userIdentityQueryPort, "userIdentityQueryPort must not be null");
        String displayName = userIdentityQueryPort.findDisplayNameByUserId(acknowledgement.acknowledgedBy())
                .orElse(UNKNOWN_OR_DELETED_USER_DISPLAY_NAME);
        return new FindingAcknowledgementView(
                acknowledgement.id(),
                acknowledgement.checkResultId(),
                acknowledgement.acknowledgedBy(),
                displayName,
                acknowledgement.acknowledgedAt(),
                acknowledgement.requestId(),
                acknowledgement.traceId(),
                acknowledgement.ruleVersion());
    }
}
