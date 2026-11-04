package com.fuquan.audit.contract.application;

import com.fuquan.audit.review.application.FindingAcknowledgementView;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One source-presentation or real finding item in contract-owned F02 progress. */
public record F02ReviewProgressItem(
        String type,
        UUID checkResultId,
        String title,
        int displayOrder,
        String status,
        String risk,
        Instant completedAt,
        FindingAcknowledgementView acknowledgement,
        FindingSourceProjection source) {

    public F02ReviewProgressItem(String type, UUID checkResultId, String title, int displayOrder,
            String status, String risk, Instant completedAt, FindingAcknowledgementView acknowledgement) {
        this(type, checkResultId, title, displayOrder, status, risk, completedAt, acknowledgement, null);
    }

    public F02ReviewProgressItem {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(title, "title must not be null");
        if (displayOrder < 0) {
            throw new IllegalArgumentException("displayOrder must be non-negative");
        }
        Objects.requireNonNull(status, "status must not be null");
    }
}
