package com.fuquan.audit.contract.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.contract.application.port.ContractReviewSessionRepository;
import com.fuquan.audit.contract.application.port.MaterialBindingRepository;
import com.fuquan.audit.contract.application.port.ReviewDocumentRepository;
import com.fuquan.audit.contract.domain.ContractReviewSession;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContractReviewSessionDeleteApplicationTest {
    @Test
    void durableAuditFailurePropagatesAndLeavesIdempotencyUncommitted() {
        ContractReviewSessionRepository sessions = mock(ContractReviewSessionRepository.class);
        ReviewDocumentRepository documents = mock(ReviewDocumentRepository.class);
        MaterialBindingRepository bindings = mock(MaterialBindingRepository.class);
        InMemoryIdempotencyAdapter idempotency = new InMemoryIdempotencyAdapter();
        OperationAuditPort audit = mock(OperationAuditPort.class);
        ContractReviewSessionTransactionCoordinator coordinator =
                new ContractReviewSessionTransactionCoordinator(
                        sessions, documents, bindings, idempotency, audit);
        Instant now = Instant.parse("2026-08-20T00:00:00Z");
        ContractReviewSession session = ContractReviewSession.create(
                UUID.randomUUID(), UUID.randomUUID(), 1, "审核", "F02_DETERMINISTIC",
                null, "actor", now);
        CurrentUser user = new CurrentUser("actor", "actor", "Actor", Set.of("AUDITOR"));
        DeleteReviewSessionCommand command = new DeleteReviewSessionCommand(
                session.id(), 0, "delete-key", new ReviewRequestMetadata("request", "trace", "127.0.0.1"));
        when(sessions.findById(session.id())).thenReturn(Optional.of(session));
        when(documents.findPendingBySessionId(session.id())).thenReturn(List.of());
        when(sessions.softDelete(session.id(), 0, "actor", now))
                .thenReturn(session.softDelete("actor", now));
        org.mockito.Mockito.doThrow(new IllegalStateException("audit unavailable"))
                .when(audit).append(any());

        assertThatThrownBy(() -> coordinator.deleteSession(command, user, "fingerprint", now))
                .isInstanceOf(IllegalStateException.class);

        assertThat(idempotency.findByScopeAndKey(
                ContractReviewSessionService.DELETE_SCOPE, "delete-key")).isEmpty();
        verify(sessions).softDelete(session.id(), 0, "actor", now);
    }

    @Test
    void idempotencyCompletionFailureIsNotConvertedToSuccess() {
        ContractReviewSessionRepository sessions = mock(ContractReviewSessionRepository.class);
        ReviewDocumentRepository documents = mock(ReviewDocumentRepository.class);
        MaterialBindingRepository bindings = mock(MaterialBindingRepository.class);
        com.fuquan.audit.common.application.port.IdempotencyPort idempotency =
                mock(com.fuquan.audit.common.application.port.IdempotencyPort.class);
        OperationAuditPort audit = mock(OperationAuditPort.class);
        ContractReviewSessionTransactionCoordinator coordinator =
                new ContractReviewSessionTransactionCoordinator(
                        sessions, documents, bindings, idempotency, audit);
        Instant now = Instant.parse("2026-08-20T00:00:00Z");
        ContractReviewSession session = ContractReviewSession.create(
                UUID.randomUUID(), UUID.randomUUID(), 1, "审核", "F02_DETERMINISTIC",
                null, "actor", now);
        CurrentUser user = new CurrentUser("actor", "actor", "Actor", Set.of("AUDITOR"));
        DeleteReviewSessionCommand command = new DeleteReviewSessionCommand(
                session.id(), 0, "delete-key-2", new ReviewRequestMetadata("request", "trace", "127.0.0.1"));
        var record = new com.fuquan.audit.common.application.port.IdempotencyRecord(
                UUID.randomUUID(), ContractReviewSessionService.DELETE_SCOPE, "delete-key-2", "actor",
                "CONTRACT_REVIEW_SESSION", session.id().toString(), "fingerprint",
                com.fuquan.audit.common.application.port.IdempotencyStatus.IN_PROGRESS,
                "CONTRACT_REVIEW_SESSION", session.id().toString(), "request", "trace", now, null, 0);
        when(idempotency.claim(any())).thenReturn(
                new com.fuquan.audit.common.application.port.IdempotencyClaim(true, record));
        when(sessions.findById(session.id())).thenReturn(Optional.of(session));
        when(documents.findPendingBySessionId(session.id())).thenReturn(List.of());
        when(sessions.softDelete(session.id(), 0, "actor", now))
                .thenReturn(session.softDelete("actor", now));
        when(idempotency.markSucceeded(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("idempotency unavailable"));

        assertThatThrownBy(() -> coordinator.deleteSession(command, user, "fingerprint", now))
                .isInstanceOf(IllegalStateException.class);
        verify(idempotency).rollbackUncommittedClaim(record);
        verify(idempotency, never()).markFailed(any(), any());
    }
}
