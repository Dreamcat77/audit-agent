package com.fuquan.audit.contract.infra.memory;

import com.fuquan.audit.contract.application.port.ContractReviewSessionRepository;
import com.fuquan.audit.contract.domain.ContractReviewSession;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryContractReviewSessionRepository implements ContractReviewSessionRepository {
    private final InMemoryContractReviewStore store;

    public InMemoryContractReviewSessionRepository(InMemoryContractReviewStore store) {
        this.store = store;
    }

    @Override public void create(ContractReviewSession session) { store.createSession(session); }
    @Override public Optional<ContractReviewSession> findById(UUID id) { return store.findSession(id); }
    @Override public List<ContractReviewSession> findByContractId(UUID id) { return store.findSessionsByContract(id); }
    @Override public ContractReviewSession advancePreparingVersion(UUID id, long version, Instant at) {
        return store.advancePreparingVersion(id, version, at);
    }
    @Override public ContractReviewSession confirmMaterials(UUID id, long version, Instant at) {
        return store.confirmMaterials(id, version, at);
    }
    @Override public ContractReviewSession softDelete(
            UUID id, long version, String deletedBy, Instant at) {
        return store.softDelete(id, version, deletedBy, at);
    }
}
