package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Provenance;
import com.fuquan.audit.contract.application.project.factai.ContractFactExtractionAttempt;
import com.fuquan.audit.contract.application.project.factai.ContractFactExtractionCandidate;
import com.fuquan.audit.contract.application.project.factai.ContractFactExtractionRepository;
import com.fuquan.audit.contract.application.project.factai.ContractFactExtractionRejection;
import com.fuquan.audit.contract.application.project.factai.ContractModelCallAudit;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.BatchStatus;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.CallOutcome;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.CallPhase;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.RejectionStage;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Scope;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

@Repository
@Profile({"local-dev", "dev29", "customer"})
public class Dm8ContractFactExtractionRepository implements ContractFactExtractionRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(Dm8ContractFactExtractionRepository.class);
    private final ContractFactExtractionMapper mapper;
    public Dm8ContractFactExtractionRepository(ContractFactExtractionMapper mapper) { this.mapper = mapper; }

    @Override
    public Claim claim(ContractFactExtractionAttempt proposed) {
        ContractFactExtractionAttemptRow existing = mapper.findAttemptByOperation(
                proposed.actorId(), proposed.operationKey());
        if (existing == null) {
            try {
                mapper.insertAttempt(row(proposed));
                return new Claim(true, proposed);
            } catch (DuplicateKeyException raced) {
                existing = mapper.findAttemptByOperation(proposed.actorId(), proposed.operationKey());
                if (existing == null) throw raced;
            }
        }
        ContractFactExtractionAttempt current = domain(existing);
        if (current.status() == ContractFactExtractionAttempt.Status.FAILED
                && mapper.retryFailed(current.attemptId().toString(), current.versionNo(),
                    time(proposed.updatedAt())) == 1) {
            return new Claim(true, domain(mapper.findAttemptById(current.attemptId().toString())));
        }
        return new Claim(false, current);
    }

    @Override public Optional<ContractFactExtractionAttempt> findById(UUID attemptId) {
        return Optional.ofNullable(mapper.findAttemptById(attemptId.toString())).map(this::domain);
    }
    @Override public Optional<ContractFactExtractionAttempt> findByOperation(String actorId,
            String operationKey) {
        return Optional.ofNullable(mapper.findAttemptByOperation(actorId, operationKey)).map(this::domain);
    }
    @Override public Optional<ContractFactExtractionAttempt> findLatest(UUID projectId, UUID fileId,
            UUID parseResultId) {
        return Optional.ofNullable(mapper.findLatest(projectId.toString(), fileId.toString(),
                parseResultId.toString())).map(this::domain);
    }
    @Override public List<ContractFactExtractionCandidate> findCandidates(UUID attemptId) {
        return mapper.findCandidates(attemptId.toString()).stream().map(this::domain).toList();
    }
    @Override public List<ContractFactExtractionRejection> findRejections(UUID attemptId) {
        return mapper.findRejections(attemptId.toString()).stream().map(this::domain).toList();
    }
    @Override public List<ContractModelCallAudit> findModelCalls(UUID attemptId) {
        return mapper.findModelCallsByFactAttempt(attemptId.toString()).stream().map(this::domain).toList();
    }
    @Override public Optional<ContractFactExtractionAttempt> findLatestForAuthority(UUID projectId,
            UUID fileId, UUID parseResultId, UUID roleDecisionId, UUID identityDecisionId,
            DocumentRole effectiveRole, Provenance provenance) {
        return Optional.ofNullable(mapper.findLatestForAuthority(projectId.toString(), fileId.toString(),
                parseResultId.toString(), roleDecisionId.toString(), identityDecisionId.toString(),
                effectiveRole.name(), provenance.provider(), provenance.modelName(),
                provenance.modelRevision(), provenance.promptVersion(), provenance.schemaVersion()))
                .map(this::domain);
    }
    @Override public void complete(ContractFactExtractionAttempt expected,
            List<ContractFactExtractionCandidate> candidates, int acceptedCount, int rejectedCount,
            Instant completedAt) {
        for (int index = 0; index < candidates.size(); index++) {
            int itemIndex = index;
            ContractFactExtractionCandidate candidate = candidates.get(index);
            ContractFactPersistenceDiagnostics.observe(LOGGER, "CANDIDATE_INSERT", itemIndex,
                    () -> mapper.insertCandidate(row(candidate)));
        }
        if (mapper.complete(expected.attemptId().toString(), expected.versionNo(), acceptedCount,
                rejectedCount, time(completedAt)) != 1) {
            throw new IllegalStateException("contract fact extraction completion conflict");
        }
    }
    @Override public void completeV2(ContractFactExtractionAttempt expected,
            List<ContractFactExtractionCandidate> candidates,
            List<ContractFactExtractionRejection> rejections,
            List<ContractModelCallAudit> modelCalls, int acceptedCount, int rejectedCount,
            Instant completedAt) {
        for (int index = 0; index < candidates.size(); index++) {
            int itemIndex = index;
            ContractFactExtractionCandidate candidate = candidates.get(index);
            ContractFactPersistenceDiagnostics.observe(LOGGER, "CANDIDATE_INSERT", itemIndex,
                    () -> mapper.insertCandidate(row(candidate)));
        }
        for (int index = 0; index < rejections.size(); index++) {
            int itemIndex = index;
            ContractFactExtractionRejection rejection = rejections.get(index);
            ContractFactPersistenceDiagnostics.observe(LOGGER, "REJECTION_INSERT", itemIndex,
                    () -> mapper.insertRejection(row(rejection)));
        }
        for (int index = 0; index < modelCalls.size(); index++) {
            int itemIndex = index;
            ContractModelCallAudit call = modelCalls.get(index);
            ContractFactPersistenceDiagnostics.observe(LOGGER, "MODEL_CALL_INSERT", itemIndex,
                    () -> mapper.insertModelCall(row(call)));
        }
        if (mapper.complete(expected.attemptId().toString(), expected.versionNo(), acceptedCount,
                rejectedCount, time(completedAt)) != 1) {
            throw new IllegalStateException("contract fact extraction completion conflict");
        }
    }
    @Override public void fail(ContractFactExtractionAttempt expected, String reason, Instant failedAt) {
        if (mapper.fail(expected.attemptId().toString(), expected.versionNo(), reason,
                ContractFactExtractionAttempt.failureStatus(reason).name(),
                time(failedAt)) != 1) {
            throw new IllegalStateException("contract fact extraction failure conflict");
        }
    }
    @Override public void failV2(ContractFactExtractionAttempt expected,
            List<ContractFactExtractionRejection> rejections,
            List<ContractModelCallAudit> modelCalls, int rejectedCount,
            String reason, Instant failedAt) {
        for (int index = 0; index < rejections.size(); index++) {
            int itemIndex = index;
            ContractFactExtractionRejection rejection = rejections.get(index);
            ContractFactPersistenceDiagnostics.observe(LOGGER, "REJECTION_INSERT", itemIndex,
                    () -> mapper.insertRejection(row(rejection)));
        }
        for (int index = 0; index < modelCalls.size(); index++) {
            int itemIndex = index;
            ContractModelCallAudit call = modelCalls.get(index);
            ContractFactPersistenceDiagnostics.observe(LOGGER, "MODEL_CALL_INSERT", itemIndex,
                    () -> mapper.insertModelCall(row(call)));
        }
        if (mapper.failV2(expected.attemptId().toString(), expected.versionNo(), rejectedCount,
                reason, ContractFactExtractionAttempt.failureStatus(reason).name(), time(failedAt)) != 1) {
            throw new IllegalStateException("contract fact extraction failure conflict");
        }
    }

    private static ContractFactExtractionAttemptRow row(ContractFactExtractionAttempt a) {
        return new ContractFactExtractionAttemptRow(a.attemptId().toString(), a.projectId().toString(),
                a.fileId().toString(), a.fileSha256(), a.parseResultId().toString(), a.parseVersion(),
                a.canonicalTextSha256(), a.effectiveRole().name(), a.roleDecisionId().toString(),
                a.identityDecisionId().toString(), a.inputFingerprint(), a.actorId(), a.operationKey(),
                a.provenance().provider(), a.provenance().modelName(), a.provenance().modelRevision(),
                a.provenance().promptVersion(), a.provenance().schemaVersion(), a.status().name(),
                a.callCount(), a.acceptedCount(), a.rejectedCount(), a.failureReason(),
                time(a.createdAt()), time(a.updatedAt()), a.versionNo());
    }
    private static ContractFactExtractionCandidateRow row(ContractFactExtractionCandidate c) {
        return new ContractFactExtractionCandidateRow(c.candidateId().toString(), c.attemptId().toString(),
                c.candidateKey(), c.factType(), c.groupKey(), c.valueText(), c.valueDecimal(), c.segmentId(),
                c.sourceLocator(), c.excerpt(), c.excerptStart(), c.excerptEnd(), c.contextCode(),
                c.confidence(), c.validationStatus(), c.validationReason(),
                c.publishedExtractionId() == null ? null : c.publishedExtractionId().toString(),
                time(c.createdAt()));
    }
    private static ContractFactExtractionRejectionRow row(ContractFactExtractionRejection value) {
        return new ContractFactExtractionRejectionRow(value.rejectionId().toString(),
                value.attemptId().toString(), value.attemptNo(), value.scope().name(), value.providerOrdinal(),
                value.stage().name(), value.reasonCode(), value.safeShapeHash(), time(value.createdAt()));
    }
    private static ContractModelCallAuditRow row(ContractModelCallAudit value) {
        return new ContractModelCallAuditRow(value.callAuditId().toString(),
                value.factExtractionAttemptId() == null ? null : value.factExtractionAttemptId().toString(),
                value.reviewStepAttemptId() == null ? null : value.reviewStepAttemptId().toString(),
                value.attemptNo(), value.scope() == null ? null : value.scope().name(), value.callOrdinal(),
                value.phase().name(), value.requestFingerprint(), value.provider(), value.modelName(),
                value.outcome().name(), value.safeReason(), value.durationMs(), value.finishReason(),
                value.promptTokens(), value.completionTokens(), value.totalTokens(),
                value.batchStatus() == null ? null : value.batchStatus().name(), time(value.createdAt()));
    }
    private ContractFactExtractionAttempt domain(ContractFactExtractionAttemptRow a) {
        return new ContractFactExtractionAttempt(UUID.fromString(a.attemptId()), UUID.fromString(a.projectId()),
                UUID.fromString(a.fileId()), a.fileSha256(), UUID.fromString(a.parseResultId()),
                a.parseVersion(), a.canonicalTextSha256(), DocumentRole.valueOf(a.effectiveRole()),
                UUID.fromString(a.roleDecisionId()), UUID.fromString(a.identityDecisionId()),
                a.inputFingerprint(), a.actorId(), a.operationKey(), new Provenance(a.provider(),
                a.modelName(), a.modelRevision(), a.promptVersion(), a.schemaVersion()),
                ContractFactExtractionAttempt.Status.valueOf(a.status()), a.callCount(), a.acceptedCount(),
                a.rejectedCount(), a.failureReason(), instant(a.createdAt()), instant(a.updatedAt()),
                a.versionNo());
    }
    private ContractFactExtractionCandidate domain(ContractFactExtractionCandidateRow c) {
        return new ContractFactExtractionCandidate(UUID.fromString(c.candidateId()),
                UUID.fromString(c.attemptId()), c.candidateKey(), c.factType(), c.groupKey(), c.valueText(),
                c.valueDecimal(), c.segmentId(), c.sourceLocator(), c.excerpt(), c.excerptStart(),
                c.excerptEnd(), c.contextCode(), c.confidence(), c.validationStatus(), c.validationReason(),
                c.publishedExtractionId() == null ? null : UUID.fromString(c.publishedExtractionId()),
                instant(c.createdAt()));
    }
    private ContractFactExtractionRejection domain(ContractFactExtractionRejectionRow value) {
        return new ContractFactExtractionRejection(UUID.fromString(value.rejectionId()),
                UUID.fromString(value.attemptId()), value.attemptNo(), Scope.valueOf(value.scope()),
                value.providerOrdinal(), RejectionStage.valueOf(value.stage()), value.reasonCode(),
                value.safeShapeHash(), instant(value.createdAt()));
    }
    private ContractModelCallAudit domain(ContractModelCallAuditRow value) {
        return new ContractModelCallAudit(UUID.fromString(value.callAuditId()),
                value.factExtractionAttemptId() == null ? null : UUID.fromString(value.factExtractionAttemptId()),
                value.reviewStepAttemptId() == null ? null : UUID.fromString(value.reviewStepAttemptId()),
                value.attemptNo(), value.scope() == null ? null : Scope.valueOf(value.scope()), value.callOrdinal(),
                CallPhase.valueOf(value.phase()), value.requestFingerprint(), value.provider(),
                value.modelName(), CallOutcome.valueOf(value.outcome()), value.safeReason(),
                value.durationMs(), value.finishReason(), value.promptTokens(), value.completionTokens(),
                value.totalTokens(), value.batchStatus() == null ? null : BatchStatus.valueOf(value.batchStatus()),
                instant(value.createdAt()));
    }
    private static LocalDateTime time(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static Instant instant(LocalDateTime value) { return value.toInstant(ZoneOffset.UTC); }
}
