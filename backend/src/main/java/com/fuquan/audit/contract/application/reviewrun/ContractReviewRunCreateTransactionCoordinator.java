package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.common.application.port.IdempotencyClaim;
import com.fuquan.audit.common.application.port.IdempotencyPort;
import com.fuquan.audit.common.application.port.IdempotencyRecord;
import com.fuquan.audit.common.application.port.IdempotencyRequest;
import com.fuquan.audit.common.application.port.IdempotencyStatus;
import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.common.application.port.OperationAuditResultStatus;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewRunRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewRunMaterialRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewStepRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewStepAttemptRepository;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunMaterial;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.security.domain.CurrentUser;
import static com.fuquan.audit.contract.application.reviewrun.ContractReviewRunCreateException.Classification.INTEGRITY_CONFLICT;
import static com.fuquan.audit.contract.application.reviewrun.ContractReviewRunCreateException.Classification.OUTCOME_UNCERTAIN;
import static com.fuquan.audit.contract.application.reviewrun.ContractReviewRunCreateException.Classification.READINESS_REJECTED;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** One short DM8 transaction claims and commits the entire immutable Phase 2A manifest. */
@Component
public class ContractReviewRunCreateTransactionCoordinator {
    public static final String SCOPE = "REVIEW_RUN_CREATE";
    private final ReviewRunReadinessResolver readiness;
    private final ReviewRunSnapshotIdentityFactory identities;
    private final ReviewRunManifestFactory manifests;
    private final ContractReviewRunRepository runs;
    private final ContractReviewRunMaterialRepository materials;
    private final ContractReviewStepRepository steps;
    private final ContractReviewStepAttemptRepository attempts;
    private final IdempotencyPort idempotency;
    private final OperationAuditPort audit;
    private final Clock clock;
    private final ContractReviewExecutionPolicyResolver policies;

    public ContractReviewRunCreateTransactionCoordinator(ReviewRunReadinessResolver readiness,
            ReviewRunSnapshotIdentityFactory identities, ReviewRunManifestFactory manifests,
            ContractReviewRunRepository runs, ContractReviewRunMaterialRepository materials,
            ContractReviewStepRepository steps, ContractReviewStepAttemptRepository attempts,
            IdempotencyPort idempotency, OperationAuditPort audit, Clock clock) {
        this(readiness, identities, manifests, runs, materials, steps, attempts,
                idempotency, audit, clock, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ContractReviewRunCreateTransactionCoordinator(ReviewRunReadinessResolver readiness,
            ReviewRunSnapshotIdentityFactory identities, ReviewRunManifestFactory manifests,
            ContractReviewRunRepository runs, ContractReviewRunMaterialRepository materials,
            ContractReviewStepRepository steps, ContractReviewStepAttemptRepository attempts,
            IdempotencyPort idempotency, OperationAuditPort audit, Clock clock,
            ContractReviewExecutionPolicyResolver policies) {
        this.readiness = readiness; this.identities = identities; this.manifests = manifests;
        this.runs = runs; this.materials = materials; this.steps = steps; this.attempts = attempts;
        this.idempotency = idempotency; this.audit = audit; this.clock = clock; this.policies = policies;
    }

    @Transactional
    public ContractReviewRunCreateResult create(CreateContractReviewRunCommand command, CurrentUser actor) {
        // A completed operation replays its frozen result, even if current authority has since drifted.
        var prior = idempotency.findByScopeAndKey(SCOPE, command.idempotencyKey());
        if (prior.isPresent()) return replay(prior.orElseThrow(), command, actor);
        UUID runId = UUID.randomUUID();
        Instant now = clock.instant();
        ReviewRunReadinessResolver.Ready ready;
        try {
            ready = readiness.resolve(runId, command.projectId(), command.contractId(),
                    command.expectedContractVersion(), command.materialFileIds(), actor.userId(), now);
        } catch (BusinessException readinessFailure) {
            // An earlier absent-key read may race with a successful winner while waiting on authority rows.
            var winner = idempotency.findByScopeAndKey(SCOPE, command.idempotencyKey());
            if (winner.isPresent()) return replay(winner.orElseThrow(), command, actor);
            throw new ContractReviewRunCreateException(readinessFailure.errorCode(),
                    readinessFailure.getMessage(), READINESS_REJECTED, readinessFailure);
        }
        // Authority row locks are now held: a same-project waiter sees the committed winner's claim.
        var existing = idempotency.findByScopeAndKey(SCOPE, command.idempotencyKey());
        if (existing.isPresent()) return replay(existing.orElseThrow(), command, actor);
        var policy = policies == null
                ? new ContractReviewExecutionPolicyResolver.ResolvedPolicy(
                        ContractReviewProfileVersions.REVIEW_PROFILE_VERSION,
                        ContractReviewProfileVersions.RULE_VERSION,
                        ContractReviewProfileVersions.MODEL_POLICY_VERSION, List.of())
                : policies.resolveForNewRun(command.projectId());
        String inputIdentity = identities.identity(command.projectId(), command.contractId(),
                ready.contract().versionNo(), ready.contractSnapshot().sha256(), ready.sourceCandidateId(),
                ready.sourceConfirmationId(), ready.materials(),
                policy.reviewProfileVersion(), policy.ruleVersion(), policy.modelPolicyVersion());
        String fingerprint = identities.commandFingerprint(actor.userId(), command.projectId(),
                command.contractId(), command.expectedContractVersion(), command.materialFileIds(), inputIdentity);
        IdempotencyClaim claim = idempotency.claim(new IdempotencyRequest(SCOPE, command.idempotencyKey(),
                actor.userId(), "CONTRACT", command.contractId().toString(), fingerprint,
                "CONTRACT_REVIEW_RUN", runId.toString(), command.metadata().requestId(),
                command.metadata().traceId(), now));
        if (!claim.acquired()) return replay(claim.record(), command, actor);
        try {
            ContractReviewRun run = new ContractReviewRun(runId, command.projectId(), command.contractId(),
                    ready.contract().versionNo(), ready.sourceCandidateId(), ready.sourceConfirmationId(),
                    ContractReviewProfileVersions.RUN_SCHEMA_VERSION, ready.contractSnapshot().canonicalJson(),
                    ready.contractSnapshot().sha256(), policy.reviewProfileVersion(),
                    policy.ruleVersion(), policy.modelPolicyVersion(),
                    inputIdentity, actor.userId(), ContractReviewRunStatus.PENDING, now,
                    null, null, null, null, null, 0);
            var manifest = policies == null ? manifests.create(runId, now) : manifests.create(runId, now, policy);
            runs.insert(run);
            ready.materials().forEach(materials::insert);
            manifest.steps().forEach(steps::insert);
            manifest.attempts().forEach(attempts::insert);
            audit.append(new OperationAuditEvent("CONTRACT_REVIEW_RUN_CREATED", "CONTRACT", "create",
                    actor.userId(), actor.username(), "CONTRACT_REVIEW_RUN", runId.toString(), null,
                    OperationAuditResultStatus.SUCCESS, "SUCCESS", "create immutable review run",
                    "{\"materialCount\":" + ready.materials().size() + ",\"stepCount\":5}",
                    command.metadata().requestId(), command.metadata().traceId(), command.metadata().sourceIp(), now));
            idempotency.markSucceeded(claim.record(), "CONTRACT_REVIEW_RUN", runId.toString(), now);
            return new ContractReviewRunCreateResult(run, ready.materials(), manifest.steps(),
                    manifest.attempts(), false);
        } catch (RuntimeException failure) {
            // Local fake adapters compensate only this unpublished aggregate. DM8 rolls back the one write tx.
            attempts.rollbackUncommittedRun(runId); steps.rollbackUncommittedRun(runId);
            materials.rollbackUncommittedRun(runId); runs.rollbackUncommittedRun(runId);
            idempotency.rollbackUncommittedClaim(claim.record());
            throw failure;
        }
    }

    private ContractReviewRunCreateResult replay(IdempotencyRecord record,
            CreateContractReviewRunCommand command, CurrentUser actor) {
        var stored = storedRequest(record, command, actor);
        ContractReviewRun run = stored.run();
        var frozenMaterials = stored.materials();
        UUID runId = run.runId();
        if (!ContractSnapshotCanonicalizer.sha256(run.contractSnapshotCanonicalJson())
                .equals(run.contractSnapshotSha256())) throw integrity("review run Contract snapshot hash is invalid");
        if (frozenMaterials.stream().anyMatch(value -> !value.runId().equals(runId)
                || !value.projectId().equals(run.projectId())))
            throw integrity("review run material ownership is invalid");
        String identity = identities.identity(run.runSchemaVersion(), run.projectId(), run.contractId(), run.contractVersion(),
                run.contractSnapshotSha256(), run.sourceCandidateId(), run.sourceConfirmationId(), frozenMaterials,
                run.reviewProfileVersion(), run.ruleVersion(), run.modelPolicyVersion());
        String fingerprint = identities.commandFingerprint(actor.userId(), command.projectId(),
                command.contractId(), command.expectedContractVersion(), command.materialFileIds(), identity);
        if (!identity.equals(run.inputSnapshotIdentity()) || !fingerprint.equals(record.requestFingerprint()))
            throw integrity("review run idempotency fingerprint conflict");
        List<com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep> frozenSteps = steps.findStepsByRunId(runId);
        List<com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt> frozenAttempts =
                attempts.findAttemptsByRunId(runId);
        if (!ReviewRunManifestVerifier.complete(runId, frozenSteps, frozenAttempts))
            throw integrity("review run manifest is incomplete");
        return new ContractReviewRunCreateResult(run, frozenMaterials, frozenSteps, frozenAttempts, true);
    }

    private record Stored(ContractReviewRun run, List<ContractReviewRunMaterial> materials) { }

    private Stored storedRequest(IdempotencyRecord record,
            CreateContractReviewRunCommand command, CurrentUser actor) {
        if (!SCOPE.equals(record.scope()) || !actor.userId().equals(record.actorId())
                || !"CONTRACT".equals(record.resourceType())
                || !command.contractId().toString().equals(record.resourceId())
                || !"CONTRACT_REVIEW_RUN".equals(record.resultResourceType()))
            throw integrity("review run idempotency identity conflict");
        if (record.status() == IdempotencyStatus.IN_PROGRESS)
            throw new ContractReviewRunCreateException(BusinessErrorCode.CONFLICT,
                    "review run create is in progress or outcome is not proven", OUTCOME_UNCERTAIN);
        if (record.status() != IdempotencyStatus.SUCCEEDED)
            throw integrity("review run create durable claim has an invalid terminal state");
        UUID runId;
        try { runId = UUID.fromString(record.resultResourceId()); }
        catch (RuntimeException invalid) { throw integrity("review run result identity is invalid"); }
        ContractReviewRun run = runs.findById(runId)
                .filter(value -> value.projectId().equals(command.projectId())
                        && value.contractId().equals(command.contractId())
                        && value.contractVersion() == command.expectedContractVersion()
                        && value.requestedBy().equals(actor.userId()))
                .orElseThrow(() -> integrity("review run durable result does not match request"));
        var frozenMaterials = materials.findMaterialsByRunId(runId);
        if (frozenMaterials.size() != command.materialFileIds().size()
                || !frozenMaterials.stream().map(value -> value.fileId()).toList().containsAll(command.materialFileIds()))
            throw integrity("review run material selection changed");
        return new Stored(run, frozenMaterials);
    }

    private static ContractReviewRunCreateException integrity(String message) {
        return new ContractReviewRunCreateException(BusinessErrorCode.CONFLICT, message,
                INTEGRITY_CONFLICT);
    }
}
