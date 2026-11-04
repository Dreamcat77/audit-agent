package com.fuquan.audit.review.application.port;

import com.fuquan.audit.review.domain.FindingAcknowledgement;
import java.util.Optional;
import java.util.UUID;

/** Write and point-read port for immutable finding acknowledgement facts. */
public interface FindingAcknowledgementRepository {

    void create(FindingAcknowledgement acknowledgement);

    Optional<FindingAcknowledgement> findByCheckResultId(UUID checkResultId);
}
