package com.fuquan.audit.contract.application.reviewrun.executor;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.clause.BreachClauseReview;
import com.fuquan.audit.contract.application.clause.ClauseComparison;
import com.fuquan.audit.contract.application.clause.ContractTermClauseReview;
import com.fuquan.audit.contract.application.reviewrun.FrozenEvidenceGrounder;
import com.fuquan.audit.contract.application.reviewrun.FrozenReviewInput;
import com.fuquan.audit.contract.application.reviewrun.FrozenReviewMaterial;
import com.fuquan.audit.contract.application.reviewrun.ReviewExecutionContext;
import com.fuquan.audit.contract.application.reviewrun.StepComputationResult;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewEvidence;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewFinding;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewClauseComparison;
import com.fuquan.audit.contract.domain.reviewrun.ReviewFindingSource;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/** Normalizes existing deterministic analyzer values without inventing risk or evidence. */
@Component
public final class FrozenClauseResultMapper {
    static final String OUTPUT_RESOURCE_TYPE = "REVIEW_FINDING_SET";
    static final String VALIDATION_POLICY_VERSION = "frozen-evidence-grounding-v1";
    private static final int FINDING_SCHEMA_VERSION = 1;
    private static final int LOCATOR_SCHEMA_VERSION = 1;

    private final FrozenEvidenceGrounder grounder;
    private final Clock clock;

    public FrozenClauseResultMapper(FrozenEvidenceGrounder grounder, Clock clock) {
        this.grounder = grounder;
        this.clock = clock;
    }

    public StepComputationResult mapBreach(BreachClauseReview review, FrozenReviewInput input,
            ReviewExecutionContext context) {
        var output = new MappingOutput(input.materials());
        for (BreachClauseReview.Item item : review.items()) {
            var finding = finding(context, output.nextFindingOrdinal(), "BREACH_CLAUSE", "BREACH",
                    item.status(), breachSummary(item), item.warning());
            List<DeclaredSource> sources = new ArrayList<>();
            sources.add(source(item.sourceFileId(), item.excerpt()));
            addSeparateSource(sources, item.sourceFileId(), item.terminationCondition());
            output.add(finding, sources, "PROBLEM");
        }
        for (ClauseComparison comparison : review.comparisons()) {
            var finding = finding(context, output.nextFindingOrdinal(), "BREACH_COMPARISON", "BREACH",
                    comparison.status(), comparisonSummary(comparison), null, 2);
            List<DeclaredSource> sources = new ArrayList<>(comparison.sources().stream()
                    .map(source -> source(source.fileId(), source.excerpt())).toList());
            comparison.typedSides().forEach(side ->
                    addSeparateSource(sources, side.fileId(), side.terminationCondition()));
            output.addComparison(finding, comparison, sources, "COMPARISON");
        }
        return output.result(context);
    }

    public StepComputationResult mapTerm(ContractTermClauseReview review, FrozenReviewInput input,
            ReviewExecutionContext context) {
        var output = new MappingOutput(input.materials());
        for (ContractTermClauseReview.DateItem item : review.dates()) {
            var finding = finding(context, output.nextFindingOrdinal(), item.type(), "TERM",
                    item.status(), dateSummary(item), null);
            output.add(finding, List.of(source(item.sourceFileId(), item.excerpt())), "PROBLEM");
        }
        for (ContractTermClauseReview.ConditionItem item : review.conditions()) {
            var finding = finding(context, output.nextFindingOrdinal(), item.type(), "TERM",
                    item.status(), conditionSummary(item), null);
            output.add(finding, List.of(source(item.sourceFileId(), item.excerpt())), "PROBLEM");
        }
        for (ContractTermClauseReview.DeterministicCheck check : review.deterministicChecks()) {
            var finding = finding(context, output.nextFindingOrdinal(), check.type(), "TERM",
                    check.status(), check.summary(), check.recommendation());
            output.add(finding, check.sources().stream()
                    .map(value -> source(value.fileId(), value.excerpt())).toList(), "COMPARISON");
        }
        for (ClauseComparison comparison : review.comparisons()) {
            var finding = finding(context, output.nextFindingOrdinal(), "TERM_COMPARISON", "TERM",
                    comparison.status(), comparisonSummary(comparison), null, 2);
            output.addComparison(finding, comparison, comparison.sources().stream()
                    .map(source -> source(source.fileId(), source.excerpt())).toList(), "COMPARISON");
        }
        return output.result(context);
    }

    private ContractReviewFinding finding(ReviewExecutionContext context, int ordinal,
            String findingType, String category, String statusCode, String summary,
            String recommendation) {
        return finding(context, ordinal, findingType, category, statusCode, summary, recommendation,
                FINDING_SCHEMA_VERSION);
    }

    private ContractReviewFinding finding(ReviewExecutionContext context, int ordinal,
            String findingType, String category, String statusCode, String summary,
            String recommendation, int schemaVersion) {
        return new ContractReviewFinding(UUID.randomUUID(), context.run().runId(),
                context.step().stepId(), context.startedAttempt().attemptId(), ordinal,
                findingType, category, statusCode, null, null, summary, recommendation,
                schemaVersion, context.step().stepPolicyVersion(),
                ReviewFindingSource.DETERMINISTIC, null, null, null, null, null,
                clock.instant(), 0);
    }

    private static String breachSummary(BreachClauseReview.Item item) {
        List<String> parts = new ArrayList<>();
        parts.add(item.scenario());
        if (item.rate() != null) parts.add("违约金比例 " + item.rate().toPlainString() + rateUnit(item.rateUnit()));
        if (item.frequency() != null) parts.add("计算周期 " + frequencyLabel(item.frequency()));
        if (item.calculationBasis() != null) parts.add("计算基数 " + basisLabel(item.calculationBasis()));
        if (item.correspondingAmount() != null)
            parts.add("对应金额 " + item.correspondingAmount().toPlainString() + " 元");
        if (item.capRate() != null)
            parts.add("累计上限比例 " + item.capRate().toPlainString() + rateUnit(item.capRateUnit()));
        if (item.capAmount() != null) parts.add("累计上限金额 " + item.capAmount().toPlainString() + " 元");
        if (item.actualPenaltyAmount() != null)
            parts.add("实际违约金额 " + item.actualPenaltyAmount().toPlainString() + " 元");
        if (item.terminationCondition() != null) parts.add("解除条件 " + item.terminationCondition());
        return String.join("；", parts) + "。";
    }

    private static String dateSummary(ContractTermClauseReview.DateItem item) {
        String label = dateLabel(item.type());
        if (item.normalizedDate() == null) return label + "：原文日期未填写或无法确认。";
        return label + "：" + item.normalizedDate()
                + (item.source() == null ? "。" : "（原文：" + item.source() + "）。");
    }

    private static String conditionSummary(ContractTermClauseReview.ConditionItem item) {
        if ("CONTRACT_COPIES".equals(item.type())) {
            return "合同份数：原文约定一式 " + item.description() + " 份。";
        }
        return ("EFFECTIVE_EVENT".equals(item.type()) ? "生效条件" : "终止条件")
                + "：" + item.description();
    }

    private static String comparisonSummary(ClauseComparison comparison) {
        return comparison.label() + "：" + comparison.message();
    }

    private static String rateUnit(String unit) {
        return switch (unit == null ? "" : unit) {
            case "PERCENT" -> "%";
            case "PER_MILLE" -> "‰";
            default -> "（单位待核）";
        };
    }

    private static String frequencyLabel(String value) {
        return switch (value) {
            case "DAY" -> "每日";
            case "WEEK" -> "每周";
            case "MONTH" -> "每月";
            case "EVENT" -> "每次";
            case "ONCE" -> "一次";
            default -> "待核";
        };
    }

    private static String basisLabel(String value) {
        return "CONTRACT_AMOUNT".equals(value) ? "合同金额" : "待核";
    }

    private static String dateLabel(String type) {
        return switch (type) {
            case "SIGNING_DATE" -> "签约日期";
            case "EFFECTIVE_DATE" -> "生效日期";
            case "PERFORMANCE_START_DATE" -> "履约开始日期";
            case "DELIVERY_DEADLINE" -> "交付日期";
            case "TERMINATION_DATE" -> "终止日期";
            default -> "条款日期";
        };
    }

    private static DeclaredSource source(UUID fileId, String excerpt) {
        return new DeclaredSource(fileId, excerpt);
    }

    private static void addSeparateSource(List<DeclaredSource> sources, UUID fileId, String excerpt) {
        if (excerpt == null || excerpt.isBlank()) return;
        if (sources.stream().noneMatch(existing -> existing.fileId().equals(fileId)
                && existing.excerpt().contains(excerpt))) {
            sources.add(source(fileId, excerpt));
        }
    }

    private final class MappingOutput {
        private final Map<UUID, FrozenReviewMaterial> materials = new LinkedHashMap<>();
        private final List<ContractReviewFinding> findings = new ArrayList<>();
        private final List<ContractReviewEvidence> evidence = new ArrayList<>();
        private final List<ContractReviewClauseComparison> typedComparisons = new ArrayList<>();

        private MappingOutput(List<FrozenReviewMaterial> selected) {
            for (FrozenReviewMaterial material : selected) {
                if (materials.putIfAbsent(material.fileId(), material) != null) throw conflict();
            }
        }

        private int nextFindingOrdinal() {
            return findings.size() + 1;
        }

        private void add(ContractReviewFinding finding, List<DeclaredSource> sources,
                String evidenceRole) {
            findings.add(finding);
            int evidenceOrdinal = 1;
            for (DeclaredSource source : sources) {
                FrozenReviewMaterial material = materials.get(source.fileId());
                if (material == null) throw conflict();
                FrozenEvidenceGrounder.GroundedEvidence grounded =
                        grounder.ground(material, source.excerpt(), true);
                evidence.add(evidence(finding, material, grounded, evidenceOrdinal++, evidenceRole));
            }
        }

        private void addComparison(ContractReviewFinding finding, ClauseComparison comparison,
                List<DeclaredSource> sources, String evidenceRole) {
            add(finding, sources, evidenceRole);
            if (comparison.typedSides().isEmpty()) return;
            Map<UUID, List<ContractReviewEvidence>> byFile = new LinkedHashMap<>();
            evidence.stream().filter(item -> item.findingId().equals(finding.findingId()))
                    .forEach(item -> byFile.computeIfAbsent(item.fileId(), ignored -> new ArrayList<>()).add(item));
            UUID comparisonId = stableId("typed-comparison-v2", finding.findingId().toString(),
                    comparison.key());
            List<ContractReviewClauseComparison.Value> values = new ArrayList<>();
            for (ClauseComparison.TypedSide side : comparison.typedSides()) {
                boolean grounded = "KNOWN".equals(side.state()) || "CONFLICT".equals(side.state());
                List<ContractReviewEvidence> owned = byFile.getOrDefault(side.fileId(), List.of());
                if (grounded && owned.isEmpty()) throw conflict();
                UUID valueId = stableId("typed-side-v2", comparisonId.toString(), side.sideKey());
                values.add(new ContractReviewClauseComparison.Value(valueId, comparisonId, finding.runId(),
                        side.sideKey(), side.fileId(), side.state(), side.scenarioKey(),
                        side.responsibleParty(), side.calculationBasis(), side.rate(), side.rateUnit(),
                        side.frequency(), side.period(), side.capRate(), side.capAmount(),
                        side.terminationThreshold(), side.dateType(), side.normalizedDate(),
                        side.termStart(), side.duration(), side.effectiveCondition(),
                        side.terminationCondition(), grounded
                                ? owned.stream().map(ContractReviewEvidence::evidenceId).toList() : List.of()));
            }
            boolean allKnown = values.stream().allMatch(value -> "KNOWN".equals(value.state()));
            String result = values.stream().anyMatch(value -> "CONFLICT".equals(value.state()))
                    ? "CONFLICT" : !allKnown ? "NOT_EVALUATED"
                    : "REVIEW".equals(comparison.status()) ? "DIFFERENT"
                    : "NORMAL".equals(comparison.status()) ? "MATCH" : "NOT_EVALUATED";
            typedComparisons.add(new ContractReviewClauseComparison(comparisonId,
                    finding.findingId(), finding.runId(), finding.stepId(), finding.attemptId(),
                    comparison.key(), finding.category(), result, 2, values));
        }

        private ContractReviewEvidence evidence(ContractReviewFinding finding,
                FrozenReviewMaterial material, FrozenEvidenceGrounder.GroundedEvidence grounded,
                int ordinal, String evidenceRole) {
            Instant now = clock.instant();
            return new ContractReviewEvidence(UUID.randomUUID(), finding.findingId(),
                    finding.runId(), finding.stepId(), finding.attemptId(), ordinal,
                    material.fileId(), material.fileSha256(), material.parseResultId(),
                    material.parseVersion(), material.canonicalTextSha256(), evidenceRole,
                    "VALIDATED", VALIDATION_POLICY_VERSION, null, null, null, null, null, null,
                    grounded.locatorAvailable(), grounded.startOffset(), grounded.endOffset(),
                    grounded.excerptSha256(), grounded.boundedExcerpt(), LOCATOR_SCHEMA_VERSION,
                    now, 0);
        }

        private StepComputationResult result(ReviewExecutionContext context) {
            return new StepComputationResult(OUTPUT_RESOURCE_TYPE,
                    context.startedAttempt().attemptId(), findings, evidence, typedComparisons,
                    null, null, null);
        }
    }

    private static UUID stableId(String namespace, String... values) {
        return UUID.nameUUIDFromBytes((namespace + "\u0000" + String.join("\u0000", values))
                .getBytes(StandardCharsets.UTF_8));
    }

    private record DeclaredSource(UUID fileId, String excerpt) {
    }

    private static BusinessException conflict() {
        return new BusinessException(BusinessErrorCode.CONFLICT,
                "clause analyzer source is outside the frozen review input");
    }
}
