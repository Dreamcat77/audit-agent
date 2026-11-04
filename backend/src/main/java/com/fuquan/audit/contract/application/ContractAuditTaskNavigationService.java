package com.fuquan.audit.contract.application;

import com.fuquan.audit.common.application.port.IdempotencyPort;
import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.reviewrun.ReviewStepOutputReadService;
import com.fuquan.audit.contract.application.reviewrun.ContractSnapshotDecoder;
import com.fuquan.audit.contract.application.reviewrun.executor.FrozenF02ResultMapper;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewRunRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewStepRepository;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.review.domain.AuditTask;
import com.fuquan.audit.review.domain.AuditTaskStatus;
import com.fuquan.audit.security.application.AuthContext;
import java.util.List;
import java.util.stream.Stream;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves a task to an exact run using durable F02 output and operation ownership. */
@Service
public class ContractAuditTaskNavigationService {
    private final AuthContext auth;
    private final IdempotencyPort operations;
    private final ContractReviewStepRepository steps;
    private final ContractReviewRunRepository runs;
    private final ReviewStepOutputReadService outputs;
    private final ContractSnapshotDecoder snapshots;

    public ContractAuditTaskNavigationService(AuthContext auth, IdempotencyPort operations,
            ContractReviewStepRepository steps, ContractReviewRunRepository runs,
            ReviewStepOutputReadService outputs, ContractSnapshotDecoder snapshots) {
        this.auth = auth;
        this.operations = operations;
        this.steps = steps;
        this.runs = runs;
        this.outputs = outputs;
        this.snapshots = snapshots;
    }

    public ContractAuditTaskNavigation resolve(AuditTask task, UUID associatedProjectId) {
        return resolveTask(task, associatedProjectId).navigation();
    }

    public record ResolvedTask(ContractAuditTaskNavigation navigation, Contract frozenContract) {
        public ResolvedTask {
            if ((navigation.kind() == ContractAuditTaskNavigation.Kind.INTEGRATED)
                    != (frozenContract != null)) {
                throw new IllegalArgumentException("integrated navigation requires frozen Contract");
            }
        }
    }

    private static ResolvedTask unavailable() {
        return new ResolvedTask(ContractAuditTaskNavigation.unavailable(), null);
    }

    @Transactional(readOnly = true)
    public ResolvedTask resolveTask(AuditTask task, UUID associatedProjectId) {
        String actorId = auth.currentUser().orElseThrow(() ->
                new BusinessException(BusinessErrorCode.UNAUTHENTICATED)).userId();
        List<IdempotencyRecord> resourceOperations = operations
                .findAllByResultResource("AUDIT_TASK", task.id().toString());
        List<IdempotencyRecord> internalOperations = resourceOperations.stream()
                .filter(value -> ContractReviewService.IDEMPOTENCY_SCOPE.equals(value.scope())
                        && value.idempotencyKey().startsWith(
                                ContractReviewService.REVIEW_RUN_INTERNAL_KEY_PREFIX))
                .toList();
        List<ContractReviewStep> matches = Stream.concat(
                steps.findByOutputResource(FrozenF02ResultMapper.OUTPUT_RESOURCE_TYPE, task.id()).stream(),
                steps.findByOutputResource("AUDIT_TASK", task.id()).stream()).toList();
        if (matches.isEmpty()) {
            List<IdempotencyRecord> directOperations = resourceOperations.stream().filter(value ->
                    ContractReviewService.IDEMPOTENCY_SCOPE.equals(value.scope())
                            && !value.idempotencyKey().startsWith(
                                    ContractReviewService.REVIEW_RUN_INTERNAL_KEY_PREFIX)
                            && value.status() == IdempotencyStatus.SUCCEEDED
                            && "CONTRACT".equals(value.resourceType())
                            && task.sourceId().toString().equals(value.resourceId())
                            && "AUDIT_TASK".equals(value.resultResourceType())
                            && task.id().toString().equals(value.resultResourceId())
                            && actorId.equals(value.actorId())
                            && actorId.equals(task.createdBy())).toList();
            return internalOperations.isEmpty() && directOperations.size() == 1
                    && resourceOperations.size() == 1 && task.status() == AuditTaskStatus.DONE
                    ? new ResolvedTask(ContractAuditTaskNavigation.independent(), null) : unavailable();
        }
        if (matches.size() != 1 || internalOperations.size() != 1 || associatedProjectId == null
                || task.status() != AuditTaskStatus.DONE) return unavailable();
        IdempotencyRecord operation = internalOperations.get(0);
        ContractReviewStep step = matches.get(0);
        if (step.stepType() != ContractReviewStepType.F02_DETERMINISTIC
                || step.status() != ContractReviewStepStatus.COMPLETED
                || !(FrozenF02ResultMapper.OUTPUT_RESOURCE_TYPE.equals(step.outputResourceType())
                        || "AUDIT_TASK".equals(step.outputResourceType()))
                || !task.id().equals(step.outputResourceId())
                || operation.status() != IdempotencyStatus.SUCCEEDED
                || !"AUDIT_TASK".equals(operation.resultResourceType())
                || !task.id().toString().equals(operation.resultResourceId())
                || !"CONTRACT".equals(operation.resourceType())
                || !task.sourceId().toString().equals(operation.resourceId())
                || !actorId.equals(operation.actorId())
                || !actorId.equals(task.createdBy())
                || !operation.idempotencyKey().startsWith(
                        ContractReviewService.REVIEW_RUN_INTERNAL_KEY_PREFIX
                                + step.runId() + ":" + step.stepId() + ":")) {
            return unavailable();
        }
        var run = runs.findById(step.runId()).orElse(null);
        if (run == null || !run.projectId().equals(associatedProjectId)
                || !run.contractId().equals(task.sourceId())
                || run.contractVersion() != task.sourceVersion()
                || !run.requestedBy().equals(actorId)) return unavailable();
        try {
            var output = outputs.getRun(run.projectId(), run.contractId(), run.runId());
            if (!output.manifest().run().equals(run)
                    || output.steps().stream().filter(value ->
                            value.step().stepId().equals(step.stepId())
                                    && value.step().equals(step)
                                    && value.auditTask() != null
                                    && value.auditTask().equals(task)).count() != 1) {
                return unavailable();
            }
            Contract frozen = snapshots.decodeAndVerify(run);
            if (frozen == null) return unavailable();
            return new ResolvedTask(ContractAuditTaskNavigation.integrated(
                    run.projectId(), run.runId(), run.status()), frozen);
        } catch (BusinessException invalidDurableOutput) {
            return unavailable();
        }
    }
}
