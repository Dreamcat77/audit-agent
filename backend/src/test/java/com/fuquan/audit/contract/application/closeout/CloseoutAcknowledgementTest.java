package com.fuquan.audit.contract.application.closeout;

import static org.junit.jupiter.api.Assertions.*;
import com.fuquan.audit.contract.domain.closeout.ContractReviewFindingAcknowledgement;
import org.junit.jupiter.api.Test;

class CloseoutAcknowledgementTest {
    @Test void emptyRediscoveryStillRejectsWrongOwnerAndF02() {
        var f = new CloseoutFixtures(); var service = service(f);
        assertTrue(service.list(f.project, f.contract, f.runId, f.finding.findingId()).isEmpty());
        var wrong = assertThrows(com.fuquan.audit.common.exception.BusinessException.class,
                () -> service.list(java.util.UUID.randomUUID(), f.contract, f.runId, f.finding.findingId()));
        assertEquals(com.fuquan.audit.common.exception.BusinessErrorCode.NOT_FOUND, wrong.errorCode());
        assertThrows(com.fuquan.audit.common.exception.BusinessException.class,
                () -> service.list(f.project, f.contract, f.runId, f.check.id()));
    }
    @Test void corruptExistingWinnerCannotBeReturnedUnderAnotherContract() {
        var f = new CloseoutFixtures(); var service = service(f);
        f.closeout.insertAcknowledgement(new ContractReviewFindingAcknowledgement(java.util.UUID.randomUUID(), f.project,
                java.util.UUID.randomUUID(), f.runId, f.finding.stepId(), f.finding.attemptId(), f.finding.findingId(), 0,
                f.actor.userId(), f.now, null, "request", "trace", f.now, 0));
        assertThrows(com.fuquan.audit.common.exception.BusinessException.class, () -> service.acknowledge(
                f.project, f.contract, f.runId, f.finding.findingId(), 0, null, "corrupt-winner", f.metadata));
        assertTrue(f.idempotency.findByScopeAndKey(AcknowledgementTransactionCoordinator.SCOPE, "corrupt-winner").isEmpty());
    }
    @Test void auditFailureCannotLeaveAReopenableLocalAcknowledgement() {
        var f = new CloseoutFixtures();
        var service = new ContractReviewAcknowledgementService(f.auth, f.closeout,
                new AcknowledgementTransactionCoordinator(new CloseoutOwnership(f.outputs, f.auth), f.closeout,
                        f.idempotency, event -> { throw new IllegalStateException("audit unavailable"); }, f.clock));
        assertThrows(IllegalStateException.class, () -> service.acknowledge(f.project, f.contract,
                f.runId, f.finding.findingId(), 0, null, "audit-fail", f.metadata));
        assertTrue(f.closeout.findAcknowledgementsByRun(f.runId).isEmpty());
        assertTrue(f.idempotency.findByScopeAndKey(AcknowledgementTransactionCoordinator.SCOPE, "audit-fail").isEmpty());
    }
    private ContractReviewAcknowledgementService service(CloseoutFixtures f) {
        var owner = new CloseoutOwnership(f.outputs, f.auth);
        return new ContractReviewAcknowledgementService(f.auth, f.closeout,
                new AcknowledgementTransactionCoordinator(owner, f.closeout, f.idempotency, f.audit::add, f.clock));
    }
    @Test void exactChainCommitsActorAuditAndReplaysNormalizedNote() {
        var f = new CloseoutFixtures(); var service = service(f);
        var first = service.acknowledge(f.project, f.contract, f.runId, f.finding.findingId(), 0, " 意见 ", "key", f.metadata);
        var replay = service.acknowledge(f.project, f.contract, f.runId, f.finding.findingId(), 0, "意见", "key", f.metadata);
        assertEquals(first, replay); assertEquals(f.actor.userId(), first.acknowledgedBy());
        assertEquals(1, f.audit.size()); assertNull(f.audit.get(0).taskId());
        assertEquals(f.finding, f.results.findFindingsByRunId(f.runId).get(0));
        assertEquals(first, service.get(f.project, f.contract, f.runId, f.finding.findingId(), first.acknowledgementId()));
    }
    @Test void anotherUserCannotReadOrAcknowledgeTheRunOwnersFinding() {
        var f = new CloseoutFixtures(); var service = service(f);
        var own = service.acknowledge(f.project, f.contract, f.runId, f.finding.findingId(),
                0, "已核对", "owner-key", f.metadata);
        org.mockito.Mockito.when(f.auth.currentUser()).thenReturn(java.util.Optional.of(
                new com.fuquan.audit.security.domain.CurrentUser("another", "other", "另一人员", java.util.Set.of("AUDITOR"))));
        assertEquals(com.fuquan.audit.common.exception.BusinessErrorCode.NOT_FOUND,
                assertThrows(com.fuquan.audit.common.exception.BusinessException.class,
                        () -> service.list(f.project, f.contract, f.runId, f.finding.findingId())).errorCode());
        assertEquals(com.fuquan.audit.common.exception.BusinessErrorCode.NOT_FOUND,
                assertThrows(com.fuquan.audit.common.exception.BusinessException.class,
                        () -> service.get(f.project, f.contract, f.runId, f.finding.findingId(), own.acknowledgementId())).errorCode());
        assertEquals(com.fuquan.audit.common.exception.BusinessErrorCode.NOT_FOUND,
                assertThrows(com.fuquan.audit.common.exception.BusinessException.class,
                        () -> service.acknowledge(f.project, f.contract, f.runId, f.finding.findingId(),
                                0, "越权", "other-key", f.metadata)).errorCode());
        assertTrue(f.idempotency.findByScopeAndKey(AcknowledgementTransactionCoordinator.SCOPE, "other-key").isEmpty());
    }
    @Test void wrongProjectContractRunAndF02AreSafeNotFound() {
        var f = new CloseoutFixtures(); var service = service(f);
        for (int index = 0; index < 4; index++) {
            int i = index;
            var error = assertThrows(com.fuquan.audit.common.exception.BusinessException.class,
                    () -> service.acknowledge(i == 0 ? java.util.UUID.randomUUID() : f.project,
                            i == 1 ? java.util.UUID.randomUUID() : f.contract,
                            i == 2 ? java.util.UUID.randomUUID() : f.runId,
                            i == 3 ? f.check.id() : f.finding.findingId(), 0, null, "key", f.metadata));
            assertEquals(com.fuquan.audit.common.exception.BusinessErrorCode.NOT_FOUND, error.errorCode());
        }
        assertTrue(f.closeout.findAcknowledgementsByRun(f.runId).isEmpty());
    }
    @Test void changedNoteVersionOrActorWithSameKeyConflicts() {
        var f = new CloseoutFixtures(); var service = service(f);
        service.acknowledge(f.project, f.contract, f.runId, f.finding.findingId(), 0, "意见", "key", f.metadata);
        assertThrows(com.fuquan.audit.common.exception.BusinessException.class, () ->
                service.acknowledge(f.project, f.contract, f.runId, f.finding.findingId(), 0, "改变", "key", f.metadata));
        assertThrows(com.fuquan.audit.common.exception.BusinessException.class, () ->
                service.acknowledge(f.project, f.contract, f.runId, f.finding.findingId(), 1, "意见", "key", f.metadata));
        org.mockito.Mockito.when(f.auth.currentUser()).thenReturn(java.util.Optional.of(
                new com.fuquan.audit.security.domain.CurrentUser("another", "other", "另一人员", java.util.Set.of("AUDITOR"))));
        assertThrows(com.fuquan.audit.common.exception.BusinessException.class, () ->
                service.acknowledge(f.project, f.contract, f.runId, f.finding.findingId(), 0, "意见", "key", f.metadata));
    }
    @Test void independentDuplicateCommandsReopenOneWinner() throws Exception {
        var f = new CloseoutFixtures(); var service = service(f);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> service.acknowledge(f.project, f.contract, f.runId, f.finding.findingId(), 0, "甲", "a", f.metadata));
            var b = pool.submit(() -> service.acknowledge(f.project, f.contract, f.runId, f.finding.findingId(), 0, "乙", "b", f.metadata));
            assertEquals(a.get().acknowledgementId(), b.get().acknowledgementId());
            assertEquals(1, f.closeout.findAcknowledgementsByRun(f.runId).size());
        } finally { pool.shutdownNow(); }
    }
    @Test void trimsOptionalNoteWithoutCountingSurrogatePairsTwice() {
        assertNull(ContractReviewFindingAcknowledgement.normalizeNote(null));
        assertNull(ContractReviewFindingAcknowledgement.normalizeNote("   "));
        assertEquals("意见", ContractReviewFindingAcknowledgement.normalizeNote("  意见  "));
        assertEquals(2000, ContractReviewFindingAcknowledgement.normalizeNote("😀".repeat(1000)).length());
        assertThrows(IllegalArgumentException.class, () -> ContractReviewFindingAcknowledgement.normalizeNote("😀".repeat(1001)));
    }
}
