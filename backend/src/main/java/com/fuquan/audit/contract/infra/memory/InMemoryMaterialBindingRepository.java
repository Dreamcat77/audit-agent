package com.fuquan.audit.contract.infra.memory;

import com.fuquan.audit.contract.application.port.MaterialBindingRepository;
import com.fuquan.audit.contract.domain.MaterialBinding;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryMaterialBindingRepository implements MaterialBindingRepository {
    private final InMemoryContractReviewStore store;

    public InMemoryMaterialBindingRepository(InMemoryContractReviewStore store) {
        this.store = store;
    }

    @Override public void create(MaterialBinding value) { store.createBinding(value); }
    @Override public Optional<MaterialBinding> findBySessionAndSlot(UUID sessionId, String slot) {
        return store.findBySessionAndSlot(sessionId, slot);
    }
    @Override public List<MaterialBinding> findBySessionId(UUID id) { return store.findBindingsBySession(id); }
    @Override public MaterialBinding bind(
            UUID id, UUID expectedDocumentId, boolean expectedActive, long version,
            UUID newDocumentId, Instant at) {
        return store.bind(id, expectedDocumentId, expectedActive, version, newDocumentId, at);
    }
    @Override public MaterialBinding deactivate(UUID id, UUID documentId, long version, Instant at) {
        return store.deactivate(id, documentId, version, at);
    }
}
