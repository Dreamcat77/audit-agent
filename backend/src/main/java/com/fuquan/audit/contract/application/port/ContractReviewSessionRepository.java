package com.fuquan.audit.contract.application.port;

import com.fuquan.audit.contract.domain.ContractReviewSession;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Contract-owned ReviewSession persistence boundary. */
public interface ContractReviewSessionRepository {
    void create(ContractReviewSession session);

    Optional<ContractReviewSession> findById(UUID id);

    List<ContractReviewSession> findByContractId(UUID contractId);

    ContractReviewSession advancePreparingVersion(UUID id, long expectedVersion, Instant updatedAt);

    ContractReviewSession confirmMaterials(UUID id, long expectedVersion, Instant confirmedAt);

    ContractReviewSession softDelete(
            UUID id,
            long expectedVersion,
            String deletedBy,
            Instant deletedAt);
}
