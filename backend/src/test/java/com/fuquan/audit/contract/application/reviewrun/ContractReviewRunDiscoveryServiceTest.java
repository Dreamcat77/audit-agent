package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewRunRepository;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ContractReviewRunDiscoveryServiceTest {
    private static final Instant REQUESTED_AT = Instant.parse("2026-09-18T00:00:00Z");

    private final UUID projectId = UUID.randomUUID();
    private final UUID contractId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final CurrentUser actor = new CurrentUser("actor-1", "auditor", "Auditor", Set.of("AUDITOR"));
    private final AuthContext auth = mock(AuthContext.class);
    private final ContractReviewRunRepository runs = mock(ContractReviewRunRepository.class);
    private final ReviewStepOutputReadService outputs = mock(ReviewStepOutputReadService.class);
    private final ContractReviewRunDiscoveryService service = new ContractReviewRunDiscoveryService(auth, runs, outputs);

    @BeforeEach
    void authenticatedActor() {
        when(auth.currentUser()).thenReturn(Optional.of(actor));
    }

    @Test
    void rejectsMissingCurrentUserBeforeQueryingReviewRuns() {
        when(auth.currentUser()).thenReturn(Optional.empty());

        assertError(() -> service.getLatest(projectId, contractId), BusinessErrorCode.UNAUTHENTICATED);

        verify(auth).currentUser();
        verifyNoMoreInteractions(runs, outputs);
    }

    @Test
    void findsLatestRunUsingExactProjectContractAndAuthenticatedActor() {
        ContractReviewRun run = run(ContractReviewRunStatus.RUNNING);
        ReviewStepOutputReadService.RunOutput expected = mock(ReviewStepOutputReadService.RunOutput.class);
        when(runs.findLatestByProjectIdAndContractIdAndRequestedBy(projectId, contractId, actor.userId()))
                .thenReturn(Optional.of(run));
        when(outputs.getRun(projectId, contractId, runId)).thenReturn(expected);

        assertThat(service.getLatest(projectId, contractId)).isSameAs(expected);

        verify(runs).findLatestByProjectIdAndContractIdAndRequestedBy(projectId, contractId, actor.userId());
        verify(outputs).getRun(projectId, contractId, runId);
        verifyReadOnlyInteractions();
    }

    @Test
    void hidesMissingOrForeignLatestRunAsNotFoundWithoutReadingOutput() {
        when(runs.findLatestByProjectIdAndContractIdAndRequestedBy(projectId, contractId, actor.userId()))
                .thenReturn(Optional.empty());

        assertError(() -> service.getLatest(projectId, contractId), BusinessErrorCode.NOT_FOUND);

        verify(runs).findLatestByProjectIdAndContractIdAndRequestedBy(projectId, contractId, actor.userId());
        verifyNoMoreInteractions(outputs);
        verify(runs, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(runs, never()).transition(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(runs, never()).rollbackUncommittedRun(org.mockito.ArgumentMatchers.any());
    }

    @ParameterizedTest
    @MethodSource("allStatuses")
    void delegatesEveryLatestRunStatusToVerifiedOutputReader(ContractReviewRunStatus status) {
        ContractReviewRun run = run(status);
        ReviewStepOutputReadService.RunOutput expected = mock(ReviewStepOutputReadService.RunOutput.class);
        when(runs.findLatestByProjectIdAndContractIdAndRequestedBy(projectId, contractId, actor.userId()))
                .thenReturn(Optional.of(run));
        when(outputs.getRun(projectId, contractId, runId)).thenReturn(expected);

        assertThat(service.getLatest(projectId, contractId)).isSameAs(expected);

        verify(runs).findLatestByProjectIdAndContractIdAndRequestedBy(projectId, contractId, actor.userId());
        verify(outputs).getRun(projectId, contractId, runId);
        verifyReadOnlyInteractions();
    }

    @Test
    void propagatesCorruptLatestOutputConflictWithoutTryingOlderRun() {
        ContractReviewRun run = run(ContractReviewRunStatus.COMPLETED);
        BusinessException conflict = new BusinessException(BusinessErrorCode.CONFLICT);
        when(runs.findLatestByProjectIdAndContractIdAndRequestedBy(projectId, contractId, actor.userId()))
                .thenReturn(Optional.of(run));
        when(outputs.getRun(projectId, contractId, runId)).thenThrow(conflict);

        assertThatThrownBy(() -> service.getLatest(projectId, contractId)).isSameAs(conflict);

        verify(runs, times(1)).findLatestByProjectIdAndContractIdAndRequestedBy(
                projectId, contractId, actor.userId());
        verify(outputs).getRun(projectId, contractId, runId);
        verifyReadOnlyInteractions();
    }

    private void verifyReadOnlyInteractions() {
        verify(runs, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(runs, never()).transition(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(runs, never()).rollbackUncommittedRun(org.mockito.ArgumentMatchers.any());
        verifyNoMoreInteractions(runs, outputs);
    }

    private ContractReviewRun run(ContractReviewRunStatus status) {
        return new ContractReviewRun(runId, projectId, contractId, 1, null, null, 1, "{}",
                "a".repeat(64), "profile-v1", "rule-v1", "model-v1", "b".repeat(64),
                actor.userId(), status, REQUESTED_AT, null, null, null, null, null, 0);
    }

    private static Stream<ContractReviewRunStatus> allStatuses() {
        return Stream.of(ContractReviewRunStatus.PENDING, ContractReviewRunStatus.RUNNING,
                ContractReviewRunStatus.COMPLETED, ContractReviewRunStatus.PARTIALLY_COMPLETED,
                ContractReviewRunStatus.FAILED, ContractReviewRunStatus.UNKNOWN);
    }

    private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
            BusinessErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(expected));
    }
}
