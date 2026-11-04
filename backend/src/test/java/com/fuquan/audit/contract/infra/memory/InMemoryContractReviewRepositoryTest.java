package com.fuquan.audit.contract.infra.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.contract.application.port.ContractReviewConcurrencyException;
import com.fuquan.audit.contract.domain.ContractReviewSession;
import com.fuquan.audit.contract.domain.MaterialBinding;
import com.fuquan.audit.contract.domain.ReviewDocument;
import com.fuquan.audit.contract.domain.ReviewDocumentKind;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryContractReviewRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");
    private InMemoryContractReviewStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryContractReviewStore();
    }

    @Test
    void sessionCreateFindListAndCasMatchDatabaseSemantics() {
        UUID contractId = UUID.randomUUID();
        ContractReviewSession first = session(contractId, NOW);
        ContractReviewSession second = session(contractId, NOW.plusSeconds(1));
        store.createSession(first);
        store.createSession(second);

        assertThat(store.findSession(first.id())).contains(first);
        assertThat(store.findSessionsByContract(contractId)).containsExactly(second, first);
        assertThat(store.advancePreparingVersion(first.id(), 0, NOW.plusSeconds(2)).versionNo()).isEqualTo(1);
        assertThatThrownBy(() -> store.advancePreparingVersion(first.id(), 0, NOW.plusSeconds(3)))
                .isInstanceOf(ContractReviewConcurrencyException.class);
    }

    @Test
    void twoConcurrentSessionCasHaveExactlyOneWinner() throws Exception {
        ContractReviewSession session = session(UUID.randomUUID(), NOW);
        store.createSession(session);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> advance(session.id(), start));
            Future<Boolean> second = executor.submit(() -> advance(session.id(), start));
            start.countDown();
            assertThat(java.util.List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void softDeleteIsCasProtectedAndActiveContractListExcludesTombstone() {
        UUID contractId = UUID.randomUUID();
        ContractReviewSession session = session(contractId, NOW);
        store.createSession(session);

        ContractReviewSession deleted = store.softDelete(session.id(), 0, "actor", NOW.plusSeconds(1));

        assertThat(deleted.deleted()).isTrue();
        assertThat(store.findSession(session.id())).contains(deleted);
        assertThat(store.findSessionsByContract(contractId)).isEmpty();
        assertThatThrownBy(() -> store.softDelete(session.id(), 0, "actor", NOW.plusSeconds(2)))
                .isInstanceOf(ContractReviewConcurrencyException.class);
        assertThatThrownBy(() -> store.advancePreparingVersion(
                session.id(), deleted.versionNo(), NOW.plusSeconds(2)))
                .isInstanceOf(ContractReviewConcurrencyException.class);
    }

    @Test
    void singletonBindingIsUniqueAndBindingCasChecksDocumentActiveAndVersion() {
        UUID sessionId = UUID.randomUUID();
        ReviewDocument first = available(sessionId, ReviewDocumentKind.CONTRACT_BODY, UUID.randomUUID(), 0);
        ReviewDocument second = available(sessionId, ReviewDocumentKind.CONTRACT_BODY, UUID.randomUUID(), 2);
        store.createDocument(first);
        store.createDocument(second);
        MaterialBinding binding = MaterialBinding.activeFor(first, NOW.plusSeconds(1));
        store.createBinding(binding);

        assertThatThrownBy(() -> store.createBinding(MaterialBinding.activeFor(first, NOW.plusSeconds(2))))
                .isInstanceOf(ContractReviewConcurrencyException.class);
        MaterialBinding replaced = store.bind(
                binding.id(), first.id(), true, 0, second.id(), NOW.plusSeconds(3));
        assertThat(replaced.boundDocumentId()).isEqualTo(second.id());
        assertThatThrownBy(() -> store.bind(
                binding.id(), first.id(), true, 0, second.id(), NOW.plusSeconds(4)))
                .isInstanceOf(ContractReviewConcurrencyException.class);
        assertThat(store.deactivate(replaced.id(), second.id(), replaced.versionNo(), NOW.plusSeconds(5)).active())
                .isFalse();
    }

    @Test
    void multipleTenderDocumentsUseDifferentServerSlots() {
        UUID sessionId = UUID.randomUUID();
        ReviewDocument first = available(sessionId, ReviewDocumentKind.TENDER_DOCUMENT, UUID.randomUUID(), 0);
        ReviewDocument second = available(sessionId, ReviewDocumentKind.TENDER_DOCUMENT, UUID.randomUUID(), 2);
        store.createDocument(first);
        store.createDocument(second);
        store.createBinding(MaterialBinding.activeFor(first, NOW));
        store.createBinding(MaterialBinding.activeFor(second, NOW));

        assertThat(store.findBindingsBySession(sessionId)).hasSize(2)
                .extracting(MaterialBinding::slotKey).doesNotHaveDuplicates();
    }

    private boolean advance(UUID id, CountDownLatch start) throws Exception {
        start.await();
        try {
            store.advancePreparingVersion(id, 0, NOW.plusSeconds(1));
            return true;
        } catch (ContractReviewConcurrencyException exception) {
            return false;
        }
    }

    private ContractReviewSession session(UUID contractId, Instant at) {
        return ContractReviewSession.create(
                UUID.randomUUID(), contractId, 1, "审核", "F02_DETERMINISTIC", null, "user", at);
    }

    private ReviewDocument available(
            UUID sessionId, ReviewDocumentKind kind, UUID id, long requestedVersion) {
        return ReviewDocument.pending(
                id, sessionId, kind, kind.newSlotKey(id), kind.name() + ".pdf", "pdf", "application/pdf",
                10, Integer.toHexString(id.hashCode()).replace("-", "0").repeat(64).substring(0, 64),
                "a".repeat(63) + (requestedVersion % 10), null, requestedVersion, "user", NOW)
                .markAvailable(NOW.plusSeconds(1));
    }
}
