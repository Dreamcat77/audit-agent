package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewEvidenceRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewFindingRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewClauseComparisonRepository;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewEvidence;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewFinding;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewClauseComparison;
import com.fuquan.audit.contract.domain.reviewrun.ReviewFindingSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8ContractReviewResultsRepositories implements ContractReviewFindingRepository,
        ContractReviewEvidenceRepository, ContractReviewClauseComparisonRepository {
    private final ContractReviewResultsMapper mapper;
    public Dm8ContractReviewResultsRepositories(ContractReviewResultsMapper mapper) { this.mapper = mapper; }

    @Override public void insert(ContractReviewFinding value) {
        requireOne(mapper.insertFinding(new ContractReviewFindingPersistenceRow(id(value.findingId()),
                id(value.runId()), id(value.stepId()), id(value.attemptId()), value.ordinal(),
                value.findingType(), value.category(), value.statusCode(), value.attentionCode(),
                value.riskLevel(), value.summary(), value.recommendation(), value.findingSchemaVersion(),
                value.policyVersion(), value.source().name(), value.modelProvider(), value.modelName(),
                value.modelRevision(), value.promptVersion(), value.modelSchemaVersion(),
                date(value.createdAt()), value.versionNo())));
    }
    @Override public void insert(ContractReviewEvidence value) {
        requireOne(mapper.insertEvidence(new ContractReviewEvidencePersistenceRow(id(value.evidenceId()),
                id(value.findingId()), id(value.runId()), id(value.stepId()), id(value.attemptId()),
                value.ordinal(), id(value.fileId()), value.fileSha256(), id(value.parseResultId()),
                value.parseVersion(), value.canonicalTextSha256(), value.evidenceRole(),
                value.validationStatus(), value.validationPolicyVersion(), value.pageNumber(),
                value.paragraphIndex(), value.tableIndex(), value.rowIndex(), value.cellIndex(),
                value.segmentIndex(), value.locatorAvailable() ? 1 : 0, value.startOffset(),
                value.endOffset(), value.excerptSha256(), value.boundedExcerpt(),
                value.locatorSchemaVersion(), date(value.createdAt()), value.versionNo())));
    }
    @Override public List<ContractReviewFinding> findFindingsByAttemptId(UUID attemptId) {
        return mapper.findFindingsByAttemptId(id(attemptId)).stream().map(this::finding).toList();
    }
    @Override public List<ContractReviewFinding> findFindingsByRunId(UUID runId) {
        return mapper.findFindingsByRunId(id(runId)).stream().map(this::finding).toList();
    }
    @Override public List<ContractReviewEvidence> findEvidenceByAttemptId(UUID attemptId) {
        return mapper.findEvidenceByAttemptId(id(attemptId)).stream().map(this::evidence).toList();
    }
    @Override public List<ContractReviewEvidence> findEvidenceByRunId(UUID runId) {
        return mapper.findEvidenceByRunId(id(runId)).stream().map(this::evidence).toList();
    }
    @Override public void insert(ContractReviewClauseComparison value) {
        requireOne(mapper.insertClauseComparison(new ContractReviewClauseComparisonPersistenceRow(
                id(value.comparisonId()), id(value.findingId()), id(value.runId()), id(value.stepId()),
                id(value.attemptId()), value.comparisonKey(), value.kind(), value.result(),
                value.schemaVersion())));
        for (var side : value.values()) {
            requireOne(mapper.insertClauseValue(new ContractReviewClauseValuePersistenceRow(
                    id(side.valueId()), id(side.comparisonId()), id(value.runId()),
                    side.sideKey(), id(side.sourceFileId()),
                    side.state(), side.scenarioKey(), side.responsibleParty(), side.calculationBasis(),
                    side.rate(), side.rateUnit(), side.frequency(), side.period(), side.capRate(),
                    side.capAmount(), side.terminationThreshold(), side.dateType(), side.normalizedDate(),
                    side.termStart(), side.duration(), side.effectiveCondition(),
                    side.terminationCondition(), null)));
            for (UUID evidenceId : side.evidenceIds())
                requireOne(mapper.insertClauseValueEvidence(id(side.valueId()), id(evidenceId)));
        }
    }
    @Override public List<ContractReviewClauseComparison> findByAttemptId(UUID attemptId) {
        return mapper.findClauseComparisonsByAttemptId(id(attemptId)).stream()
                .map(this::comparison).toList();
    }
    @Override public List<ContractReviewClauseComparison> findByRunId(UUID runId) {
        return mapper.findClauseComparisonsByRunId(id(runId)).stream()
                .map(this::comparison).toList();
    }

    private ContractReviewFinding finding(ContractReviewFindingPersistenceRow row) {
        return new ContractReviewFinding(uuid(row.findingId()), uuid(row.runId()), uuid(row.stepId()),
                uuid(row.attemptId()), row.ordinal(), row.findingType(), row.category(), row.statusCode(),
                row.attentionCode(), row.riskLevel(), row.summary(), row.recommendation(),
                row.findingSchemaVersion(), row.policyVersion(), ReviewFindingSource.valueOf(row.source()),
                row.modelProvider(), row.modelName(), row.modelRevision(), row.promptVersion(),
                row.modelSchemaVersion(), instant(row.createdAt()), row.versionNo());
    }
    private ContractReviewEvidence evidence(ContractReviewEvidencePersistenceRow row) {
        return new ContractReviewEvidence(uuid(row.evidenceId()), uuid(row.findingId()), uuid(row.runId()),
                uuid(row.stepId()), uuid(row.attemptId()), row.ordinal(), uuid(row.fileId()),
                row.fileSha256(), uuid(row.parseResultId()), row.parseVersion(), row.canonicalTextSha256(),
                row.evidenceRole(), row.validationStatus(), row.validationPolicyVersion(), row.pageNumber(),
                row.paragraphIndex(), row.tableIndex(), row.rowIndex(), row.cellIndex(), row.segmentIndex(),
                row.locatorAvailable() == 1, row.startOffset(), row.endOffset(), row.excerptSha256(),
                row.boundedExcerpt(), row.locatorSchemaVersion(), instant(row.createdAt()), row.versionNo());
    }
    private ContractReviewClauseComparison comparison(ContractReviewClauseComparisonPersistenceRow row) {
        Map<String, List<ContractReviewClauseValuePersistenceRow>> grouped = new LinkedHashMap<>();
        for (ContractReviewClauseValuePersistenceRow value : mapper.findClauseValues(row.comparisonId()))
            grouped.computeIfAbsent(value.valueId(), ignored -> new ArrayList<>()).add(value);
        List<ContractReviewClauseComparison.Value> values = grouped.values().stream()
                .map(this::value).toList();
        return new ContractReviewClauseComparison(uuid(row.comparisonId()), uuid(row.findingId()),
                uuid(row.runId()), uuid(row.stepId()), uuid(row.attemptId()), row.comparisonKey(),
                row.kind(), row.result(), row.schemaVersion(), values);
    }

    private ContractReviewClauseComparison.Value value(
            List<ContractReviewClauseValuePersistenceRow> rows) {
        ContractReviewClauseValuePersistenceRow value = rows.get(0);
        List<UUID> evidenceIds = rows.stream().map(ContractReviewClauseValuePersistenceRow::evidenceId)
                .filter(java.util.Objects::nonNull).map(Dm8ContractReviewResultsRepositories::uuid).toList();
        return new ContractReviewClauseComparison.Value(uuid(value.valueId()),
                uuid(value.comparisonId()), uuid(value.runId()), value.sideKey(),
                uuid(value.sourceFileId()), value.state(), value.scenarioKey(),
                value.responsibleParty(), value.calculationBasis(), value.rateValue(),
                value.rateUnit(), value.frequency(), value.periodValue(), value.capRate(),
                value.capAmount(), value.terminationThreshold(), value.dateType(),
                value.normalizedDate(), value.termStart(), value.durationValue(),
                value.effectiveCondition(), value.terminationCondition(), evidenceIds);
    }
    private static void requireOne(int changed) {
        if (changed != 1) throw new IllegalStateException("review result write was not durable");
    }
    private static String id(UUID value) { return value.toString(); }
    private static UUID uuid(String value) { return UUID.fromString(value); }
    private static LocalDateTime date(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static Instant instant(LocalDateTime value) { return value.toInstant(ZoneOffset.UTC); }
}
