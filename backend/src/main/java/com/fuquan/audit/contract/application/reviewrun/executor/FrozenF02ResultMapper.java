package com.fuquan.audit.contract.application.reviewrun.executor;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.FindingSourceProjection;
import com.fuquan.audit.contract.application.FindingSourceProjectionService;
import com.fuquan.audit.contract.application.reviewrun.FrozenEvidenceGrounder;
import com.fuquan.audit.contract.application.reviewrun.FrozenReviewInput;
import com.fuquan.audit.contract.application.reviewrun.FrozenReviewMaterial;
import com.fuquan.audit.contract.application.reviewrun.ReviewExecutionContext;
import com.fuquan.audit.contract.application.reviewrun.F02RulePolicyFingerprint;
import com.fuquan.audit.contract.application.reviewrun.ContractSnapshotCanonicalizer;
import com.fuquan.audit.contract.application.reviewrun.StepComputationResult;
import com.fuquan.audit.contract.domain.F02RuleCodes;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewEvidence;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewFinding;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunMaterial;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.reviewrun.ReviewFindingSource;
import com.fuquan.audit.review.domain.AuditCheckResult;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Maps durable F02 truth into versioned ReviewRun navigation carriers. */
@Component
public final class FrozenF02ResultMapper {
    public static final String OUTPUT_RESOURCE_TYPE = "AUDIT_TASK_F02_NAVIGATION_V2";
    public static final String LEGACY_OUTPUT_RESOURCE_TYPE = "AUDIT_TASK";
    public static final int FINDING_SCHEMA_VERSION = 2;
    public static final int LOCATOR_SCHEMA_VERSION = 1;
    public static final String FINDING_TYPE = "F02_AUDIT_CHECK_RESULT";
    public static final String VALIDATION_POLICY_VERSION = "f02-frozen-evidence-v2";
    public static final String LEGACY_VALIDATION_POLICY_VERSION = "f02-frozen-evidence-v1";
    public static final String SUPPLIER_WITNESS_POLICY_VERSION = F02RulePolicyFingerprint.FROZEN_SOURCE_POLICY;
    private static final Map<String, Integer> RULE_ORDER = ruleOrder();
    private static final Comparator<AuditCheckResult> CHECK_ORDER = Comparator
            .comparingInt((AuditCheckResult value) -> RULE_ORDER.getOrDefault(value.ruleCode(), Integer.MAX_VALUE))
            .thenComparing(AuditCheckResult::itemKey, Comparator.nullsFirst(String::compareTo))
            .thenComparing(value -> value.id().toString());

    private final FindingSourceProjectionService sources;
    private final FrozenEvidenceGrounder grounder;
    private final Clock clock;

    public FrozenF02ResultMapper(FindingSourceProjectionService sources,
            FrozenEvidenceGrounder grounder, Clock clock) {
        this.sources = sources; this.grounder = grounder; this.clock = clock;
    }

    public StepComputationResult map(FrozenReviewInput input, ReviewExecutionContext context,
            UUID taskId, List<AuditCheckResult> checks) {
        Objects.requireNonNull(input); Objects.requireNonNull(context); Objects.requireNonNull(taskId);
        requireSupportedFrozenExecution(input, context);
        List<AuditCheckResult> ordered = List.copyOf(checks).stream().sorted(CHECK_ORDER).toList();
        if (ordered.isEmpty() || ordered.stream().anyMatch(value -> !taskId.equals(value.taskId())
                || !RULE_ORDER.containsKey(value.ruleCode()))) throw conflict();
        Map<UUID, FrozenReviewMaterial> materialByFile = new HashMap<>();
        for (FrozenReviewMaterial material : input.materials()) {
            if (materialByFile.putIfAbsent(material.fileId(), material) != null) throw conflict();
        }
        List<ContractReviewFinding> findings = new ArrayList<>();
        List<ContractReviewEvidence> evidence = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            AuditCheckResult check = ordered.get(index);
            boolean supplier = F02RuleCodes.AWARD_SUPPLIER_MATCH.equals(check.ruleCode())
                    && input.frozenContract().awardNotice() != null
                    && (input.frozenContract().partyBCreditCode() == null || input.frozenContract().partyBCreditCode().isBlank()
                        || input.frozenContract().awardNotice().winningBidderCreditCode() == null
                        || input.frozenContract().awardNotice().winningBidderCreditCode().isBlank());
            FindingSourceProjection projection = sources.projectFrozen(input, check);
            List<GroundedSource> groundedSources = new ArrayList<>();
            String unavailableReason = projection.available() ? null : projection.reason();
            if (!projection.sources().isEmpty()) {
                for (FindingSourceProjection.Source source : projection.sources()) {
                    FrozenReviewMaterial material = materialByFile.get(source.fileId());
                    if (material == null || supplier && (!source.projectId().equals(input.run().projectId())
                            || !("CONTRACT".equals(source.role()) && material.effectiveRole() == DocumentRole.CONTRACT_BODY
                                || "AWARD_NOTICE".equals(source.role()) && material.effectiveRole() == DocumentRole.AWARD_NOTICE))) {
                        unavailableReason = partialReason(groundedSources);
                        continue;
                    }
                    FrozenEvidenceGrounder.GroundedEvidence grounded =
                            grounder.groundDeclared(material, source.excerpt(), source.locator(), false);
                    if (!grounded.locatorAvailable()) {
                        unavailableReason = partialReason(groundedSources);
                        continue;
                    }
                    groundedSources.add(new GroundedSource(source, material, grounded));
                }
                if (unavailableReason != null && !groundedSources.isEmpty()
                        && !"CONFIRMED_VALUE_DIFFERS_FROM_SOURCE".equals(unavailableReason)) {
                    unavailableReason = "PARTIAL_LOCATOR_UNAVAILABLE";
                }
            }
            // A multi-witness supplier certificate is all-or-unavailable, never a conveniently
            // grounded subset presented as complete by navigation consumers.
            if (supplier && unavailableReason != null) groundedSources.clear();
            String attention = unavailableReason == null ? null : unavailableCode(unavailableReason);
            ContractReviewFinding finding = new ContractReviewFinding(findingId(check.id()),
                    context.run().runId(), context.step().stepId(), context.startedAttempt().attemptId(),
                    index + 1, FINDING_TYPE, check.ruleCode(), check.checkStatus().name(), attention,
                    check.riskLevel().name(), check.detail(),
                    "CONFIRMED_VALUE_DIFFERS_FROM_SOURCE".equals(projection.reason())
                            ? confirmedValueDescription(input, check) : null, FINDING_SCHEMA_VERSION,
                    context.step().stepPolicyVersion(), ReviewFindingSource.DETERMINISTIC,
                    null, null, null, null, null, clock.instant(), 0);
            findings.add(finding);
            if (unavailableReason != null && groundedSources.isEmpty()) continue;
            int evidenceOrdinal = 0;
            for (GroundedSource source : groundedSources) {
                int ordinal = ++evidenceOrdinal;
                evidence.add(new ContractReviewEvidence(evidenceId(check.id(), source.material(), ordinal),
                        finding.findingId(), finding.runId(), finding.stepId(), finding.attemptId(), ordinal,
                        source.material().fileId(), source.material().fileSha256(), source.material().parseResultId(),
                        source.material().parseVersion(), source.material().canonicalTextSha256(), "COMPARISON",
                        "VALIDATED", supplier ? SUPPLIER_WITNESS_POLICY_VERSION : VALIDATION_POLICY_VERSION, null,
                        null, null, null, null, null, true,
                        source.grounded().startOffset(), source.grounded().endOffset(),
                        source.grounded().excerptSha256(), source.grounded().boundedExcerpt(),
                        LOCATOR_SCHEMA_VERSION, clock.instant(), 0));
            }
            if (evidenceOrdinal == 0 && unavailableReason == null) throw conflict();
        }
        return new StepComputationResult(OUTPUT_RESOURCE_TYPE, taskId, findings, evidence, null, null, null);
    }

    public static void requireSupportedFrozenExecution(FrozenReviewInput input, ReviewExecutionContext context) {
        var r = input.run(); var c = context.run(); var step = context.step(); var attempt = context.startedAttempt();
        if (!r.ruleVersion().matches("f02-policy-v2:[0-9a-f]{64}")
                || !r.runId().equals(c.runId()) || !r.projectId().equals(c.projectId())
                || !r.contractId().equals(c.contractId()) || r.contractVersion() != c.contractVersion()
                || !Objects.equals(r.sourceCandidateId(), c.sourceCandidateId())
                || !Objects.equals(r.sourceConfirmationId(), c.sourceConfirmationId())
                || r.runSchemaVersion() != c.runSchemaVersion()
                || !r.contractSnapshotCanonicalJson().equals(c.contractSnapshotCanonicalJson())
                || !r.contractSnapshotSha256().equals(c.contractSnapshotSha256())
                || !r.contractSnapshotSha256().equals(ContractSnapshotCanonicalizer.sha256(r.contractSnapshotCanonicalJson()))
                || !r.inputSnapshotIdentity().equals(c.inputSnapshotIdentity())
                || !r.reviewProfileVersion().equals(c.reviewProfileVersion())
                || !r.ruleVersion().equals(c.ruleVersion()) || !r.modelPolicyVersion().equals(c.modelPolicyVersion())
                || !r.requestedAt().equals(c.requestedAt()) || !r.requestedBy().equals(c.requestedBy())
                || !input.frozenContract().id().equals(r.contractId())
                || input.frozenContract().versionNo() != r.contractVersion()
                || r.status() != ContractReviewRunStatus.RUNNING || c.status() != ContractReviewRunStatus.RUNNING
                || !step.runId().equals(r.runId()) || step.stepType() != ContractReviewStepType.F02_DETERMINISTIC
                || step.status() != ContractReviewStepStatus.RUNNING || !step.stepPolicyVersion().equals(r.ruleVersion())
                || !attempt.runId().equals(r.runId()) || !attempt.stepId().equals(step.stepId())
                || attempt.attemptNo() != step.currentAttemptNo() || attempt.status() != ContractReviewStepStatus.RUNNING)
            throw conflict();
    }

    public static UUID findingId(UUID checkResultId) {
        return stableId("f02-finding-v2:" + checkResultId);
    }

    private static String confirmedValueDescription(FrozenReviewInput input, AuditCheckResult check) {
        var contract = input.frozenContract();
        String values = switch (check.ruleCode()) {
            case F02RuleCodes.LINE_ITEM_ARITHMETIC -> contract.lineItems().stream()
                    .filter(line -> Objects.equals(line.itemKey(), check.itemKey()))
                    .map(line -> "明细 " + line.itemKey() + "；数量=" + line.quantity()
                            + "；单价=" + line.unitPrice() + "；声明金额=" + line.declaredAmount())
                    .findFirst().orElse("明细确认值见冻结合同");
            case F02RuleCodes.LINE_SUM_MATCH -> "声明总额=" + contract.contractAmount()
                    + "；参与合计明细=" + contract.lineItems().size() + "行";
            case F02RuleCodes.AWARD_AMOUNT_MATCH -> "合同金额=" + contract.contractAmount()
                    + "；成交金额=" + contract.awardNotice().winningAmount();
            default -> "声明总额=" + contract.contractAmount();
        };
        String origin = input.run().sourceConfirmationId() == null ? "冻结合同"
                : "人工确认记录 " + input.run().sourceConfirmationId();
        return origin + "：" + values + "。确认值与原文值不同；关联证据保留原文值，数值结论使用冻结合同值。";
    }

    public static UUID evidenceId(UUID checkResultId, FrozenReviewMaterial material, int ordinal) {
        return evidenceId(checkResultId, material.fileId(), material.parseResultId(), ordinal);
    }

    public static UUID evidenceId(UUID checkResultId, UUID fileId, UUID parseResultId, int ordinal) {
        return stableId("f02-evidence-v2:" + checkResultId + ":" + fileId
                + ":" + parseResultId + ":" + ordinal);
    }

    /** Verifies the one-to-one truth-carrier mapping without using summaries or array position as identity. */
    public static void requireValidProjection(ContractReviewStep step, UUID taskId,
            List<AuditCheckResult> checks, List<ContractReviewFinding> findings,
            List<ContractReviewEvidence> evidence) {
        requireValidProjection(step, taskId, checks, findings, evidence, null);
    }

    public static void requireValidProjection(ContractReviewStep step, UUID taskId,
            List<AuditCheckResult> checks, List<ContractReviewFinding> findings,
            List<ContractReviewEvidence> evidence, List<ContractReviewRunMaterial> frozenMaterials) {
        List<AuditCheckResult> ordered = List.copyOf(checks).stream().sorted(CHECK_ORDER).toList();
        if (ordered.isEmpty() || findings.size() != ordered.size()) throw conflict();
        Map<UUID, ContractReviewFinding> findingById = new HashMap<>();
        for (ContractReviewFinding finding : findings) {
            if (findingById.putIfAbsent(finding.findingId(), finding) != null) throw conflict();
        }
        for (int index = 0; index < ordered.size(); index++) {
            AuditCheckResult check = ordered.get(index);
            if (!taskId.equals(check.taskId()) || !RULE_ORDER.containsKey(check.ruleCode())) throw conflict();
            ContractReviewFinding finding = findingById.get(findingId(check.id()));
            if (finding == null || finding.ordinal() != index + 1
                    || !FINDING_TYPE.equals(finding.findingType())
                    || !check.ruleCode().equals(finding.category())
                    || !check.checkStatus().name().equals(finding.statusCode())
                    || !check.riskLevel().name().equals(finding.riskLevel())
                    || !check.detail().equals(finding.summary())
                    || finding.findingSchemaVersion() != FINDING_SCHEMA_VERSION
                    || !step.stepPolicyVersion().equals(finding.policyVersion())
                    || finding.source() != ReviewFindingSource.DETERMINISTIC
                    || finding.modelProvider() != null || finding.modelName() != null
                    || finding.modelRevision() != null || finding.promptVersion() != null
                    || finding.modelSchemaVersion() != null) throw conflict();
            List<ContractReviewEvidence> owned = evidence.stream()
                    .filter(value -> value.findingId().equals(finding.findingId()))
                    .sorted(Comparator.comparingInt(ContractReviewEvidence::ordinal)).toList();
            boolean supplierWitnesses = owned.stream().anyMatch(value ->
                    SUPPLIER_WITNESS_POLICY_VERSION.equals(value.validationPolicyVersion()));
            if (supplierWitnesses) {
                if (!F02RuleCodes.AWARD_SUPPLIER_MATCH.equals(check.ruleCode())) throw conflict();
                requireSupplierWitnessCertificate(step, finding, owned, frozenMaterials);
            }
            java.util.Set<Integer> expectedEvidence = expectedEvidenceCounts(check, ordered);
            boolean sourceUnavailable = finding.attentionCode() != null
                    && finding.attentionCode().startsWith("SOURCE_UNAVAILABLE:");
            if (owned.isEmpty()) {
                if (!sourceUnavailable) throw conflict();
                continue;
            }
            if (sourceUnavailable) {
                boolean originalValues = "SOURCE_UNAVAILABLE:CONFIRMED_VALUE_DIFFERS_FROM_SOURCE"
                        .equals(finding.attentionCode()) && owned.stream().allMatch(value ->
                                VALIDATION_POLICY_VERSION.equals(value.validationPolicyVersion()));
                if (!originalValues && owned.size() >= expectedEvidence.stream()
                        .mapToInt(Integer::intValue).max().orElseThrow()
                        || originalValues && !expectedEvidence.contains(owned.size()))
                    throw conflict();
            } else if (finding.attentionCode() != null || !supplierWitnesses && !expectedEvidence.contains(owned.size())) throw conflict();
            for (int ordinal = 1; ordinal <= owned.size(); ordinal++) {
                ContractReviewEvidence item = owned.get(ordinal - 1);
                if (item.ordinal() != ordinal
                        || !item.evidenceId().equals(evidenceId(check.id(), item.fileId(),
                                item.parseResultId(), ordinal))
                        || item.locatorSchemaVersion() != LOCATOR_SCHEMA_VERSION
                        || !java.util.Set.of(VALIDATION_POLICY_VERSION, LEGACY_VALIDATION_POLICY_VERSION, SUPPLIER_WITNESS_POLICY_VERSION)
                                .contains(item.validationPolicyVersion())) throw conflict();
            }
        }
        if (evidence.stream().anyMatch(value -> !findingById.containsKey(value.findingId()))) throw conflict();
    }

    /** Shared structural certificate gate; only frozen supplier-name publishers may write v3. */
    public static void requireSupplierWitnessCertificate(ContractReviewStep step, ContractReviewFinding finding,
            List<ContractReviewEvidence> owned, List<ContractReviewRunMaterial> materials) {
        if (materials == null || owned.isEmpty() || !F02RuleCodes.AWARD_SUPPLIER_MATCH.equals(finding.category())
                || step.stepType() != ContractReviewStepType.F02_DETERMINISTIC
                || !step.stepPolicyVersion().matches("f02-policy-v2:[0-9a-f]{64}")
                || !finding.policyVersion().equals(step.stepPolicyVersion())
                || !finding.runId().equals(step.runId()) || !finding.stepId().equals(step.stepId())
                || finding.attentionCode() != null
                || owned.stream().anyMatch(value -> !SUPPLIER_WITNESS_POLICY_VERSION.equals(value.validationPolicyVersion())))
            throw conflict();
        Map<DocumentRole, ContractReviewRunMaterial> sides = new HashMap<>();
        for (var item : owned) {
            if (!item.runId().equals(finding.runId()) || !item.stepId().equals(step.stepId())
                    || !item.attemptId().equals(finding.attemptId()) || !"COMPARISON".equals(item.evidenceRole())
                    || item.locatorSchemaVersion() != LOCATOR_SCHEMA_VERSION
                    || !item.locatorAvailable() || !"VALIDATED".equals(item.validationStatus())) throw conflict();
            var matches = materials.stream().filter(m -> m.runId().equals(item.runId())
                    && m.fileId().equals(item.fileId()) && m.fileSha256().equals(item.fileSha256())
                    && m.parseResultId().equals(item.parseResultId()) && m.parseVersion() == item.parseVersion()
                    && m.canonicalTextSha256().equals(item.canonicalTextSha256())).toList();
            if (matches.size() != 1) throw conflict();
            var m = matches.get(0);
            if (m.effectiveRole() != DocumentRole.CONTRACT_BODY && m.effectiveRole() != DocumentRole.AWARD_NOTICE)
                throw conflict();
            var previous = sides.putIfAbsent(m.effectiveRole(), m);
            if (previous != null && !previous.equals(m)) throw conflict();
        }
        if (sides.size() != 2 || !sides.get(DocumentRole.CONTRACT_BODY).projectId()
                .equals(sides.get(DocumentRole.AWARD_NOTICE).projectId())) throw conflict();
    }

    private static java.util.Set<Integer> expectedEvidenceCounts(
            AuditCheckResult check, List<AuditCheckResult> checks) {
        return switch (check.ruleCode()) {
            case F02RuleCodes.AMOUNT_WORDS_MATCH -> java.util.Set.of(2);
            // AMOUNT_ONLY has one declared-amount source. A quantitative row has quantity,
            // unit price and declared amount. The check result deliberately does not duplicate basis.
            case F02RuleCodes.LINE_ITEM_ARITHMETIC -> java.util.Set.of(1, 3);
            case F02RuleCodes.LINE_SUM_MATCH -> java.util.Set.of(1 + (int) checks.stream()
                    .filter(value -> F02RuleCodes.LINE_ITEM_ARITHMETIC.equals(value.ruleCode())).count());
            case F02RuleCodes.AWARD_AMOUNT_MATCH, F02RuleCodes.AWARD_SUPPLIER_MATCH -> java.util.Set.of(2);
            default -> throw conflict();
        };
    }

    private static String unavailableCode(String reason) {
        String code = reason == null || !reason.matches("[A-Z0-9_]{1,100}") ? "UNPROVEN" : reason;
        return "SOURCE_UNAVAILABLE:" + code;
    }

    private static String partialReason(List<GroundedSource> groundedSources) {
        return groundedSources.isEmpty() ? "LOCATOR_UNAVAILABLE" : "PARTIAL_LOCATOR_UNAVAILABLE";
    }

    private static Map<String, Integer> ruleOrder() {
        Map<String, Integer> order = new HashMap<>();
        for (int index = 0; index < F02RuleCodes.ALL.size(); index++) order.put(F02RuleCodes.ALL.get(index), index);
        return Map.copyOf(order);
    }

    private static UUID stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static BusinessException conflict() {
        return new BusinessException(BusinessErrorCode.CONFLICT,
                "F02 frozen finding projection cannot be verified");
    }

    private record GroundedSource(FindingSourceProjection.Source source,
            FrozenReviewMaterial material, FrozenEvidenceGrounder.GroundedEvidence grounded) { }
}
