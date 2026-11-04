package com.fuquan.audit.contract.application.port;

import com.fuquan.audit.contract.domain.ReviewDocument;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Append-only ReviewDocument metadata persistence boundary. */
public interface ReviewDocumentRepository {
    void create(ReviewDocument document);

    Optional<ReviewDocument> findById(UUID id);

    Optional<ReviewDocument> findBySessionAndId(UUID sessionId, UUID id);

    List<ReviewDocument> findBySessionId(UUID sessionId);

    List<ReviewDocument> findPendingBySessionId(UUID sessionId);

    ReviewDocument markAvailable(UUID id, long expectedVersion, Instant storedAt);

    ReviewDocument markFailed(UUID id, long expectedVersion, Instant failedAt);
}
