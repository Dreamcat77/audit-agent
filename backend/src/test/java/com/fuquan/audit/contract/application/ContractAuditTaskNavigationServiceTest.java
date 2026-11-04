package com.fuquan.audit.contract.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fuquan.audit.common.application.port.IdempotencyPort;
import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.contract.application.reviewrun.ContractReviewRunCreateResult;
import com.fuquan.audit.contract.application.reviewrun.ContractSnapshotDecoder;
import com.fuquan.audit.contract.application.reviewrun.ReviewStepOutputReadService;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewRunRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewStepRepository;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.review.domain.AuditTask;
import com.fuquan.audit.review.domain.AuditTaskStatus;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContractAuditTaskNavigationServiceTest {
    private static final UUID PROJECT = UUID.fromString("71000000-0000-0000-0000-000000000001");
    private static final UUID CONTRACT = UUID.fromString("71000000-0000-0000-0000-000000000002");
    private static final UUID TASK = UUID.fromString("71000000-0000-0000-0000-000000000003");
    private static final UUID RUN = UUID.fromString("71000000-0000-0000-0000-000000000004");
    private static final UUID STEP = UUID.fromString("71000000-0000-0000-0000-000000000005");
    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");
    private final AuthContext auth = mock(AuthContext.class);
    private final IdempotencyPort operations = mock(IdempotencyPort.class);
    private final ContractReviewStepRepository steps = mock(ContractReviewStepRepository.class);
    private final ContractReviewRunRepository runs = mock(ContractReviewRunRepository.class);
    private final ReviewStepOutputReadService outputs = mock(ReviewStepOutputReadService.class);
    private final ContractSnapshotDecoder snapshots = mock(ContractSnapshotDecoder.class);
    private final ContractAuditTaskNavigationService service = new ContractAuditTaskNavigationService(
            auth, operations, steps, runs, outputs, snapshots);

    @Test
    void completedF02TaskOpensItsExactHistoricalRunEvenWhenAnotherRunExists() {
        when(auth.currentUser()).thenReturn(Optional.of(actor("auditor")));
        when(operations.findAllByResultResource("AUDIT_TASK", TASK.toString()))
                .thenReturn(List.of(enclosingOperation(), operation("auditor")));
        ContractReviewStep step = step();
        when(steps.findByOutputResource("AUDIT_TASK", TASK)).thenReturn(List.of(step));
        ContractReviewRun run = run("auditor", ContractReviewRunStatus.FAILED);
        when(runs.findById(RUN)).thenReturn(Optional.of(run));
        when(outputs.getRun(PROJECT, CONTRACT, RUN)).thenReturn(new ReviewStepOutputReadService.RunOutput(
                new ContractReviewRunCreateResult(run, List.of(), List.of(step), List.of(), false),
                List.of(new ReviewStepOutputReadService.StepOutput(
                        run, step, List.of(), task(), List.of(), List.of(), List.of()))));
        when(snapshots.decodeAndVerify(run)).thenReturn(mock(Contract.class));

        ContractAuditTaskNavigation link = service.resolve(task(), PROJECT);

        assertThat(link.kind()).isEqualTo(ContractAuditTaskNavigation.Kind.INTEGRATED);
        assertThat(link.runId()).isEqualTo(RUN);
        assertThat(link.status()).isEqualTo(ContractReviewRunStatus.FAILED);
    }

    @Test
    void missingStepForInternalReviewRunOperationFailsClosed() {
        when(auth.currentUser()).thenReturn(Optional.of(actor("auditor")));
        when(operations.findAllByResultResource("AUDIT_TASK", TASK.toString()))
                .thenReturn(List.of(enclosingOperation(), operation("auditor")));
        when(steps.findByOutputResource("AUDIT_TASK", TASK)).thenReturn(List.of());

        assertThat(service.resolve(task(), PROJECT).kind())
                .isEqualTo(ContractAuditTaskNavigation.Kind.UNAVAILABLE);
    }

    @Test
    void anotherActorOrProjectCannotReceiveHistoricalRunIdentity() {
        when(auth.currentUser()).thenReturn(Optional.of(actor("different-user")));
        when(operations.findAllByResultResource("AUDIT_TASK", TASK.toString()))
                .thenReturn(List.of(enclosingOperation(), operation("auditor")));
        when(steps.findByOutputResource("AUDIT_TASK", TASK)).thenReturn(List.of(step()));
        when(runs.findById(RUN)).thenReturn(Optional.of(run("auditor", ContractReviewRunStatus.COMPLETED)));
        assertThat(service.resolve(task(), PROJECT).runId()).isNull();

        when(auth.currentUser()).thenReturn(Optional.of(actor("auditor")));
        assertThat(service.resolve(task(), UUID.randomUUID()).kind())
                .isEqualTo(ContractAuditTaskNavigation.Kind.UNAVAILABLE);
    }

    @Test
    void genuineIndependentF02TaskRetainsAQuantitativeOnlyEntry() {
        when(auth.currentUser()).thenReturn(Optional.of(actor("auditor")));
        when(operations.findAllByResultResource("AUDIT_TASK", TASK.toString()))
                .thenReturn(List.of(independentOperation()));
        when(steps.findByOutputResource("AUDIT_TASK", TASK)).thenReturn(List.of());
        assertThat(service.resolve(task(), PROJECT).kind())
                .isEqualTo(ContractAuditTaskNavigation.Kind.INDEPENDENT_F02);
    }

    @Test
    void taskWithoutDurableIndependentOriginFailsClosed() {
        when(auth.currentUser()).thenReturn(Optional.of(actor("auditor")));
        when(operations.findAllByResultResource("AUDIT_TASK", TASK.toString()))
                .thenReturn(List.of());
        when(steps.findByOutputResource("AUDIT_TASK", TASK)).thenReturn(List.of());

        assertThat(service.resolve(task(), PROJECT).kind())
                .isEqualTo(ContractAuditTaskNavigation.Kind.UNAVAILABLE);
    }

    @Test
    void integratedTaskStillProcessingCannotOpenIndependentF02() {
        when(auth.currentUser()).thenReturn(Optional.of(actor("auditor")));
        when(operations.findAllByResultResource("AUDIT_TASK", TASK.toString()))
                .thenReturn(List.of(operation("auditor")));
        when(steps.findByOutputResource("AUDIT_TASK", TASK)).thenReturn(List.of());
        AuditTask processing = new AuditTask(TASK, "CONTRACT", CONTRACT, 1,
                ContractReviewService.REVIEW_TYPE, AuditTaskStatus.PROCESSING,
                "auditor", "request", "trace", NOW, NOW, null, 1);

        assertThat(service.resolve(processing, PROJECT).kind())
                .isEqualTo(ContractAuditTaskNavigation.Kind.UNAVAILABLE);
    }

    private static CurrentUser actor(String id) {
        return new CurrentUser(id, id, id, Set.of("AUDITOR"));
    }

    private static AuditTask task() {
        return new AuditTask(TASK, "CONTRACT", CONTRACT, 1, ContractReviewService.REVIEW_TYPE,
                AuditTaskStatus.DONE, "auditor", "request", "trace", NOW, NOW, NOW, 2);
    }

    private static IdempotencyRecord operation(String actor) {
        return new IdempotencyRecord(UUID.randomUUID(), ContractReviewService.IDEMPOTENCY_SCOPE,
                "reviewrun-f02:" + RUN + ":" + STEP + ":" + UUID.randomUUID(), actor, "CONTRACT",
                CONTRACT.toString(), "fingerprint", IdempotencyStatus.SUCCEEDED, "AUDIT_TASK",
                TASK.toString(), "request", "trace", NOW, NOW, 1);
    }

    private static IdempotencyRecord enclosingOperation() {
        return new IdempotencyRecord(UUID.randomUUID(), "CONTRACT_REVIEW_STEP_EXECUTION",
                "step-" + STEP, "auditor", "CONTRACT_REVIEW_STEP", STEP.toString(),
                "fingerprint", IdempotencyStatus.SUCCEEDED, "AUDIT_TASK", TASK.toString(),
                "request", "trace", NOW, NOW, 1);
    }

    private static IdempotencyRecord independentOperation() {
        return new IdempotencyRecord(UUID.randomUUID(), ContractReviewService.IDEMPOTENCY_SCOPE,
                "direct-f02-" + TASK, "auditor", "CONTRACT", CONTRACT.toString(),
                "fingerprint", IdempotencyStatus.SUCCEEDED, "AUDIT_TASK", TASK.toString(),
                "request", "trace", NOW, NOW, 1);
    }

    private static ContractReviewStep step() {
        return new ContractReviewStep(STEP, RUN, ContractReviewStepType.F02_DETERMINISTIC, true,
                ContractReviewStepStatus.COMPLETED, "f02-v1", 1, "AUDIT_TASK", TASK,
                null, NOW, NOW, 2);
    }

    private static ContractReviewRun run(String actor, ContractReviewRunStatus status) {
        return new ContractReviewRun(RUN, PROJECT, CONTRACT, 1, null, null, 1,
                "{}", "a".repeat(64), "profile", "rule", "model", "b".repeat(64), actor,
                status, NOW, NOW, status == ContractReviewRunStatus.COMPLETED ? NOW : null,
                status == ContractReviewRunStatus.FAILED ? NOW : null, null, null, 2);
    }
}
