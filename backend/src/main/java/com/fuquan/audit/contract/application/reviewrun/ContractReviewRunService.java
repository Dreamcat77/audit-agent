package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewRunRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewRunMaterialRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewStepRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewStepAttemptRepository;
import com.fuquan.audit.security.application.AuthContext;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContractReviewRunService {
    private final AuthContext auth;
    private final ContractReviewRunCreateTransactionCoordinator creates;
    private final ContractReviewRunRepository runs;
    private final ContractReviewRunMaterialRepository materials;
    private final ContractReviewStepRepository steps;
    private final ContractReviewStepAttemptRepository attempts;
    private final ReviewRunSnapshotIdentityFactory identities;

    public ContractReviewRunService(AuthContext auth, ContractReviewRunCreateTransactionCoordinator creates,
            ContractReviewRunRepository runs, ContractReviewRunMaterialRepository materials,
            ContractReviewStepRepository steps, ContractReviewStepAttemptRepository attempts,
            ReviewRunSnapshotIdentityFactory identities) {
        this.auth = auth; this.creates = creates; this.runs = runs; this.materials = materials;
        this.steps = steps; this.attempts = attempts; this.identities = identities;
    }

    public synchronized ContractReviewRunCreateResult create(CreateContractReviewRunCommand command) {
        var actor = auth.currentUser().orElseThrow(() ->
                new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
        return creates.create(command, actor);
    }

    /** Reopens frozen DM8 rows only; no current Project/File/Contract reread and no command side effects. */
    @Transactional(readOnly = true)
    public ContractReviewRunCreateResult get(UUID projectId, UUID contractId, UUID runId) {
        var run = runs.findById(runId).filter(value -> value.projectId().equals(projectId)
                && value.contractId().equals(contractId)).orElseThrow(() ->
                        new BusinessException(BusinessErrorCode.NOT_FOUND, "review run not found"));
        var selected = materials.findMaterialsByRunId(runId);
        var manifestSteps = steps.findStepsByRunId(runId);
        var manifestAttempts = attempts.findAttemptsByRunId(runId);
        if (selected.isEmpty() || !ReviewRunManifestVerifier.complete(runId, manifestSteps, manifestAttempts)
                || selected.stream().anyMatch(value -> !value.runId().equals(runId)
                        || !value.projectId().equals(projectId))
                || !ContractSnapshotCanonicalizer.sha256(run.contractSnapshotCanonicalJson())
                        .equals(run.contractSnapshotSha256())
                || !identities.identity(run.runSchemaVersion(), run.projectId(), run.contractId(), run.contractVersion(),
                        run.contractSnapshotSha256(), run.sourceCandidateId(), run.sourceConfirmationId(), selected,
                        run.reviewProfileVersion(), run.ruleVersion(), run.modelPolicyVersion())
                        .equals(run.inputSnapshotIdentity())) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "review run frozen manifest cannot be verified");
        }
        return new ContractReviewRunCreateResult(run, selected, manifestSteps, manifestAttempts, false);
    }
}
