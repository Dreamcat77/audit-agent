package com.fuquan.audit.review.application.port;

import com.fuquan.audit.review.domain.FindingAcknowledgement;
import java.util.List;
import java.util.UUID;

/** Task-scoped read port for durable finding acknowledgement facts. */
public interface FindingAcknowledgementReadRepository {

    List<FindingAcknowledgement> findByTaskId(UUID taskId);
}
