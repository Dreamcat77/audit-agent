package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.common.application.port.IdempotencyPort;
import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.Money;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunMaterial;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewStepRepository;
import com.fuquan.audit.contract.infra.memory.reviewrun.InMemoryContractReviewRunRepositories;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.security.domain.CurrentUser;
import java.math.BigDecimal;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContractReviewRunCreateCoordinatorTest {
    private final UUID projectId = UUID.randomUUID(), contractId = UUID.randomUUID(), fileId = UUID.randomUUID();
    private final UUID candidateId = UUID.randomUUID();
    private final UUID parseId = UUID.randomUUID(), roleDecisionId = UUID.randomUUID();
    private final UUID identityDecisionId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-09-15T00:00:00Z");
    private final CurrentUser actor = new CurrentUser(UUID.randomUUID().toString(), "reviewer", "Reviewer",
            Set.of("AUDITOR"));
    private final ReviewRunReadinessResolver readiness = mock(ReviewRunReadinessResolver.class);
    private final InMemoryContractReviewRunRepositories store = new InMemoryContractReviewRunRepositories();
    private final InMemoryIdempotencyAdapter idempotency = new InMemoryIdempotencyAdapter();
    private final OperationAuditPort audit = mock(OperationAuditPort.class);
    private final ContractReviewRunCreateTransactionCoordinator coordinator =
            new ContractReviewRunCreateTransactionCoordinator(readiness, new ReviewRunSnapshotIdentityFactory(),
                    new ReviewRunManifestFactory(), store, store, store, store, idempotency, audit,
                    Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void createsPendingRunAndDurablyReplaysSameKeyWithoutSecondManifestOrAudit() {
        ready();
        String key = "run-create-" + UUID.randomUUID();
        var created = coordinator.create(command(key, 0, List.of(fileId)), actor);
        assertThat(created.replayed()).isFalse();
        assertThat(created.run().status()).isEqualTo(ContractReviewRunStatus.PENDING);
        assertThat(created.materials()).hasSize(1);
        assertThat(created.steps()).hasSize(5);
        assertThat(created.attempts()).hasSize(4);
        assertThat(created.run().inputSnapshotIdentity()).matches("[0-9a-f]{64}");
        var replay = coordinator.create(command(key, 0, List.of(fileId)), actor);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.run().runId()).isEqualTo(created.run().runId());
        assertThat(replay.steps()).containsExactlyElementsOf(created.steps());
        assertThat(replay.attempts()).containsExactlyElementsOf(created.attempts());
        assertThat(idempotency.size()).isEqualTo(1);
        verify(audit, times(1)).append(any());
        verify(readiness, times(1)).resolve(any(), eq(projectId), eq(contractId), eq(0L),
                eq(List.of(fileId)), eq(actor.userId()), eq(now));
    }

    @Test
    void completedSameKeyReplaysFrozenRunEvenWhenCurrentReadinessIsUnavailable() {
        ready();
        String key = "lost-response-" + UUID.randomUUID();
        var created = coordinator.create(command(key, 0, List.of(fileId)), actor);
        when(readiness.resolve(any(), eq(projectId), eq(contractId), eq(0L), eq(List.of(fileId)),
                eq(actor.userId()), eq(now))).thenThrow(new BusinessException(
                        BusinessErrorCode.CONFLICT, "current material changed"));
        var replay = coordinator.create(command(key, 0, List.of(fileId)), actor);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.run().runId()).isEqualTo(created.run().runId());
        assertThat(replay.materials()).containsExactlyElementsOf(created.materials());
        assertThat(replay.steps()).containsExactlyElementsOf(created.steps());
        assertThat(replay.attempts()).containsExactlyElementsOf(created.attempts());
        assertThat(idempotency.size()).isEqualTo(1);
        verify(audit, times(1)).append(any());
        verify(readiness, times(1)).resolve(any(), eq(projectId), eq(contractId), eq(0L),
                eq(List.of(fileId)), eq(actor.userId()), eq(now));
    }

    @Test
    void sameKeyChangedRequestedVersionOrMaterialSetIsConflictNotNewRun() {
        ready();
        String key = "run-conflict-" + UUID.randomUUID();
        UUID original = coordinator.create(command(key, 0, List.of(fileId)), actor).run().runId();
        conflict(command(key, 1, List.of(fileId)));
        conflict(command(key, 0, List.of(fileId, UUID.randomUUID())));
        conflict(new CreateContractReviewRunCommand(UUID.randomUUID(), contractId, 0,
                List.of(fileId), key, command(key, 0, List.of(fileId)).metadata()));
        conflict(new CreateContractReviewRunCommand(projectId, UUID.randomUUID(), 0,
                List.of(fileId), key, command(key, 0, List.of(fileId)).metadata()));
        assertThat(store.findById(original)).isPresent();
        assertThat(idempotency.size()).isEqualTo(1);
        verify(audit, times(1)).append(any());
    }

    @Test
    void sameKeyDifferentActorConflictsWithoutCreatingAnotherRun() {
        ready();
        String key = "changed-actor-" + UUID.randomUUID();
        UUID original = coordinator.create(command(key, 0, List.of(fileId)), actor).run().runId();
        CurrentUser other = new CurrentUser(UUID.randomUUID().toString(), "other", "Other", Set.of("AUDITOR"));
        assertThatThrownBy(() -> coordinator.create(command(key, 0, List.of(fileId)), other))
                .satisfies(error -> assertClassified(error, BusinessErrorCode.CONFLICT,
                        "INTEGRITY_CONFLICT"));
        assertThat(store.findById(original)).isPresent();
        assertThat(idempotency.size()).isEqualTo(1);
    }

    @Test
    void readinessFailureLeavesNoPartialRunOrIdempotencyCommand() {
        when(readiness.resolve(any(), eq(projectId), eq(contractId), anyLong(), any(), eq(actor.userId()), eq(now)))
                .thenThrow(new BusinessException(BusinessErrorCode.CONFLICT, "material identity conflict"));
        assertThatThrownBy(() -> coordinator.create(
                command("readiness-fail-" + UUID.randomUUID(), 0, List.of(fileId)), actor))
                .satisfies(error -> assertClassified(error, BusinessErrorCode.CONFLICT,
                        "READINESS_REJECTED"));
        assertThat(idempotency.size()).isZero();
        verify(audit, times(0)).append(any());
    }

    @Test
    void partialManifestInsertFailureCompensatesOnlyUnpublishedLocalRunAndClaim() {
        ready();
        ContractReviewStepRepository brokenSteps = mock(ContractReviewStepRepository.class);
        doThrow(new IllegalStateException("synthetic step insert failure"))
                .when(brokenSteps).insert(any(ContractReviewStep.class));
        var failing = new ContractReviewRunCreateTransactionCoordinator(readiness,
                new ReviewRunSnapshotIdentityFactory(), new ReviewRunManifestFactory(),
                store, store, brokenSteps, store, idempotency, audit, Clock.fixed(now, ZoneOffset.UTC));
        assertThatThrownBy(() -> failing.create(command("run-partial-" + UUID.randomUUID(), 0,
                List.of(fileId)), actor)).isInstanceOf(IllegalStateException.class);
        ArgumentCaptor<UUID> runId = ArgumentCaptor.forClass(UUID.class);
        verify(readiness).resolve(runId.capture(), eq(projectId), eq(contractId), eq(0L),
                eq(List.of(fileId)), eq(actor.userId()), eq(now));
        assertThat(store.findById(runId.getValue())).isEmpty();
        assertThat(store.findMaterialsByRunId(runId.getValue())).isEmpty();
        assertThat(store.findStepsByRunId(runId.getValue())).isEmpty();
        assertThat(store.findAttemptsByRunId(runId.getValue())).isEmpty();
        assertThat(idempotency.size()).isZero();
        verify(audit, times(0)).append(any());
    }

    @Test
    void completedSameKeyReplaysAfterCurrentContractHashChanges() {
        ready();
        String key = "changed-contract-" + UUID.randomUUID();
        UUID original = coordinator.create(command(key, 0, List.of(fileId)), actor).run().runId();
        when(readiness.resolve(any(), eq(projectId), eq(contractId), eq(0L), eq(List.of(fileId)),
                eq(actor.userId()), eq(now))).thenAnswer(call -> readyFor(call.getArgument(0),
                        new BigDecimal("101"), parseId, roleDecisionId, identityDecisionId));
        var replay = coordinator.create(command(key, 0, List.of(fileId)), actor);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.run().runId()).isEqualTo(original);
        assertThat(store.findById(original)).isPresent();
        assertThat(idempotency.size()).isEqualTo(1);
    }

    @Test
    void completedSameKeyReplaysAfterCurrentParseRoleOrIdentityChanges() {
        ready();
        String key = "changed-material-" + UUID.randomUUID();
        UUID original = coordinator.create(command(key, 0, List.of(fileId)), actor).run().runId();
        when(readiness.resolve(any(), eq(projectId), eq(contractId), eq(0L), eq(List.of(fileId)),
                eq(actor.userId()), eq(now))).thenAnswer(call -> readyFor(call.getArgument(0),
                        new BigDecimal("100"), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        var replay = coordinator.create(command(key, 0, List.of(fileId)), actor);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.run().runId()).isEqualTo(original);
        assertThat(store.findById(original)).isPresent();
        assertThat(idempotency.size()).isEqualTo(1);
    }

    @Test
    void corruptedFrozenMaterialCannotBeBlindlyReplayed() throws Exception {
        ready();
        String key = "corrupt-frozen-" + UUID.randomUUID();
        var created = coordinator.create(command(key, 0, List.of(fileId)), actor);
        var original = created.materials().get(0);
        Field field = InMemoryContractReviewRunRepositories.class.getDeclaredField("materials");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<UUID, ContractReviewRunMaterial> rows =
                (Map<UUID, ContractReviewRunMaterial>) field.get(store);
        rows.put(original.runMaterialId(), new ContractReviewRunMaterial(original.runMaterialId(),
                original.runId(), original.ordinal(), original.projectId(), original.fileId(),
                original.fileSha256(), original.parseResultId(), original.parseVersion(),
                "c".repeat(64), original.effectiveRole(), original.roleDecisionId(),
                original.identityDecisionId(), original.sourceRoleDispositionId(),
                original.selectedBy(), original.snapshottedAt()));
        conflict(command(key, 0, List.of(fileId)));
        assertThat(idempotency.size()).isEqualTo(1);
    }

    @Test
    void loserWithEarlierEmptyLookupReplaysWinnerAfterReadinessFails() {
        ready();
        String key = "winner-" + UUID.randomUUID();
        var winner = coordinator.create(command(key, 0, List.of(fileId)), actor);
        IdempotencyPort racingLookup = mock(IdempotencyPort.class);
        var winnerRecord = idempotency.findByScopeAndKey(
                ContractReviewRunCreateTransactionCoordinator.SCOPE, key).orElseThrow();
        when(racingLookup.findByScopeAndKey(ContractReviewRunCreateTransactionCoordinator.SCOPE, key))
                .thenReturn(Optional.empty(), Optional.of(winnerRecord));
        when(readiness.resolve(any(), eq(projectId), eq(contractId), eq(0L), eq(List.of(fileId)),
                eq(actor.userId()), eq(now))).thenThrow(new BusinessException(
                        BusinessErrorCode.CONFLICT, "material became unavailable"));
        var loser = new ContractReviewRunCreateTransactionCoordinator(readiness,
                new ReviewRunSnapshotIdentityFactory(), new ReviewRunManifestFactory(),
                store, store, store, store, racingLookup, audit, Clock.fixed(now, ZoneOffset.UTC));
        var replay = loser.create(command(key, 0, List.of(fileId)), actor);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.run().runId()).isEqualTo(winner.run().runId());
        verify(audit, times(1)).append(any());
    }

    @Test
    void readinessFailureWithInProgressWinnerRemainsOutcomeUncertain() {
        String key = "in-progress-winner-" + UUID.randomUUID();
        IdempotencyPort racingLookup = mock(IdempotencyPort.class);
        var winner = record(key, IdempotencyStatus.IN_PROGRESS);
        when(racingLookup.findByScopeAndKey(ContractReviewRunCreateTransactionCoordinator.SCOPE, key))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(readiness.resolve(any(), eq(projectId), eq(contractId), eq(0L), eq(List.of(fileId)),
                eq(actor.userId()), eq(now))).thenThrow(new BusinessException(
                        BusinessErrorCode.CONFLICT, "contract version or status is not ready"));
        var racing = coordinator(racingLookup);

        assertThatThrownBy(() -> racing.create(command(key, 0, List.of(fileId)), actor))
                .satisfies(error -> assertClassified(error, BusinessErrorCode.CONFLICT,
                        "OUTCOME_UNCERTAIN"));
        verify(audit, times(0)).append(any());
    }

    @Test
    void readinessFailureWithFailedWinnerIsIntegrityConflict() {
        String key = "failed-winner-" + UUID.randomUUID();
        IdempotencyPort racingLookup = mock(IdempotencyPort.class);
        var winner = record(key, IdempotencyStatus.FAILED);
        when(racingLookup.findByScopeAndKey(ContractReviewRunCreateTransactionCoordinator.SCOPE, key))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(readiness.resolve(any(), eq(projectId), eq(contractId), eq(0L), eq(List.of(fileId)),
                eq(actor.userId()), eq(now))).thenThrow(new BusinessException(
                        BusinessErrorCode.CONFLICT, "contract version or status is not ready"));

        assertThatThrownBy(() -> coordinator(racingLookup).create(command(key, 0, List.of(fileId)), actor))
                .satisfies(error -> assertClassified(error, BusinessErrorCode.CONFLICT,
                        "INTEGRITY_CONFLICT"));
        verify(audit, times(0)).append(any());
    }

    @Test
    void readinessFailureWithoutWinnerKeepsOriginalStatusAndIsRecoverable() {
        for (BusinessErrorCode errorCode : List.of(BusinessErrorCode.CONFLICT, BusinessErrorCode.NOT_FOUND)) {
            String key = "no-winner-" + errorCode + "-" + UUID.randomUUID();
            IdempotencyPort emptyLookup = mock(IdempotencyPort.class);
            when(emptyLookup.findByScopeAndKey(ContractReviewRunCreateTransactionCoordinator.SCOPE, key))
                    .thenReturn(Optional.empty());
            when(readiness.resolve(any(), eq(projectId), eq(contractId), eq(0L), eq(List.of(fileId)),
                    eq(actor.userId()), eq(now))).thenThrow(new BusinessException(errorCode, "deterministic rejection"));

            assertThatThrownBy(() -> coordinator(emptyLookup).create(command(key, 0, List.of(fileId)), actor))
                    .satisfies(error -> assertClassified(error, errorCode, "READINESS_REJECTED"));
        }
        verify(audit, times(0)).append(any());
    }

    @Test
    void replayIdentityMismatchIsIntegrityConflictAndNeverCreatesAnotherRun() {
        ready();
        String key = "integrity-mismatch-" + UUID.randomUUID();
        UUID original = coordinator.create(command(key, 0, List.of(fileId)), actor).run().runId();
        CurrentUser other = new CurrentUser(UUID.randomUUID().toString(), "other", "Other", Set.of("AUDITOR"));

        assertThatThrownBy(() -> coordinator.create(command(key, 0, List.of(fileId)), other))
                .satisfies(error -> assertClassified(error, BusinessErrorCode.CONFLICT,
                        "INTEGRITY_CONFLICT"));
        assertThat(store.findById(original)).isPresent();
        assertThat(idempotency.size()).isEqualTo(1);
        verify(audit, times(1)).append(any());
    }

    @Test
    void replayFingerprintMismatchIsIntegrityConflictAndNeverCreatesAnotherRun() {
        ready();
        String key = "fingerprint-mismatch-" + UUID.randomUUID();
        UUID original = coordinator.create(command(key, 0, List.of(fileId)), actor).run().runId();
        IdempotencyRecord stored = idempotency.findByScopeAndKey(
                ContractReviewRunCreateTransactionCoordinator.SCOPE, key).orElseThrow();
        IdempotencyRecord corrupted = new IdempotencyRecord(stored.id(), stored.scope(),
                stored.idempotencyKey(), stored.actorId(), stored.resourceType(), stored.resourceId(),
                "0".repeat(64), stored.status(), stored.resultResourceType(), stored.resultResourceId(),
                stored.firstRequestId(), stored.traceId(), stored.createdAt(), stored.completedAt(),
                stored.versionNo());
        IdempotencyPort corruptedLookup = mock(IdempotencyPort.class);
        when(corruptedLookup.findByScopeAndKey(ContractReviewRunCreateTransactionCoordinator.SCOPE, key))
                .thenReturn(Optional.of(corrupted));

        assertThatThrownBy(() -> coordinator(corruptedLookup).create(
                command(key, 0, List.of(fileId)), actor))
                .satisfies(error -> assertClassified(error, BusinessErrorCode.CONFLICT,
                        "INTEGRITY_CONFLICT"));
        assertThat(store.findById(original)).isPresent();
        assertThat(idempotency.size()).isEqualTo(1);
        verify(audit, times(1)).append(any());
    }

    private void conflict(CreateContractReviewRunCommand command) {
        assertThatThrownBy(() -> coordinator.create(command, actor))
                .satisfies(error -> assertClassified(error, BusinessErrorCode.CONFLICT,
                        "INTEGRITY_CONFLICT"));
    }

    private ContractReviewRunCreateTransactionCoordinator coordinator(IdempotencyPort idempotencyPort) {
        return new ContractReviewRunCreateTransactionCoordinator(readiness,
                new ReviewRunSnapshotIdentityFactory(), new ReviewRunManifestFactory(),
                store, store, store, store, idempotencyPort, audit, Clock.fixed(now, ZoneOffset.UTC));
    }

    private IdempotencyRecord record(String key, IdempotencyStatus status) {
        return new IdempotencyRecord(UUID.randomUUID(), ContractReviewRunCreateTransactionCoordinator.SCOPE,
                key, actor.userId(), "CONTRACT", contractId.toString(), "unproven-fingerprint", status,
                "CONTRACT_REVIEW_RUN", UUID.randomUUID().toString(), "request-test", "trace-test",
                now, status == IdempotencyStatus.IN_PROGRESS ? null : now, 0);
    }

    private static void assertClassified(Throwable error, BusinessErrorCode errorCode,
            String classification) {
        assertThat(error).isInstanceOf(ContractReviewRunCreateException.class);
        var classified = (ContractReviewRunCreateException) error;
        assertThat(classified.errorCode()).isEqualTo(errorCode);
        assertThat(classified.classification().name()).isEqualTo(classification);
    }

    private CreateContractReviewRunCommand command(String key, long version, List<UUID> files) {
        return new CreateContractReviewRunCommand(projectId, contractId, version, files, key,
                new ProjectRequestMetadata("request-test", "trace-test", null));
    }

    private void ready() {
        when(readiness.resolve(any(), eq(projectId), eq(contractId), anyLong(), any(),
                eq(actor.userId()), eq(now))).thenAnswer(call -> {
            long version = call.getArgument(3);
            List<UUID> selected = call.getArgument(4);
            if (version != 0 || !selected.equals(List.of(fileId)))
                throw new BusinessException(BusinessErrorCode.CONFLICT, "changed request is not ready");
            return readyFor(call.getArgument(0), new BigDecimal("100.00"),
                    parseId, roleDecisionId, identityDecisionId);
        });
    }

    private ReviewRunReadinessResolver.Ready readyFor(UUID runId, BigDecimal amount,
            UUID parse, UUID role, UUID identity) {
        Contract contract = new Contract(contractId, "C-1", "Synthetic Contract", "GOODS", "A", "B",
                null, Money.of(amount), null, "READY_FOR_REVIEW", 0, now, now, List.of(), null);
        var snapshot = new ContractSnapshotCanonicalizer().canonicalize(contract);
        var material = new ContractReviewRunMaterial(UUID.randomUUID(), runId, 1, projectId, fileId,
                "a".repeat(64), parse, 1, "b".repeat(64), DocumentRole.CONTRACT_BODY,
                role, identity, null, actor.userId(), now);
        return new ReviewRunReadinessResolver.Ready(contract, candidateId, null, snapshot, List.of(material));
    }
}
