package com.fuquan.audit.contract.infra.memory.reviewrun;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewEvidenceRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewFindingRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewClauseComparisonRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewRunMaterialRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewStepAttemptRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewStepRepository;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewEvidence;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewFinding;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewClauseComparison;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryContractReviewResultsRepositories implements ContractReviewFindingRepository,
        ContractReviewEvidenceRepository, ContractReviewClauseComparisonRepository {
    private final ContractReviewStepAttemptRepository attempts;
    private final ContractReviewRunMaterialRepository materials;
    private final ContractReviewStepRepository steps;
    private final ConcurrentMap<UUID, ContractReviewFinding> findings = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, ContractReviewEvidence> evidence = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, ContractReviewClauseComparison> comparisons = new ConcurrentHashMap<>();

    public InMemoryContractReviewResultsRepositories(ContractReviewStepAttemptRepository attempts,
            ContractReviewRunMaterialRepository materials, ContractReviewStepRepository steps) {
        this.attempts = attempts;
        this.materials = materials;
        this.steps = steps;
    }

    @Override public synchronized void insert(ContractReviewFinding finding) {
        var attempt = attempts.findAttemptById(finding.attemptId()).orElseThrow(
                InMemoryContractReviewResultsRepositories::conflict);
        if (!attempt.runId().equals(finding.runId()) || !attempt.stepId().equals(finding.stepId())
                || findings.containsKey(finding.findingId()) || findings.values().stream().anyMatch(value ->
                value.attemptId().equals(finding.attemptId()) && value.ordinal() == finding.ordinal()))
            throw conflict();
        findings.put(finding.findingId(), finding);
    }
    @Override public synchronized void insert(ContractReviewEvidence item) {
        var finding = findings.get(item.findingId());
        if (finding == null || !finding.runId().equals(item.runId())
                || !finding.stepId().equals(item.stepId())
                || !finding.attemptId().equals(item.attemptId())
                || evidence.containsKey(item.evidenceId())
                || evidence.values().stream().anyMatch(value ->
                value.findingId().equals(item.findingId()) && value.ordinal() == item.ordinal())
                || materials.findMaterialsByRunId(item.runId()).stream().noneMatch(material ->
                material.fileId().equals(item.fileId())
                        && material.fileSha256().equals(item.fileSha256())
                        && material.parseResultId().equals(item.parseResultId())
                        && material.parseVersion() == item.parseVersion()
                        && material.canonicalTextSha256().equals(item.canonicalTextSha256())))
            throw conflict();
        evidence.put(item.evidenceId(), item);
    }
    @Override public List<ContractReviewFinding> findFindingsByAttemptId(UUID attemptId) {
        return findings.values().stream().filter(value -> value.attemptId().equals(attemptId))
                .sorted(Comparator.comparingInt(ContractReviewFinding::ordinal)
                        .thenComparing(value -> value.findingId().toString())).toList();
    }
    @Override public List<ContractReviewFinding> findFindingsByRunId(UUID runId) {
        return findings.values().stream().filter(value -> value.runId().equals(runId))
                .sorted(Comparator.comparingInt((ContractReviewFinding value) ->
                        steps.findStepById(value.stepId()).orElseThrow(
                                InMemoryContractReviewResultsRepositories::conflict).stepType().ordinal())
                        .thenComparingInt(ContractReviewFinding::ordinal)
                        .thenComparing(value -> value.findingId().toString())).toList();
    }
    @Override public List<ContractReviewEvidence> findEvidenceByAttemptId(UUID attemptId) {
        return evidence.values().stream().filter(value -> value.attemptId().equals(attemptId))
                .sorted(Comparator.comparingInt((ContractReviewEvidence value) ->
                        findings.get(value.findingId()).ordinal())
                        .thenComparingInt(ContractReviewEvidence::ordinal)
                        .thenComparing(value -> value.evidenceId().toString())).toList();
    }
    @Override public List<ContractReviewEvidence> findEvidenceByRunId(UUID runId) {
        return evidence.values().stream().filter(value -> value.runId().equals(runId))
                .sorted(Comparator.comparingInt((ContractReviewEvidence value) ->
                        steps.findStepById(value.stepId()).orElseThrow(
                                InMemoryContractReviewResultsRepositories::conflict).stepType().ordinal())
                        .thenComparingInt(value -> findings.get(value.findingId()).ordinal())
                        .thenComparingInt(ContractReviewEvidence::ordinal)
                        .thenComparing(value -> value.evidenceId().toString())).toList();
    }
    @Override public synchronized void insert(ContractReviewClauseComparison comparison) {
        ContractReviewFinding finding = findings.get(comparison.findingId());
        if (finding == null || !finding.runId().equals(comparison.runId())
                || !finding.stepId().equals(comparison.stepId())
                || !finding.attemptId().equals(comparison.attemptId())
                || comparisons.containsKey(comparison.comparisonId())
                || comparison.values().stream().anyMatch(value -> {
                    if (!value.runId().equals(comparison.runId())) return true;
                    return value.evidenceIds().stream().anyMatch(evidenceId -> {
                        ContractReviewEvidence item = evidence.get(evidenceId);
                        return item == null || !item.findingId().equals(comparison.findingId())
                                || !item.fileId().equals(value.sourceFileId())
                                || !"COMPARISON".equals(item.evidenceRole());
                    });
                })) throw conflict();
        comparisons.put(comparison.comparisonId(), comparison);
    }
    @Override public List<ContractReviewClauseComparison> findByAttemptId(UUID attemptId) {
        return comparisons.values().stream().filter(value -> value.attemptId().equals(attemptId))
                .sorted(Comparator.comparing(ContractReviewClauseComparison::comparisonKey)).toList();
    }
    @Override public List<ContractReviewClauseComparison> findByRunId(UUID runId) {
        return comparisons.values().stream().filter(value -> value.runId().equals(runId))
                .sorted(Comparator.comparing(ContractReviewClauseComparison::comparisonKey)).toList();
    }

    private static BusinessException conflict() {
        return new BusinessException(BusinessErrorCode.CONFLICT, "review result ownership conflict");
    }
}
