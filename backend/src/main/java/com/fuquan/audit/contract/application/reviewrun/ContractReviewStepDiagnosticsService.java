package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.CallOutcome;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.CallPhase;
import com.fuquan.audit.contract.application.project.factai.ContractModelCallAudit;
import com.fuquan.audit.contract.application.project.factai.ContractModelCallAuditRepository;
import com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointRepository;
import com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes.Plan;
import com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes.Purpose;
import com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes.Unit;
import com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes.UnitStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import com.fuquan.audit.contract.domain.reviewrun.ReviewStepExternalCallState;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only projection of already durable, credential-free ReviewStep diagnostics. */
@Service
public class ContractReviewStepDiagnosticsService {
    private static final Comparator<ContractModelCallAudit> CALL_ORDER = Comparator
            .comparingInt(ContractModelCallAudit::callOrdinal)
            .thenComparing(value -> value.callAuditId().toString());
    private static final Set<String> SAFE_CODES = Set.of(
            "SEMANTIC_UNAVAILABLE", "SEMANTIC_RESPONSE_INVALID", "SEMANTIC_OUTCOME_UNKNOWN",
            "SEMANTIC_MODEL_INVALID_RESULT", "SEMANTIC_MODEL_GATEWAY_ERROR",
            "SEMANTIC_TOTAL_BUDGET_EXHAUSTED", "SEMANTIC_MODEL_NOT_CONFIGURED",
            "SEMANTIC_INPUT_TOO_LARGE", "SEMANTIC_INPUT_INVALID", "SEMANTIC_CHECKPOINT_OUTPUT_BOUND",
            "SEMANTIC_SYNTHESIS_INPUT_BOUND", "SEMANTIC_LOCAL_BOUNDARY_STOPPED",
            "READ_TIMEOUT", "CONNECT_TIMEOUT", "CONNECT_ERROR", "PROVIDER_429",
            "OUTPUT_TOKEN_LIMIT", "TRANSPORT_OUTCOME_UNKNOWN", "TOTAL_BUDGET_EXCEEDED", "MODEL_TIMEOUT_UNKNOWN",
            "PROVIDER_4XX", "PROVIDER_5XX", "INVALID_PROVIDER_RESPONSE",
            "INVALID_PROVIDER_JSON", "MISSING_REQUIRED_FIELD", "TYPE_MISMATCH",
            "ENUM_VALUE_INVALID", "EXTRA_PROPERTY", "INVALID_TYPED_SCHEMA",
            "SCHEMA_INVALID", "CORRECTION_SCHEMA_INVALID", "INVALID_CORRECTION_SCHEMA",
            "CATEGORY_SET_MISMATCH", "REQUIRED_CATEGORY_MISSING",
            "MISSING_REQUIRED_CATEGORY", "MISSING_REQUIRED_BREACH_CATEGORY",
            "CORRECTION_CATEGORY_SET_MISMATCH", "UNKNOWN_EVIDENCE_ID",
            "INVALID_EVIDENCE_REF", "NON_CITABLE_EVIDENCE", "INVALID_EVIDENCE_ROLE",
            "EVIDENCE_INVALID", "CORRECTION_EVIDENCE_INVALID",
            "CORRECTION_REPAIR_TARGET_MISMATCH",
            "MISSING_OR_UNVERIFIED_PROBLEM_EVIDENCE", "EXCERPT_NOT_FOUND",
            "EXCERPT_AMBIGUOUS", "EVIDENCE_NOT_GROUNDED", "UNPROCESSABLE_RESULT",
            "UNPROCESSABLE_TOP_LEVEL", "POLICY_REJECTED", "SEMANTIC_INVALID",
            "CORRECTION_SEMANTIC_INVALID", "CORRECTION_UNPROCESSABLE_RESULT",
            "MATERIAL_OUTSIDE_FROZEN_RUN", "INVALID_FILE_REFERENCE",
            "FROZEN_REFERENCE_MISMATCH");
    private static final Set<String> SAFE_FINISH_REASONS = Set.of(
            "STOP", "LENGTH", "CONTENT_FILTER", "TOOL_CALLS", "UNKNOWN");
    private static final Set<String> UNKNOWN_CALL_REASONS = Set.of(
            "READ_TIMEOUT", "TRANSPORT_OUTCOME_UNKNOWN", "TOTAL_BUDGET_EXCEEDED", "MODEL_TIMEOUT_UNKNOWN");

    private final ReviewStepOutputReadService outputs;
    private final ContractModelCallAuditRepository modelCalls;
    private final SemanticCheckpointRepository checkpoints;

    public ContractReviewStepDiagnosticsService(ReviewStepOutputReadService outputs,
            ContractModelCallAuditRepository modelCalls) {
        this(outputs, modelCalls, null);
    }

    @Autowired
    public ContractReviewStepDiagnosticsService(ReviewStepOutputReadService outputs,
            ContractModelCallAuditRepository modelCalls, @Nullable SemanticCheckpointRepository checkpoints) {
        this.outputs = outputs;
        this.modelCalls = modelCalls;
        this.checkpoints = checkpoints;
    }

    @Transactional(readOnly = true)
    public Diagnostics get(UUID projectId, UUID contractId, UUID runId, UUID stepId,
            String actorId) {
        ReviewStepOutputReadService.StepOutput output = outputs.getStep(
                projectId, contractId, runId, stepId);
        if (!output.run().requestedBy().equals(actorId)) {
            throw new BusinessException(BusinessErrorCode.NOT_FOUND);
        }
        var step = output.step();
        var attempt = output.attempts().stream()
                .filter(value -> value.attemptNo() == step.currentAttemptNo())
                .reduce((left, right) -> { throw conflict(); })
                .orElseThrow(ContractReviewStepDiagnosticsService::conflict);
        List<ContractModelCallAudit> durable = modelCalls
                .findByReviewStepAttempt(attempt.attemptId()).stream().sorted(CALL_ORDER).toList();
        boolean checkpointPolicy = step.stepType() == ContractReviewStepType.SEMANTIC_AI
                && (ContractReviewExecutionPolicyResolver.isDurableModelPolicy(output.run().modelPolicyVersion())
                    || ContractReviewExecutionPolicyResolver.HISTORICAL_DURABLE_MODEL_POLICY_VERSION.equals(output.run().modelPolicyVersion()));
        validate(attempt.attemptId(), attempt.attemptNo(),
                attempt.externalRequestFingerprint(), attempt.provider(), attempt.model(), durable, !checkpointPolicy);
        Set<UUID> unknownCalls = checkpointPolicy ? validateCheckpointCalls(output, attempt, durable) : Set.of();
        List<ModelCallDiagnostic> calls = durable.stream()
                .map(call -> ModelCallDiagnostic.from(call, unknownCalls.contains(call.callAuditId()))).toList();
        String failureReason = durable.stream()
                .filter(value -> value.outcome() == CallOutcome.FAILED && value.safeReason() != null)
                .reduce((left, right) -> right).map(ContractModelCallAudit::safeReason)
                .orElseGet(() -> firstSafe(attempt.safeReasonCode(), step.safeReasonCode()));
        // A call rejected before a successful correction is still an audit fact,
        // but cannot replace a later proven local terminal failure. Keep the
        // historical call list intact and project the actual Step close reason.
        String terminalReason = firstSafe(attempt.safeReasonCode(), step.safeReasonCode());
        if ((attempt.status() == ContractReviewStepStatus.FAILED || attempt.status() == ContractReviewStepStatus.UNKNOWN)
                && Set.of("SEMANTIC_SYNTHESIS_INPUT_BOUND",
                          "SEMANTIC_LOCAL_BOUNDARY_STOPPED").contains(terminalReason == null ? "" : terminalReason)) {
            failureReason = terminalReason;
        }
        FailureStage stage = classify(failureReason);
        String provider = attempt.provider() != null ? attempt.provider()
                : durable.stream().findFirst().map(ContractModelCallAudit::provider).orElse(null);
        String model = attempt.model() != null ? attempt.model()
                : durable.stream().findFirst().map(ContractModelCallAudit::modelName).orElse(null);
        return new Diagnostics(output.run().runId(), step.stepId(), step.stepType(), step.status(),
                safe(step.safeReasonCode()), attempt.attemptId(), attempt.attemptNo(), attempt.status(),
                safe(attempt.safeReasonCode()), attempt.externalCallState(),
                attempt.externalRequestFingerprint(), provider, model, calls, stage,
                failureReason == null ? null : safe(failureReason));
    }

    private static void validate(UUID attemptId, int attemptNo, String fingerprint,
            String provider, String model, List<ContractModelCallAudit> calls, boolean wholeRequestFingerprint) {
        Set<Integer> ordinals = new HashSet<>();
        for (ContractModelCallAudit call : calls) {
            if (!attemptId.equals(call.reviewStepAttemptId())
                    || call.factExtractionAttemptId() != null || call.attemptNo() != attemptNo
                    || call.scope() != null || call.batchStatus() != null
                    || (wholeRequestFingerprint && !Objects.equals(fingerprint, call.requestFingerprint()))
                    || !ordinals.add(call.callOrdinal())
                    || (provider != null && !provider.equals(call.provider()))
                    || (model != null && !model.equals(call.modelName()))) throw conflict();
        }
        if (calls.stream().map(ContractModelCallAudit::provider).distinct().count() > 1
                || calls.stream().map(ContractModelCallAudit::modelName).distinct().count() > 1) {
            throw conflict();
        }
    }

    /** Reads the actual dispatch ledger; never claims/replays a unit or copies an old Attempt's audit. */
    private Set<UUID> validateCheckpointCalls(ReviewStepOutputReadService.StepOutput output,
            ContractReviewStepAttempt attempt, List<ContractModelCallAudit> calls) {
        if (checkpoints == null || output.step().stepType() != ContractReviewStepType.SEMANTIC_AI
                || !output.run().modelPolicyVersion().equals(output.step().stepPolicyVersion())) throw conflict();
        Plan plan = checkpoints.findPlanByStep(output.step().stepId()).orElse(null);
        if (plan == null) {
            if (calls.isEmpty() && attempt.externalCallState() == ReviewStepExternalCallState.NOT_STARTED) return Set.of();
            throw conflict();
        }
        if (!plan.runId().equals(output.run().runId()) || !plan.stepId().equals(output.step().stepId())) throw conflict();
        Set<UUID> attemptIds = new HashSet<>();
        for (var owner : output.attempts()) {
            if (!owner.runId().equals(plan.runId()) || !owner.stepId().equals(plan.stepId())
                    || !attemptIds.add(owner.attemptId())) throw conflict();
        }
        if (!attemptIds.contains(plan.originalAttemptId()) || !attemptIds.contains(plan.currentAttemptId())) throw conflict();
        var currentCalls = new HashMap<UUID, Unit>();
        Set<UUID> executions = new HashSet<>(), auditIds = new HashSet<>();
        Set<String> executionKeys = new HashSet<>(), ordinals = new HashSet<>();
        List<Unit> units = checkpoints.findUnits(plan.planId());
        if (units == null || units.isEmpty()) throw conflict();
        for (Unit unit : units) {
            if (unit == null || !unit.planId().equals(plan.planId()) || !attemptIds.contains(unit.attemptId())
                    || !executions.add(unit.executionId())
                    || !executionKeys.add(unit.logicalKey() + ":" + unit.executionNo())) throw conflict();
            if (unit.status() == UnitStatus.PLANNED || unit.status() == UnitStatus.CLAIMED) {
                if (unit.call() != null || unit.callOrdinal() != 0) throw conflict();
                continue;
            }
            if (unit.call() == null || !auditIds.add(unit.call().callAuditId())
                    || !ordinals.add(unit.attemptId() + ":" + unit.callOrdinal())) throw conflict();
            if (unit.attemptId().equals(attempt.attemptId())) {
                if (attempt.externalRequestFingerprint() == null
                        || !attempt.externalRequestFingerprint().matches("[0-9a-f]{64}")
                        || attempt.externalCallState() == ReviewStepExternalCallState.NOT_STARTED
                        || ((unit.status() == UnitStatus.SENT || unit.status() == UnitStatus.UNKNOWN)
                            && attempt.externalCallState() == ReviewStepExternalCallState.COMPLETED)) throw conflict();
                currentCalls.put(unit.call().callAuditId(), unit);
            }
        }
        Set<UUID> unknown = new HashSet<>();
        for (ContractModelCallAudit call : calls) {
            Unit unit = currentCalls.remove(call.callAuditId());
            if (unit == null || unit.status() == UnitStatus.SENT
                    || !unit.call().requestFingerprint().equals(call.requestFingerprint())
                    || !unit.call().provider().equals(call.provider()) || !unit.call().model().equals(call.modelName())
                    || unit.callOrdinal() != call.callOrdinal() || unit.durationMs() != call.durationMs()
                    || !Objects.equals(unit.safeReason(), call.safeReason())
                    || call.phase() != (unit.purpose() == Purpose.REPAIR ? CallPhase.REPAIR : CallPhase.TRANSPORT)) throw conflict();
            if (unit.status() == UnitStatus.UNKNOWN) {
                if (call.outcome() != CallOutcome.FAILED || call.safeReason() == null
                        || !UNKNOWN_CALL_REASONS.contains(call.safeReason())) throw conflict();
                unknown.add(call.callAuditId());
            } else if (unit.status() == UnitStatus.VERIFIED) {
                if (call.outcome() != CallOutcome.SUCCEEDED || call.safeReason() != null) throw conflict();
            } else if (unit.status() == UnitStatus.FAILED || unit.status() == UnitStatus.REPAIR_REQUIRED) {
                if (call.outcome() != CallOutcome.FAILED || call.safeReason() == null
                        || UNKNOWN_CALL_REASONS.contains(call.safeReason())) throw conflict();
            } else throw conflict();
        }
        // A SENT row has no completion audit yet. Every terminal current-Attempt dispatch must have exactly one.
        if (currentCalls.values().stream().anyMatch(unit -> unit.status() != UnitStatus.SENT)) throw conflict();
        return Set.copyOf(unknown);
    }

    private static String firstSafe(String first, String second) {
        String value = safe(first);
        return value != null ? value : safe(second);
    }

    private static String safe(String value) {
        if (value == null) return null;
        return value.matches("[A-Z0-9_]{1,64}") && SAFE_CODES.contains(value) ? value : "UNKNOWN";
    }

    static FailureStage classify(String reason) {
        if (reason == null) return FailureStage.UNKNOWN;
        return switch (reason) {
            case "OUTPUT_TOKEN_LIMIT" -> FailureStage.PROVIDER_GENERATION;
            case "READ_TIMEOUT", "CONNECT_TIMEOUT", "CONNECT_ERROR", "PROVIDER_429",
                    "PROVIDER_4XX", "PROVIDER_5XX", "INVALID_PROVIDER_RESPONSE" ->
                    FailureStage.PROVIDER_TRANSPORT;
            case "INVALID_PROVIDER_JSON" -> FailureStage.JSON_PARSE;
            case "MISSING_REQUIRED_FIELD", "TYPE_MISMATCH", "ENUM_VALUE_INVALID",
                    "EXTRA_PROPERTY", "INVALID_TYPED_SCHEMA", "SCHEMA_INVALID",
                    "CORRECTION_SCHEMA_INVALID", "INVALID_CORRECTION_SCHEMA" ->
                    FailureStage.TYPED_SCHEMA;
            case "CATEGORY_SET_MISMATCH", "REQUIRED_CATEGORY_MISSING",
                    "MISSING_REQUIRED_CATEGORY", "MISSING_REQUIRED_BREACH_CATEGORY",
                    "CORRECTION_CATEGORY_SET_MISMATCH" ->
                    FailureStage.COMPLETENESS;
            case "UNKNOWN_EVIDENCE_ID", "INVALID_EVIDENCE_REF", "NON_CITABLE_EVIDENCE",
                    "INVALID_EVIDENCE_ROLE", "EVIDENCE_INVALID",
                    "CORRECTION_EVIDENCE_INVALID", "CORRECTION_REPAIR_TARGET_MISMATCH",
                    "MISSING_OR_UNVERIFIED_PROBLEM_EVIDENCE" ->
                    FailureStage.EVIDENCE_REFERENCE;
            case "EXCERPT_NOT_FOUND", "EXCERPT_AMBIGUOUS", "EVIDENCE_NOT_GROUNDED" ->
                    FailureStage.EVIDENCE_GROUNDING;
            case "UNPROCESSABLE_RESULT", "UNPROCESSABLE_TOP_LEVEL", "POLICY_REJECTED",
                    "SEMANTIC_INVALID", "CORRECTION_SEMANTIC_INVALID",
                    "CORRECTION_UNPROCESSABLE_RESULT", "SEMANTIC_CHECKPOINT_OUTPUT_BOUND",
                    "SEMANTIC_SYNTHESIS_INPUT_BOUND" -> FailureStage.BUSINESS_VALIDATION;
            case "MATERIAL_OUTSIDE_FROZEN_RUN", "INVALID_FILE_REFERENCE",
                    "FROZEN_REFERENCE_MISMATCH", "SEMANTIC_LOCAL_BOUNDARY_STOPPED" -> FailureStage.JAVA_VALIDATION;
            default -> FailureStage.UNKNOWN;
        };
    }

    private static BusinessException conflict() {
        return new BusinessException(BusinessErrorCode.CONFLICT,
                "review step diagnostics cannot be verified");
    }

    public enum FailureStage {
        PROVIDER_TRANSPORT, PROVIDER_GENERATION, JSON_PARSE, TYPED_SCHEMA, COMPLETENESS, EVIDENCE_REFERENCE,
        EVIDENCE_GROUNDING, BUSINESS_VALIDATION, JAVA_VALIDATION, UNKNOWN
    }

    public enum DiagnosticCallOutcome { SUCCEEDED, FAILED, UNKNOWN }

    public record ModelCallDiagnostic(int callOrdinal, CallPhase phase, DiagnosticCallOutcome outcome,
            String safeReason, long durationMs, String finishReason, Integer promptTokens,
            Integer completionTokens, Integer totalTokens) {
        public ModelCallDiagnostic(int callOrdinal, CallPhase phase, CallOutcome outcome,
                String safeReason, long durationMs, String finishReason, Integer promptTokens,
                Integer completionTokens, Integer totalTokens) {
            this(callOrdinal, phase, DiagnosticCallOutcome.valueOf(outcome.name()), safeReason, durationMs,
                    finishReason, promptTokens, completionTokens, totalTokens);
        }

        static ModelCallDiagnostic from(ContractModelCallAudit call, boolean unknown) {
            DiagnosticCallOutcome outcome = unknown ? DiagnosticCallOutcome.UNKNOWN
                    : DiagnosticCallOutcome.valueOf(call.outcome().name());
            return new ModelCallDiagnostic(call.callOrdinal(), call.phase(), outcome,
                    safe(call.safeReason()), call.durationMs(), safeFinish(call.finishReason()), call.promptTokens(),
                    call.completionTokens(), call.totalTokens());
        }

        private static String safeFinish(String value) {
            if (value == null) return null;
            return SAFE_FINISH_REASONS.contains(value) ? value : "UNKNOWN";
        }
    }

    public record Diagnostics(UUID runId, UUID stepId, ContractReviewStepType stepType,
            ContractReviewStepStatus stepStatus, String stepSafeReasonCode, UUID attemptId,
            int attemptNo, ContractReviewStepStatus attemptStatus, String attemptSafeReasonCode,
            ReviewStepExternalCallState externalCallState, String externalRequestFingerprint,
            String provider, String model, List<ModelCallDiagnostic> modelCalls,
            FailureStage failureStage, String failureReasonCode) {
        public Diagnostics { modelCalls = List.copyOf(modelCalls); }
    }
}
