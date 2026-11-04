package com.fuquan.audit.contract.application.closeout;

import com.fuquan.audit.common.application.port.*;
import com.fuquan.audit.contract.application.closeout.port.ContractReviewCloseoutRepository;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.domain.closeout.ContractReviewFindingAcknowledgement;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
public class AcknowledgementTransactionCoordinator {
    public static final String SCOPE = "CONTRACT_REVIEW_FINDING_ACKNOWLEDGEMENT_CREATE";
    public static final String RESOURCE = "CONTRACT_REVIEW_FINDING_ACKNOWLEDGEMENT";
    private final CloseoutOwnership owner;
    private final ContractReviewCloseoutRepository repository;
    private final IdempotencyPort idempotency;
    private final OperationAuditPort audit;
    private final Clock clock;
    public AcknowledgementTransactionCoordinator(CloseoutOwnership owner, ContractReviewCloseoutRepository repository,
            IdempotencyPort idempotency, OperationAuditPort audit, Clock clock) {
        this.owner = owner; this.repository = repository; this.idempotency = idempotency; this.audit = audit; this.clock = clock;
    }
    public com.fuquan.audit.contract.domain.reviewrun.ContractReviewFinding verifyFinding(UUID project, UUID contract, UUID run, UUID finding) {
        return owner.finding(project, contract, run, finding);
    }
    static void verifyFact(ContractReviewFindingAcknowledgement ack, UUID project, UUID contract, UUID run,
            com.fuquan.audit.contract.domain.reviewrun.ContractReviewFinding finding) {
        if (!ack.projectId().equals(project) || !ack.contractId().equals(contract) || !ack.reviewRunId().equals(run)
                || !ack.findingId().equals(finding.findingId()) || !ack.reviewStepId().equals(finding.stepId())
                || !ack.attemptId().equals(finding.attemptId()) || ack.findingVersionNo() != finding.versionNo())
            throw CloseoutOwnership.conflict();
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ContractReviewFindingAcknowledgement create(UUID project, UUID contract, UUID run, UUID finding,
            long version, String note, String key, String actor, ProjectRequestMetadata metadata) {
        var fact = owner.finding(project, contract, run, finding);
        String fingerprint = CloseoutCanonical.sha256(CloseoutCanonical.json(Arrays.asList(SCOPE, actor, project,
                contract, run, fact.stepId(), fact.attemptId(), finding, version, note)));
        var existing = idempotency.findByScopeAndKey(SCOPE, key);
        if (existing.isPresent()) return replay(existing.get(), fingerprint, project, contract, run, finding, actor);
        if (version != fact.versionNo()) throw CloseoutOwnership.conflict();
        var now = clock.instant();
        var proposed = new ContractReviewFindingAcknowledgement(UUID.randomUUID(), project, contract, run,
                fact.stepId(), fact.attemptId(), finding, version, actor, now, note, metadata.requestId(), metadata.traceId(), now, 0);
        var claim = idempotency.claim(new IdempotencyRequest(SCOPE, key, actor, "CONTRACT_REVIEW_FINDING",
                finding.toString(), fingerprint, RESOURCE, proposed.acknowledgementId().toString(), metadata.requestId(), metadata.traceId(), now));
        if (!claim.acquired()) return replay(claim.record(), fingerprint, project, contract, run, finding, actor);
        try {
            var winner = repository.findAcknowledgementByFinding(finding);
            var saved = winner.orElse(proposed);
            verifyFact(saved, project, contract, run, fact);
            if (winner.isEmpty()) {
                repository.insertAcknowledgement(proposed);
                audit.append(new OperationAuditEvent("CONTRACT_FINDING_ACKNOWLEDGED", "CONTRACT", "ACKNOWLEDGE",
                        actor, null, RESOURCE, saved.acknowledgementId().toString(), null,
                        OperationAuditResultStatus.SUCCESS, "ACKNOWLEDGED", "Contract finding acknowledged",
                        null, metadata.requestId(), metadata.traceId(), metadata.sourceIp(), now));
            }
            idempotency.markSucceeded(claim.record(), RESOURCE, saved.acknowledgementId().toString(), now);
            return saved;
        } catch (RuntimeException failure) {
            repository.rollbackUncommittedAcknowledgement(proposed);
            idempotency.rollbackUncommittedClaim(claim.record());
            throw failure;
        }
    }
    private ContractReviewFindingAcknowledgement replay(IdempotencyRecord op, String fingerprint,
            UUID project, UUID contract, UUID run, UUID finding, String actor) {
        if (!fingerprint.equals(op.requestFingerprint()) || !actor.equals(op.actorId())
                || !SCOPE.equals(op.scope()) || !"CONTRACT_REVIEW_FINDING".equals(op.resourceType())
                || !finding.toString().equals(op.resourceId()) || !RESOURCE.equals(op.resultResourceType())
                || op.status() != IdempotencyStatus.SUCCEEDED) throw CloseoutOwnership.conflict();
        var ack = repository.findAcknowledgement(UUID.fromString(op.resultResourceId())).orElseThrow(CloseoutOwnership::conflict);
        if (!ack.projectId().equals(project) || !ack.contractId().equals(contract) || !ack.reviewRunId().equals(run)
                || !ack.findingId().equals(finding)) throw CloseoutOwnership.conflict();
        verifyFact(ack, project, contract, run, owner.finding(project, contract, run, finding));
        return ack;
    }
}
