package com.fuquan.audit.contract.application.project.role;

import com.fuquan.audit.contract.domain.project.DeterministicDocumentRoleResult;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.DocumentRoleCandidate;
import com.fuquan.audit.contract.domain.project.DocumentRoleSignal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure scoring of deterministic signals. Confidence is display-only and never authorizes a role. */
public final class DeterministicDocumentRoleResolver {
    private static final int CONFIDENCE_SCALE = 6;
    private static final Comparator<DocumentRoleSignal> EVIDENCE_ORDER =
            Comparator.comparing(DocumentRoleSignal::signalType)
                    .thenComparing(DocumentRoleSignal::ruleCode)
                    .thenComparingInt(DocumentRoleSignal::charStart)
                    .thenComparingInt(DocumentRoleSignal::charEnd)
                    .thenComparing(signal -> signal.parseResultId().toString());

    public DeterministicDocumentRoleResult resolve(List<DocumentRoleSignal> signals) {
        Objects.requireNonNull(signals, "signals must not be null");
        Map<DocumentRole, List<DocumentRoleSignal>> evidenceByRole = new EnumMap<>(DocumentRole.class);
        for (DocumentRoleSignal signal : signals) {
            Objects.requireNonNull(signal, "signals must not contain null");
            if (signal.role() != DocumentRole.UNKNOWN && signal.weight().compareTo(BigDecimal.ZERO) > 0) {
                evidenceByRole.computeIfAbsent(signal.role(), ignored -> new ArrayList<>()).add(signal);
            }
        }
        if (evidenceByRole.isEmpty()) {
            return new DeterministicDocumentRoleResult(
                    DocumentRole.UNKNOWN, BigDecimal.ZERO, List.of(), false, List.of(),
                    DeterministicDocumentRoleResult.DecisionStatus.NEEDS_CONFIRMATION);
        }

        Map<DocumentRole, BigDecimal> scores = new EnumMap<>(DocumentRole.class);
        BigDecimal totalScore = BigDecimal.ZERO;
        for (Map.Entry<DocumentRole, List<DocumentRoleSignal>> entry : evidenceByRole.entrySet()) {
            entry.getValue().sort(EVIDENCE_ORDER);
            BigDecimal score = entry.getValue().stream()
                    .map(DocumentRoleSignal::weight)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            scores.put(entry.getKey(), score);
            totalScore = totalScore.add(score);
        }

        BigDecimal denominator = totalScore;
        List<DocumentRoleCandidate> candidates = scores.entrySet().stream()
                .map(entry -> new DocumentRoleCandidate(
                        entry.getKey(), entry.getValue(), displayConfidence(entry.getValue(), denominator),
                        evidenceByRole.get(entry.getKey())))
                .sorted(Comparator.comparing(DocumentRoleCandidate::score, BigDecimal::compareTo).reversed()
                        .thenComparing(DocumentRoleCandidate::role))
                .toList();

        BigDecimal maximum = candidates.get(0).score();
        boolean conflict = candidates.stream()
                .filter(candidate -> candidate.score().compareTo(maximum) == 0)
                .limit(2)
                .count() == 2;
        DocumentRole proposedRole = conflict ? DocumentRole.UNKNOWN : candidates.get(0).role();
        List<DocumentRoleSignal> orderedEvidence = candidates.stream()
                .flatMap(candidate -> candidate.evidence().stream())
                .toList();

        return new DeterministicDocumentRoleResult(
                proposedRole, candidates.get(0).confidence(), candidates, conflict, orderedEvidence,
                DeterministicDocumentRoleResult.DecisionStatus.NEEDS_CONFIRMATION);
    }

    private static BigDecimal displayConfidence(BigDecimal score, BigDecimal totalScore) {
        return score.divide(totalScore, CONFIDENCE_SCALE, RoundingMode.HALF_UP);
    }
}
