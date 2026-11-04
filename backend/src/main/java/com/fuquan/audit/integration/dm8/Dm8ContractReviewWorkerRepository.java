package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.contract.application.reviewrun.ReviewWorkerLeasePolicy;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewWorkerRepository;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** No process-local queue or lock: the conditional DM8 write is the ownership decision. */
@Repository
@Profile({"local-dev", "dev29", "customer"})
public class Dm8ContractReviewWorkerRepository implements ContractReviewWorkerRepository {
    private final ContractReviewWorkerMapper mapper;
    public Dm8ContractReviewWorkerRepository(ContractReviewWorkerMapper mapper) { this.mapper = mapper; }

    @Override public Optional<BusinessInitiator> findBusinessInitiator(String userId) {
        return Optional.ofNullable(mapper.findBusinessInitiator(Objects.requireNonNull(userId)));
    }

    @Override public List<Candidate> findClaimCandidates(int limit, Instant now) {
        validateLimit(limit);
        return candidates(mapper.findClaimCandidates(limit, date(Objects.requireNonNull(now))));
    }
    @Override public List<Candidate> findExpiredLeases(int limit, Instant now) {
        validateLimit(limit);
        return candidates(mapper.findExpiredLeases(limit, date(Objects.requireNonNull(now))));
    }
    @Override public ContractReviewStepAttempt compareAndSetAttempt(ContractReviewStepAttempt expected,
            ContractReviewStepAttempt next, LeaseCondition condition, Instant now) {
        Objects.requireNonNull(condition); Objects.requireNonNull(now);
        if (!expected.attemptId().equals(next.attemptId()) || !expected.runId().equals(next.runId())
                || !expected.stepId().equals(next.stepId()) || expected.attemptNo() != next.attemptNo()
                || !expected.operationIdentity().equals(next.operationIdentity())
                || next.versionNo() != expected.versionNo() + 1) throw ReviewWorkerLeasePolicy.conflict();
        if (mapper.compareAndSetAttempt(row(expected), row(next), condition.name(), date(now)) != 1)
            throw ReviewWorkerLeasePolicy.conflict();
        return next;
    }
    @Override public void advanceRetry(ContractReviewStep expected, ContractReviewStep next,
            ContractReviewRun expectedRun, ContractReviewRun resumedRun) {
        if (!expected.stepId().equals(next.stepId()) || !expected.runId().equals(expectedRun.runId())
                || !expected.runId().equals(next.runId()) || !expectedRun.runId().equals(resumedRun.runId())
                || expected.status() != ContractReviewStepStatus.FAILED || next.status() != ContractReviewStepStatus.PENDING
                || next.currentAttemptNo() != expected.currentAttemptNo() + 1
                || next.versionNo() != expected.versionNo() + 1
                || resumedRun.versionNo() != expectedRun.versionNo() + 1
                || resumedRun.status() != ContractReviewRunStatus.RUNNING
                || (expectedRun.status() != ContractReviewRunStatus.FAILED
                    && expectedRun.status() != ContractReviewRunStatus.RUNNING)) throw ReviewWorkerLeasePolicy.conflict();
        if (mapper.advanceRetryStep(expected.stepId().toString(), expected.runId().toString(),
                expected.versionNo(), expected.currentAttemptNo(), next.currentAttemptNo()) != 1)
            throw ReviewWorkerLeasePolicy.conflict();
        if (mapper.resumeRetryRun(expectedRun.runId().toString(), expectedRun.versionNo(), expectedRun.status().name()) != 1)
            throw ReviewWorkerLeasePolicy.conflict();
    }
    private static List<Candidate> candidates(List<ContractReviewStepAttemptPersistenceRow> rows) {
        return rows.stream().map(row -> new Candidate(UUID.fromString(row.runId()),
                UUID.fromString(row.stepId()), UUID.fromString(row.attemptId()), row.versionNo())).toList();
    }
    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("worker batch limit must be 1..100");
    }
    private static ContractReviewStepAttemptPersistenceRow row(ContractReviewStepAttempt value) {
        return new ContractReviewStepAttemptPersistenceRow(value.attemptId().toString(), value.runId().toString(),
                value.stepId().toString(), value.attemptNo(), value.operationIdentity(), value.status().name(),
                value.leaseOwner(), date(value.leaseUntil()), date(value.claimedAt()), value.externalCallState().name(),
                value.externalRequestFingerprint(), value.providerRequestId(), value.provider(), value.model(),
                date(value.startedAt()), date(value.completedAt()), value.safeReasonCode(), value.outputResourceType(),
                value.outputResourceId() == null ? null : value.outputResourceId().toString(), value.versionNo());
    }
    private static LocalDateTime date(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
