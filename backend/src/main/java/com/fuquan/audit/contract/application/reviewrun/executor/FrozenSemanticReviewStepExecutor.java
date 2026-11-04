package com.fuquan.audit.contract.application.reviewrun.executor;

import com.fuquan.audit.common.exception.ExternalServiceException;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisPort;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisFailure;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisResult;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.CallOutcome;
import com.fuquan.audit.contract.application.project.factai.ContractFactCandidatePort.CallPhase;
import com.fuquan.audit.contract.application.project.factai.ContractModelCallAudit;
import com.fuquan.audit.contract.application.reviewrun.FrozenEvidenceGrounder;
import com.fuquan.audit.contract.application.reviewrun.FrozenReviewInput;
import com.fuquan.audit.contract.application.reviewrun.FrozenReviewMaterial;
import com.fuquan.audit.contract.application.reviewrun.ReviewExecutionContext;
import com.fuquan.audit.contract.application.reviewrun.ReviewStepExecutor;
import com.fuquan.audit.contract.application.reviewrun.ReviewStepOutcomeUnknownException;
import com.fuquan.audit.contract.application.reviewrun.ReviewStepProvenFailureException;
import com.fuquan.audit.contract.application.reviewrun.StepComputationResult;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewEvidence;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewFinding;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import com.fuquan.audit.contract.domain.reviewrun.ReviewFindingSource;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

/** Executes the model-assisted semantic step solely against frozen, redacted run material. */
@Component
public final class FrozenSemanticReviewStepExecutor implements ReviewStepExecutor {
    private static final String OUTPUT_TYPE = "REVIEW_FINDING_SET";
    private static final String FINDING_TYPE = "SEMANTIC_HINT";
    private static final String MODEL_SCHEMA_VERSION = "contract-semantic-result-v1";
    private static final String SEMANTIC_UNAVAILABLE = "SEMANTIC_UNAVAILABLE";
    private static final String SEMANTIC_INPUT_INVALID = "SEMANTIC_INPUT_INVALID";
    private static final String SEMANTIC_RESPONSE_INVALID = "SEMANTIC_RESPONSE_INVALID";
    private static final String SEMANTIC_OUTCOME_UNKNOWN = "SEMANTIC_OUTCOME_UNKNOWN";

    private final ContractTextAnalysisPort analysis;
    private final FrozenSemanticRequestBuilder requests;
    private final FrozenEvidenceGrounder grounder;
    private final Clock clock;
    private final com.fuquan.audit.contract.application.reviewrun.semantic.DurableSemanticExecution durable;

    public FrozenSemanticReviewStepExecutor(ContractTextAnalysisPort analysis,
            FrozenSemanticRequestBuilder requests, FrozenEvidenceGrounder grounder, Clock clock) {
        this(analysis,requests,grounder,clock,null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FrozenSemanticReviewStepExecutor(ContractTextAnalysisPort analysis,
            FrozenSemanticRequestBuilder requests, FrozenEvidenceGrounder grounder, Clock clock,
            @org.springframework.lang.Nullable com.fuquan.audit.contract.application.reviewrun.semantic.DurableSemanticExecution durable) {
        this.analysis = Objects.requireNonNull(analysis);
        this.requests = Objects.requireNonNull(requests);
        this.grounder = Objects.requireNonNull(grounder);
        this.clock = Objects.requireNonNull(clock);
        this.durable=durable;
    }

    @Override
    public ContractReviewStepType stepType() {
        return ContractReviewStepType.SEMANTIC_AI;
    }

    @Override
    public StepComputationResult execute(FrozenReviewInput input, ReviewExecutionContext context) {
        FrozenSemanticRequestBuilder.BuiltRequest built;
        try {
            built = requests.build(input, context);
        } catch (IllegalArgumentException invalid) {
            throw new ReviewStepProvenFailureException(SEMANTIC_INPUT_INVALID, invalid);
        }
        if (com.fuquan.audit.contract.application.reviewrun.ContractReviewExecutionPolicyResolver
                .isDurableModelPolicy(context.run().modelPolicyVersion())) {
            if (durable==null) throw new ReviewStepProvenFailureException("SEMANTIC_CHECKPOINTS_UNAVAILABLE",
                    new IllegalStateException("durable semantic policy requires its checkpoint service"));
            ContractTextAnalysisResult result;
            try {
                result=durable.execute(built.request(),new com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes.Parent(
                        context.run().runId(),context.step().stepId(),context.startedAttempt().attemptId(),
                        context.startedAttempt().leaseOwner()),context.run().inputSnapshotIdentity(),() -> {
                    var state=context.startedAttempt().externalCallState();
                    if (state==com.fuquan.audit.contract.domain.reviewrun.ReviewStepExternalCallState.NOT_STARTED)
                        context.markExternalRequested(built.requestFingerprint());
                    else if (state!=com.fuquan.audit.contract.domain.reviewrun.ReviewStepExternalCallState.REQUESTED
                            || !built.requestFingerprint().equals(context.startedAttempt().externalRequestFingerprint()))
                        throw new IllegalStateException("semantic outbound identity does not match frozen Attempt");
                });
            } catch (ReviewStepProvenFailureException | ReviewStepOutcomeUnknownException stopped) { throw stopped; }
            catch (RuntimeException invalid) {
                if (context.startedAttempt().externalCallState()==com.fuquan.audit.contract.domain.reviewrun.ReviewStepExternalCallState.REQUESTED)
                    throw new ReviewStepOutcomeUnknownException("SEMANTIC_DURABLE_STATE_UNPROVEN",
                            new IllegalStateException("semantic durable state is unavailable"));
                throw new ReviewStepProvenFailureException("SEMANTIC_CHECKPOINT_INVALID",new IllegalStateException("semantic checkpoint execution failed closed"));
            }
            if (result==null || !result.available()) throw new ReviewStepProvenFailureException(SEMANTIC_RESPONSE_INVALID,
                    new IllegalStateException("verified final semantic result is missing"));
            return map(input,context,result,List.of());
        }
        context.markExternalRequested(built.requestFingerprint());

        ContractTextAnalysisResult result;
        try {
            result = analysis.analyzeContractText(built.request());
        } catch (ExternalServiceException failure) {
            List<ContractModelCallAudit> calls = modelCalls(
                    context, built.requestFingerprint(), failure.getCause(), clock.instant());
            if (uncertainEscapedTransport(failure.getCause())) {
                throw new ReviewStepOutcomeUnknownException(SEMANTIC_OUTCOME_UNKNOWN, failure, calls);
            }
            String classified = classifiedFailure(failure.getCause());
            if (calls.stream().anyMatch(call -> "TRANSPORT_OUTCOME_UNKNOWN".equals(call.safeReason())
                    || "READ_TIMEOUT".equals(call.safeReason()))) {
                throw new ReviewStepOutcomeUnknownException(
                        classified == null ? SEMANTIC_OUTCOME_UNKNOWN : classified, failure, calls);
            }
            if (classified != null) {
                throw new ReviewStepProvenFailureException(classified, failure, calls);
            }
            if (invalidTypedResponse(failure.getCause())) {
                throw new ReviewStepProvenFailureException(SEMANTIC_RESPONSE_INVALID, failure, calls);
            }
            throw new ReviewStepProvenFailureException(SEMANTIC_UNAVAILABLE, failure, calls);
        } catch (RuntimeException invalid) {
            throw new ReviewStepProvenFailureException(SEMANTIC_RESPONSE_INVALID, invalid);
        }
        if (result == null) {
            throw new ReviewStepProvenFailureException(SEMANTIC_RESPONSE_INVALID,
                    new IllegalStateException("semantic result is missing"));
        }
        List<ContractModelCallAudit> observedCalls = modelCalls(
                context, built.requestFingerprint(), result.modelCalls(), clock.instant());
        if (!result.available()) {
            throw new ReviewStepProvenFailureException(SEMANTIC_UNAVAILABLE,
                    new IllegalStateException("semantic analysis is unavailable"), observedCalls);
        }
        try {
            return map(input, context, result, observedCalls);
        } catch (ReviewStepProvenFailureException failure) {
            throw new ReviewStepProvenFailureException(failure.safeReasonCode(), failure, observedCalls);
        } catch (RuntimeException invalid) {
            throw new ReviewStepProvenFailureException(SEMANTIC_RESPONSE_INVALID, invalid, observedCalls);
        }
    }

    private StepComputationResult map(FrozenReviewInput input, ReviewExecutionContext context,
            ContractTextAnalysisResult result, List<ContractModelCallAudit> observedCalls) {
        Map<UUID, FrozenReviewMaterial> materialByFileId = new LinkedHashMap<>();
        for (FrozenReviewMaterial material : input.materials()) {
            if (materialByFileId.putIfAbsent(material.fileId(), material) != null) {
                throw invalid("duplicate frozen material file identity");
            }
        }
        List<ContractReviewFinding> findings = new ArrayList<>();
        List<ContractReviewEvidence> evidence = new ArrayList<>();
        Instant createdAt = clock.instant();
        int findingOrdinal = 0;
        for (ContractTextAnalysisResult.Hint hint : result.hints()) {
            validateReferences(hint, materialByFileId);
            UUID findingId = UUID.randomUUID();
            ContractReviewFinding finding = new ContractReviewFinding(findingId,
                    context.run().runId(), context.step().stepId(),
                    context.startedAttempt().attemptId(), ++findingOrdinal,
                    FINDING_TYPE, hint.category().name(), null, hint.attention().name(), null,
                    hint.title(), hint.description(), 1, context.step().stepPolicyVersion(),
                    ReviewFindingSource.MODEL_ASSISTED, null, result.model(), null,
                    null, MODEL_SCHEMA_VERSION, createdAt, 0);
            findings.add(finding);
            int evidenceOrdinal = 0;
            for (ContractTextAnalysisResult.EvidenceItem item : hint.evidenceItems()) {
                if (item.excerpt() == null) {
                    continue;
                }
                FrozenReviewMaterial material = materialByFileId.get(item.fileId());
                var grounded = grounder.ground(material, item.excerpt(), false);
                evidence.add(new ContractReviewEvidence(UUID.randomUUID(), findingId,
                        context.run().runId(), context.step().stepId(),
                        context.startedAttempt().attemptId(), ++evidenceOrdinal,
                        material.fileId(), material.fileSha256(), material.parseResultId(),
                        material.parseVersion(), material.canonicalTextSha256(), "SUPPORT",
                        grounded.locatorAvailable() ? "VALIDATED" : "LOCATOR_UNAVAILABLE",
                        context.step().stepPolicyVersion(), null, null, null, null, null, null,
                        grounded.locatorAvailable(), grounded.startOffset(), grounded.endOffset(),
                        grounded.excerptSha256(), grounded.boundedExcerpt(), 1, createdAt, 0));
            }
        }
        return new StepComputationResult(OUTPUT_TYPE, context.startedAttempt().attemptId(),
                findings, evidence, List.of(), observedCalls, null, null, result.model());
    }

    private static List<ContractModelCallAudit> modelCalls(ReviewExecutionContext context,
            String requestFingerprint, Throwable cause, Instant createdAt) {
        Throwable current = cause;
        while (current != null) {
            if (current instanceof ContractTextAnalysisFailure failure)
                return modelCalls(context, requestFingerprint, failure.modelCalls(), createdAt);
            current = current.getCause();
        }
        return List.of();
    }

    private static List<ContractModelCallAudit> modelCalls(ReviewExecutionContext context,
            String requestFingerprint, List<ContractTextAnalysisResult.ModelCall> calls,
            Instant createdAt) {
        return calls.stream().map(call -> new ContractModelCallAudit(UUID.randomUUID(), null,
                context.startedAttempt().attemptId(), context.startedAttempt().attemptNo(), null,
                call.ordinal(), CallPhase.valueOf(call.phase().name()), requestFingerprint,
                call.provider(), call.model(), CallOutcome.valueOf(call.outcome().name()),
                call.safeReason(), call.durationMs(), call.finishReason(), call.promptTokens(),
                call.completionTokens(), call.totalTokens(), null, createdAt)).toList();
    }

    private static void validateReferences(ContractTextAnalysisResult.Hint hint,
            Map<UUID, FrozenReviewMaterial> materialByFileId) {
        if (hint == null || hint.evidenceRefs().stream().anyMatch(
                reference -> !materialByFileId.containsKey(reference))
                || hint.evidenceItems().stream().anyMatch(
                        item -> !materialByFileId.containsKey(item.fileId())
                                || !hint.evidenceRefs().contains(item.fileId()))) {
            throw invalid("semantic result references material outside the frozen run");
        }
    }

    private static ReviewStepProvenFailureException invalid(String reason) {
        return new ReviewStepProvenFailureException(SEMANTIC_RESPONSE_INVALID,
                new IllegalArgumentException(reason));
    }

    private static boolean uncertainEscapedTransport(Throwable failure) {
        if (!(failure instanceof ResourceAccessException)) {
            return false;
        }
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ConnectException || current instanceof UnknownHostException) {
                return false;
            }
            current = current.getCause();
        }
        return true;
    }

    private static boolean invalidTypedResponse(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof IllegalArgumentException || current instanceof IllegalStateException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String classifiedFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ContractTextAnalysisFailure classified) {
                return switch (classified.reason()) {
                    case MODEL_INVALID_RESULT -> "SEMANTIC_MODEL_INVALID_RESULT";
                    case MODEL_GATEWAY_ERROR -> "SEMANTIC_MODEL_GATEWAY_ERROR";
                    case MODEL_TOTAL_BUDGET_EXHAUSTED -> "SEMANTIC_TOTAL_BUDGET_EXHAUSTED";
                    case MODEL_NOT_CONFIGURED -> "SEMANTIC_MODEL_NOT_CONFIGURED";
                    case MODEL_INPUT_TOO_LARGE -> "SEMANTIC_INPUT_TOO_LARGE";
                    case EMPTY_MODEL_INPUT -> "SEMANTIC_INPUT_INVALID";
                    case SERVICE_UNAVAILABLE -> SEMANTIC_UNAVAILABLE;
                };
            }
            current = current.getCause();
        }
        return null;
    }
}
