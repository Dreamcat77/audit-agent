package com.fuquan.audit.contract.infra.memory;

import com.fuquan.audit.contract.application.port.ReviewDocumentRepository;
import com.fuquan.audit.contract.domain.ReviewDocument;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryReviewDocumentRepository implements ReviewDocumentRepository {
    private final InMemoryContractReviewStore store;

    public InMemoryReviewDocumentRepository(InMemoryContractReviewStore store) {
        this.store = store;
    }

    @Override public void create(ReviewDocument value) { store.createDocument(value); }
    @Override public Optional<ReviewDocument> findById(UUID id) { return store.findDocument(id); }
    @Override public Optional<ReviewDocument> findBySessionAndId(UUID sessionId, UUID id) {
        return store.findDocumentBySessionAndId(sessionId, id);
    }
    @Override public List<ReviewDocument> findBySessionId(UUID id) { return store.findDocumentsBySession(id); }
    @Override public List<ReviewDocument> findPendingBySessionId(UUID id) { return store.findPendingBySessionId(id); }
    @Override public ReviewDocument markAvailable(UUID id, long version, Instant at) {
        return store.markAvailable(id, version, at);
    }
    @Override public ReviewDocument markFailed(UUID id, long version, Instant at) {
        return store.markFailed(id, version, at);
    }
}
