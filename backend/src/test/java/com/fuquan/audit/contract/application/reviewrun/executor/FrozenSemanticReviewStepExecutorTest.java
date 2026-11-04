package com.fuquan.audit.contract.application.reviewrun.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.common.exception.ExternalServiceException;
import com.fuquan.audit.common.exception.ExternalServiceType;
import com.fuquan.audit.contract.application.ai.ContractAiTextRedactor;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisPort;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisFailure;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisResult;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.reviewrun.ContractSnapshotCanonicalizer;
import com.fuquan.audit.contract.application.reviewrun.FrozenEvidenceGrounder;
import com.fuquan.audit.contract.application.reviewrun.FrozenReviewInput;
import com.fuquan.audit.contract.application.reviewrun.FrozenReviewMaterial;
import com.fuquan.audit.contract.application.reviewrun.ReviewExecutionContext;
import com.fuquan.audit.contract.application.reviewrun.ReviewStepOutcomeUnknownException;
import com.fuquan.audit.contract.application.reviewrun.ReviewStepProvenFailureException;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.Money;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import com.fuquan.audit.contract.domain.reviewrun.ReviewFindingSource;
import com.fuquan.audit.contract.domain.reviewrun.ReviewStepExternalCallState;
import com.fuquan.audit.security.domain.CurrentUser;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class FrozenSemanticReviewStepExecutorTest {
    private static final Instant NOW = Instant.parse("2026-09-16T04:00:00Z");
    private final UUID projectId = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private final UUID contractId = UUID.fromString("00000000-0000-0000-0000-000000000302");
    private final UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000303");
    private final UUID stepId = UUID.fromString("00000000-0000-0000-0000-000000000304");
    private final UUID attemptId = UUID.fromString("00000000-0000-0000-0000-000000000305");
    private final RecordingPort port = new RecordingPort();
    private final List<String> requestedFingerprints = new ArrayList<>();
    private ContractReviewRun run;
    private ReviewExecutionContext context;
    private FrozenSemanticReviewStepExecutor executor;

    @Test void durableFrozenPolicyNeverFallsBackToWholeStepLegacyTransport() {
        run=run("contract-semantic-work-v3-redaction-v1-grounding-v1");
        context=context(requestedFingerprints::add);
        var material=material(1,DocumentRole.CONTRACT_BODY,"frozen-clause");
        assertThatThrownBy(() -> executor.execute(input(List.of(material)),context))
                .isInstanceOfSatisfying(ReviewStepProvenFailureException.class,failure ->
                        assertThat(failure.safeReasonCode()).isEqualTo("SEMANTIC_CHECKPOINTS_UNAVAILABLE"));
        assertThat(port.calls).isZero();
        assertThat(requestedFingerprints).isEmpty();
    }

    @Test void durableFrozenPolicyUsesCheckpointServiceAndPublishesNoDuplicateModelCallRows() {
        run=run("contract-semantic-work-v3-redaction-v1-grounding-v1");
        var original=context(requestedFingerprints::add);
        var attempt=org.mockito.Mockito.mock(ContractReviewStepAttempt.class);
        org.mockito.Mockito.when(attempt.runId()).thenReturn(runId);
        org.mockito.Mockito.when(attempt.stepId()).thenReturn(stepId);
        org.mockito.Mockito.when(attempt.attemptId()).thenReturn(attemptId);
        org.mockito.Mockito.when(attempt.operationIdentity()).thenReturn("d".repeat(64));
        org.mockito.Mockito.when(attempt.leaseOwner()).thenReturn("worker-durable");
        org.mockito.Mockito.when(attempt.externalCallState()).thenReturn(ReviewStepExternalCallState.NOT_STARTED);
        context=new ReviewExecutionContext(run,original.step(),attempt,original.actor(),original.metadata(),List.of(),requestedFingerprints::add);
        var durable=org.mockito.Mockito.mock(com.fuquan.audit.contract.application.reviewrun.semantic.DurableSemanticExecution.class);
        org.mockito.Mockito.when(durable.execute(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(run.inputSnapshotIdentity()),org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> { ((Runnable)call.getArgument(3)).run(); return available(List.of()); });
        executor=new FrozenSemanticReviewStepExecutor(port,new FrozenSemanticRequestBuilder(new ContractAiTextRedactor()),
                new FrozenEvidenceGrounder(),Clock.fixed(NOW,ZoneOffset.UTC),durable);
        var result=org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                executor.execute(input(List.of(material(1,DocumentRole.CONTRACT_BODY,"frozen-clause"))),context));
        assertThat(port.calls).isZero();assertThat(requestedFingerprints).hasSize(1);
        assertThat(result.modelCalls()).isEmpty();
    }

    @Test void historicalV2PolicyNeverDispatchesThroughTheCurrentDurableOrLegacyPorts() {
        run=run("contract-semantic-work-v2-redaction-v1-grounding-v1");
        context=context(requestedFingerprints::add);
        var durable=org.mockito.Mockito.mock(com.fuquan.audit.contract.application.reviewrun.semantic.DurableSemanticExecution.class);
        executor=new FrozenSemanticReviewStepExecutor(port,new FrozenSemanticRequestBuilder(new ContractAiTextRedactor()),
                new FrozenEvidenceGrounder(),Clock.fixed(NOW,ZoneOffset.UTC),durable);

        assertThatThrownBy(() -> executor.execute(input(List.of(
                material(1,DocumentRole.CONTRACT_BODY,"preserved historical source"))),context))
                .isInstanceOfSatisfying(ReviewStepProvenFailureException.class,failure ->
                        assertThat(failure.safeReasonCode()).isEqualTo("SEMANTIC_INPUT_INVALID"));

        assertThat(port.calls).isZero();
        assertThat(requestedFingerprints).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(durable);
    }

    @BeforeEach
    void setUp() {
        run = run();
        context = context(requestedFingerprints::add);
        FrozenSemanticRequestBuilder requests =
                new FrozenSemanticRequestBuilder(new ContractAiTextRedactor());
        executor = new FrozenSemanticReviewStepExecutor(
                port, requests, new FrozenEvidenceGrounder(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void persistsTypedHintAndUniqueRawGroundingWithOnlyProvenModelName() {
        String excerpt = "\u552f\u4e00\u4ea4\u4ed8\u6761\u6b3e";
        String raw = "\ud83d\ude00" + excerpt + "\uff0c\u5408\u540c\u91d1\u989d\uff1a67000\u5143";
        FrozenReviewMaterial material = material(1, DocumentRole.CONTRACT_BODY, raw);
        port.result = available(List.of(new ContractTextAnalysisResult.Hint(
                ContractTextAnalysisResult.SemanticCategory.DELIVERY_TERMS,
                ContractTextAnalysisResult.Attention.REVIEW,
                "Delivery title", "Delivery description", List.of(material.fileId()), List.of(
                new ContractTextAnalysisResult.EvidenceItem(material.fileId(),
                        ContractTextAnalysisResult.EvidenceStatus.REVIEW, excerpt, true)))));

        var result = executor.execute(input(List.of(material)), context);

        assertThat(port.calls).isEqualTo(1);
        assertThat(port.markerWasCommittedBeforeCall).isTrue();
        assertThat(requestedFingerprints).hasSize(1);
        assertThat(requestedFingerprints.get(0)).matches("[0-9a-f]{64}");
        assertThat(port.request.projectId()).isEqualTo(projectId);
        assertThat(port.request.materials()).singleElement().satisfies(sent -> {
            assertThat(sent.fileId()).isEqualTo(material.fileId());
            assertThat(sent.category()).isEqualTo("CONTRACT_BODY");
            assertThat(sent.text()).doesNotContain("67000").contains(excerpt);
        });
        assertThat(result.outputResourceType()).isEqualTo("REVIEW_FINDING_SET");
        assertThat(result.outputResourceId()).isEqualTo(attemptId);
        assertThat(result.providerRequestId()).isNull();
        assertThat(result.provider()).isNull();
        assertThat(result.model()).isEqualTo("qwen-test");
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.findingType()).isEqualTo("SEMANTIC_HINT");
            assertThat(finding.category()).isEqualTo("DELIVERY_TERMS");
            assertThat(finding.statusCode()).isNull();
            assertThat(finding.attentionCode()).isEqualTo("REVIEW");
            assertThat(finding.riskLevel()).isNull();
            assertThat(finding.summary()).isEqualTo("Delivery title");
            assertThat(finding.recommendation()).isEqualTo("Delivery description");
            assertThat(finding.source()).isEqualTo(ReviewFindingSource.MODEL_ASSISTED);
            assertThat(finding.modelProvider()).isNull();
            assertThat(finding.modelName()).isEqualTo("qwen-test");
            assertThat(finding.modelRevision()).isNull();
        });
        assertThat(result.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.findingId()).isEqualTo(result.findings().get(0).findingId());
            assertThat(evidence.fileId()).isEqualTo(material.fileId());
            assertThat(evidence.parseResultId()).isEqualTo(material.parseResultId());
            assertThat(evidence.locatorAvailable()).isTrue();
            assertThat(evidence.startOffset()).isEqualTo(1);
            assertThat(evidence.endOffset()).isEqualTo(1 + excerpt.codePointCount(0, excerpt.length()));
            assertThat(evidence.boundedExcerpt()).isEqualTo(excerpt);
        });
        assertThat(result.toString()).doesNotContain("67000");
    }

    @Test
    void preservesTheFullTypedModelIdentifierBoundAcrossFindingAndAttemptPersistence() {
        String model = "m".repeat(128);
        FrozenReviewMaterial material = material(
                1, DocumentRole.CONTRACT_BODY, "unique frozen evidence");
        port.result = new ContractTextAnalysisResult(true, List.of("typed summary"),
                List.of(hint(material.fileId(), "unique frozen evidence", "Typed hint")), model, 7L);

        var result = executor.execute(input(List.of(material)), context);

        assertThat(result.model()).isEqualTo(model);
        assertThat(result.findings()).singleElement()
                .satisfies(finding -> assertThat(finding.modelName()).isEqualTo(model));
    }

    @Test
    void bindsSafeModelCallsToTheExactReviewAttemptAndRequestFingerprint() {
        FrozenReviewMaterial material = material(1, DocumentRole.CONTRACT_BODY, "unique frozen evidence");
        var call = new ContractTextAnalysisResult.ModelCall(1,
                ContractTextAnalysisResult.CallPhase.TRANSPORT,
                ContractTextAnalysisResult.CallOutcome.SUCCEEDED, null, 7, "STOP",
                10, 5, 15, "OPENAI_COMPATIBLE", "qwen-test");
        port.result = new ContractTextAnalysisResult(true, List.of("typed summary"),
                List.of(hint(material.fileId(), "unique frozen evidence", "Typed hint")),
                "qwen-test", 7L, List.of(call));

        var result = executor.execute(input(List.of(material)), context);

        assertThat(result.modelCalls()).singleElement().satisfies(audit -> {
            assertThat(audit.reviewStepAttemptId()).isEqualTo(attemptId);
            assertThat(audit.attemptNo()).isEqualTo(1);
            assertThat(audit.requestFingerprint()).isEqualTo(requestedFingerprints.get(0));
            assertThat(audit.totalTokens()).isEqualTo(15);
        });
    }

    @Test
    void ambiguousAndUnmatchedModelExcerptsNeverFabricateOffsets() {
        FrozenReviewMaterial repeated = material(1, DocumentRole.CONTRACT_BODY,
                "repeat clause / repeat clause");
        FrozenReviewMaterial unmatched = material(2, DocumentRole.SUPPLEMENT,
                "different frozen source");
        port.result = available(List.of(
                hint(repeated.fileId(), "repeat clause", "Repeated"),
                hint(unmatched.fileId(), "not in source", "Unmatched")));

        var result = executor.execute(input(List.of(unmatched, repeated)), context);

        assertThat(result.findings()).hasSize(2);
        assertThat(result.evidence()).hasSize(2).allSatisfy(evidence -> {
            assertThat(evidence.locatorAvailable()).isFalse();
            assertThat(evidence.validationStatus()).isEqualTo("LOCATOR_UNAVAILABLE");
            assertThat(evidence.startOffset()).isNull();
            assertThat(evidence.endOffset()).isNull();
            assertThat(evidence.excerptSha256()).matches("[0-9a-f]{64}");
        });
    }

    @Test
    void rejectsEveryEvidenceReferenceOutsideTheFrozenRun() {
        FrozenReviewMaterial material = material(1, DocumentRole.CONTRACT_BODY, "frozen source");
        UUID foreign = UUID.fromString("00000000-0000-0000-0000-000000000399");
        port.result = available(List.of(new ContractTextAnalysisResult.Hint(
                ContractTextAnalysisResult.SemanticCategory.OTHER_TEXT,
                ContractTextAnalysisResult.Attention.INSUFFICIENT,
                "Foreign", "Foreign reference", List.of(foreign))));

        assertThatThrownBy(() -> executor.execute(input(List.of(material)), context))
                .isInstanceOfSatisfying(ReviewStepProvenFailureException.class,
                        failure -> assertThat(failure.safeReasonCode())
                                .isEqualTo("SEMANTIC_RESPONSE_INVALID"));
        assertThat(requestedFingerprints).hasSize(1);
    }

    @Test
    void classifiesInvalidRequestContextBeforeMarkerOrProviderCall() {
        run = run("contract-semantic-future-policy-v2");
        context = context(requestedFingerprints::add);

        assertThatThrownBy(() -> executor.execute(
                input(List.of(material(1, DocumentRole.CONTRACT_BODY, "frozen source"))), context))
                .isInstanceOfSatisfying(ReviewStepProvenFailureException.class,
                        failure -> assertThat(failure.safeReasonCode())
                                .isEqualTo("SEMANTIC_INPUT_INVALID"));
        assertThat(requestedFingerprints).isEmpty();
        assertThat(port.calls).isZero();
    }

    @Test
    void doesNotClassifyExternalMarkerPersistenceFailureAsSemanticInputInvalid() {
        IllegalArgumentException markerFailure =
                new IllegalArgumentException("marker persistence failed");
        context = context(ignored -> { throw markerFailure; });

        assertThatThrownBy(() -> executor.execute(
                input(List.of(material(1, DocumentRole.CONTRACT_BODY, "frozen source"))), context))
                .isSameAs(markerFailure);
        assertThat(port.calls).isZero();
    }

    @Test
    void keepsDirectProviderRuntimeFailureInProviderResponseClassification() {
        port.failure = new IllegalArgumentException("provider response invalid");

        assertThatThrownBy(() -> executor.execute(
                input(List.of(material(1, DocumentRole.CONTRACT_BODY, "frozen source"))), context))
                .isInstanceOfSatisfying(ReviewStepProvenFailureException.class,
                        failure -> assertThat(failure.safeReasonCode())
                                .isEqualTo("SEMANTIC_RESPONSE_INVALID"));
        assertThat(requestedFingerprints).hasSize(1);
        assertThat(port.calls).isEqualTo(1);
    }

    @Test
    void unavailableAndTypedSchemaErrorsAreProvenFailures() {
        FrozenReviewInput input = input(List.of(material(1, DocumentRole.CONTRACT_BODY, "source")));
        port.result = ContractTextAnalysisResult.unavailable();

        assertThatThrownBy(() -> executor.execute(input, context))
                .isInstanceOfSatisfying(ReviewStepProvenFailureException.class,
                        failure -> assertThat(failure.safeReasonCode())
                                .isEqualTo("SEMANTIC_UNAVAILABLE"));

        requestedFingerprints.clear();
        context = context(requestedFingerprints::add);
        port.failure = new ExternalServiceException(ExternalServiceType.AI,
                new IllegalStateException("typed schema mismatch"));
        assertThatThrownBy(() -> executor.execute(input, context))
                .isInstanceOfSatisfying(ReviewStepProvenFailureException.class,
                        failure -> assertThat(failure.safeReasonCode())
                                .isEqualTo("SEMANTIC_RESPONSE_INVALID"));
        assertThat(requestedFingerprints).hasSize(1);
    }

    @Test
    void preservesSafeFastApiFailureClassification() {
        FrozenReviewInput frozen = input(List.of(material(1, DocumentRole.CONTRACT_BODY, "source")));
        var cases = List.of(
                new FailureCase(ContractTextAnalysisFailure.Reason.MODEL_INVALID_RESULT,
                        "SEMANTIC_MODEL_INVALID_RESULT"),
                new FailureCase(ContractTextAnalysisFailure.Reason.MODEL_GATEWAY_ERROR,
                        "SEMANTIC_MODEL_GATEWAY_ERROR"),
                new FailureCase(ContractTextAnalysisFailure.Reason.MODEL_TOTAL_BUDGET_EXHAUSTED,
                        "SEMANTIC_TOTAL_BUDGET_EXHAUSTED"),
                new FailureCase(ContractTextAnalysisFailure.Reason.MODEL_NOT_CONFIGURED,
                        "SEMANTIC_MODEL_NOT_CONFIGURED"),
                new FailureCase(ContractTextAnalysisFailure.Reason.MODEL_INPUT_TOO_LARGE,
                        "SEMANTIC_INPUT_TOO_LARGE"),
                new FailureCase(ContractTextAnalysisFailure.Reason.EMPTY_MODEL_INPUT,
                        "SEMANTIC_INPUT_INVALID"),
                new FailureCase(ContractTextAnalysisFailure.Reason.SERVICE_UNAVAILABLE,
                        "SEMANTIC_UNAVAILABLE"));

        for (FailureCase sample : cases) {
            requestedFingerprints.clear();
            context = context(requestedFingerprints::add);
            port.failure = new ExternalServiceException(ExternalServiceType.AI,
                    new ContractTextAnalysisFailure(sample.reason()));

            assertThatThrownBy(() -> executor.execute(frozen, context))
                    .isInstanceOfSatisfying(ReviewStepProvenFailureException.class,
                            failure -> assertThat(failure.safeReasonCode())
                                    .isEqualTo(sample.safeReason()));
            assertThat(requestedFingerprints).hasSize(1);
        }
    }

    @Test
    void preservesFailedProviderCallAuditOnAProvenSemanticFailure() {
        FrozenReviewInput frozen = input(List.of(material(1, DocumentRole.CONTRACT_BODY, "source")));
        var call = new ContractTextAnalysisResult.ModelCall(1,
                ContractTextAnalysisResult.CallPhase.REPAIR,
                ContractTextAnalysisResult.CallOutcome.FAILED, "INVALID_PROVIDER_JSON", 11,
                null, null, null, null, "OPENAI_COMPATIBLE", "qwen-test");
        port.failure = new ExternalServiceException(ExternalServiceType.AI,
                new ContractTextAnalysisFailure(
                        ContractTextAnalysisFailure.Reason.MODEL_INVALID_RESULT, List.of(call)));

        assertThatThrownBy(() -> executor.execute(frozen, context))
                .isInstanceOfSatisfying(ReviewStepProvenFailureException.class, failure -> {
                    assertThat(failure.safeReasonCode()).isEqualTo("SEMANTIC_MODEL_INVALID_RESULT");
                    assertThat(failure.modelCalls()).singleElement().satisfies(audit -> {
                        assertThat(audit.reviewStepAttemptId()).isEqualTo(attemptId);
                        assertThat(audit.safeReason()).isEqualTo("INVALID_PROVIDER_JSON");
                    });
                });
    }

    @Test
    void onlyUncertainEscapedTransportBecomesUnknown() {
        FrozenReviewInput input = input(List.of(material(1, DocumentRole.CONTRACT_BODY, "source")));
        port.failure = new ExternalServiceException(ExternalServiceType.AI,
                new ResourceAccessException("read timeout", new SocketTimeoutException("timeout")));

        assertThatThrownBy(() -> executor.execute(input, context))
                .isInstanceOfSatisfying(ReviewStepOutcomeUnknownException.class,
                        failure -> assertThat(failure.safeReasonCode())
                                .isEqualTo("SEMANTIC_OUTCOME_UNKNOWN"));
        assertThat(requestedFingerprints).hasSize(1);

        requestedFingerprints.clear();
        context = context(requestedFingerprints::add);
        port.failure = new ExternalServiceException(ExternalServiceType.AI,
                new ResourceAccessException("connect refused", new ConnectException("refused")));
        assertThatThrownBy(() -> executor.execute(input, context))
                .isInstanceOfSatisfying(ReviewStepProvenFailureException.class,
                        failure -> assertThat(failure.safeReasonCode())
                                .isEqualTo("SEMANTIC_UNAVAILABLE"));

        requestedFingerprints.clear();
        context = context(requestedFingerprints::add);
        port.failure = new ExternalServiceException(ExternalServiceType.AI,
                new ResourceAccessException("unknown host", new UnknownHostException("unresolved")));
        assertThatThrownBy(() -> executor.execute(input, context))
                .isInstanceOfSatisfying(ReviewStepProvenFailureException.class,
                        failure -> assertThat(failure.safeReasonCode())
                                .isEqualTo("SEMANTIC_UNAVAILABLE"));
    }

    private ContractTextAnalysisResult.Hint hint(UUID fileId, String excerpt, String title) {
        return new ContractTextAnalysisResult.Hint(
                ContractTextAnalysisResult.SemanticCategory.OTHER_TEXT,
                ContractTextAnalysisResult.Attention.REVIEW,
                title, title + " description", List.of(fileId), List.of(
                new ContractTextAnalysisResult.EvidenceItem(fileId,
                        ContractTextAnalysisResult.EvidenceStatus.REVIEW, excerpt, false)));
    }

    private ContractTextAnalysisResult available(List<ContractTextAnalysisResult.Hint> hints) {
        return new ContractTextAnalysisResult(true, List.of("typed summary"), hints, "qwen-test", 7L);
    }

    private record FailureCase(ContractTextAnalysisFailure.Reason reason, String safeReason) { }

    private FrozenReviewInput input(List<FrozenReviewMaterial> materials) {
        Contract contract = new Contract(contractId, "C-1", "Frozen Contract", "PROCUREMENT",
                "Party A", "Party B", null, Money.of(new BigDecimal("100.00")), null,
                "ACTIVE", 1, NOW, NOW, List.of(), null);
        return new FrozenReviewInput(run, contract, materials);
    }

    private FrozenReviewMaterial material(int ordinal, DocumentRole role, String text) {
        UUID fileId = UUID.randomUUID();
        return new FrozenReviewMaterial(UUID.randomUUID(), ordinal, fileId,
                ContractSnapshotCanonicalizer.sha256("file:" + fileId), UUID.randomUUID(), 1,
                ContractSnapshotCanonicalizer.sha256(text), text, role, null, null, null);
    }

    private ContractReviewRun run() {
        return run("contract-semantic-java-schema-v1-redaction-v1-grounding-v1");
    }

    private ContractReviewRun run(String modelPolicyVersion) {
        String snapshot = "{}";
        return new ContractReviewRun(runId, projectId, contractId, 1, null, null, 1,
                snapshot, ContractSnapshotCanonicalizer.sha256(snapshot), "profile-v1", "rule-v1",
                modelPolicyVersion, "c".repeat(64),
                "user-1", ContractReviewRunStatus.RUNNING, NOW, NOW, null, null, null, null, 1);
    }

    private ReviewExecutionContext context(ReviewExecutionContext.ExternalRequestMarker marker) {
        ContractReviewStep step = new ContractReviewStep(stepId, runId, ContractReviewStepType.SEMANTIC_AI,
                true, ContractReviewStepStatus.RUNNING, run.modelPolicyVersion(), 1,
                null, null, null, NOW, null, 1);
        ContractReviewStepAttempt attempt = new ContractReviewStepAttempt(attemptId, runId, stepId, 1,
                "d".repeat(64), ContractReviewStepStatus.RUNNING, null, null, NOW,
                ReviewStepExternalCallState.NOT_STARTED, null, null, null, null,
                NOW, null, null, null, null, 1);
        return new ReviewExecutionContext(run, step, attempt,
                new CurrentUser("user-1", "auditor", "Audit User", Set.of("AUDITOR")),
                new ProjectRequestMetadata("request-1", "trace-1", "127.0.0.1"),
                List.of(), marker);
    }

    private final class RecordingPort implements ContractTextAnalysisPort {
        private ContractTextAnalysisRequest request;
        private ContractTextAnalysisResult result = available(List.of());
        private RuntimeException failure;
        private int calls;
        private boolean markerWasCommittedBeforeCall;

        @Override
        public ContractTextAnalysisResult analyzeContractText(ContractTextAnalysisRequest request) {
            this.request = request;
            this.calls++;
            this.markerWasCommittedBeforeCall = !requestedFingerprints.isEmpty();
            RuntimeException nextFailure = failure;
            failure = null;
            if (nextFailure != null) throw nextFailure;
            return result;
        }
    }
}
