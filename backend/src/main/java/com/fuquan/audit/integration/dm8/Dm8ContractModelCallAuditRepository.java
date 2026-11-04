package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.BatchStatus;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.CallOutcome;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.CallPhase;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.Scope;
import com.fuquan.audit.contract.application.project.factai.ContractModelCallAudit;
import com.fuquan.audit.contract.application.project.factai.ContractModelCallAuditRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile({"local-dev", "dev29", "customer"})
public class Dm8ContractModelCallAuditRepository implements ContractModelCallAuditRepository {
    private final ContractFactExtractionMapper mapper;

    public Dm8ContractModelCallAuditRepository(ContractFactExtractionMapper mapper) {
        this.mapper = mapper;
    }

    @Override public void insertAll(List<ContractModelCallAudit> calls) {
        for (ContractModelCallAudit call : List.copyOf(calls)) mapper.insertModelCall(row(call));
    }

    @Override public List<ContractModelCallAudit> findByReviewStepAttempt(UUID attemptId) {
        return mapper.findModelCallsByReviewStepAttempt(attemptId.toString()).stream()
                .map(this::domain).toList();
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

    private ContractModelCallAudit domain(ContractModelCallAuditRow value) {
        return new ContractModelCallAudit(UUID.fromString(value.callAuditId()),
                value.factExtractionAttemptId() == null ? null : UUID.fromString(value.factExtractionAttemptId()),
                value.reviewStepAttemptId() == null ? null : UUID.fromString(value.reviewStepAttemptId()),
                value.attemptNo(), value.scope() == null ? null : Scope.valueOf(value.scope()),
                value.callOrdinal(), CallPhase.valueOf(value.phase()), value.requestFingerprint(),
                value.provider(), value.modelName(), CallOutcome.valueOf(value.outcome()), value.safeReason(),
                value.durationMs(), value.finishReason(), value.promptTokens(), value.completionTokens(),
                value.totalTokens(), value.batchStatus() == null ? null : BatchStatus.valueOf(value.batchStatus()),
                instant(value.createdAt()));
    }

    private static LocalDateTime time(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static Instant instant(LocalDateTime value) { return value.toInstant(ZoneOffset.UTC); }
}
