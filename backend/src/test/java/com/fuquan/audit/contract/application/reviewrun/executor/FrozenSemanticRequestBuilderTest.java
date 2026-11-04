package com.fuquan.audit.contract.application.reviewrun.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.contract.application.ai.ContractAiTextRedactor;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.reviewrun.ContractSnapshotCanonicalizer;
import com.fuquan.audit.contract.application.reviewrun.FrozenReviewInput;
import com.fuquan.audit.contract.application.reviewrun.FrozenReviewMaterial;
import com.fuquan.audit.contract.application.reviewrun.ReviewExecutionContext;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.Money;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import com.fuquan.audit.contract.domain.reviewrun.ReviewStepExternalCallState;
import com.fuquan.audit.security.domain.CurrentUser;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FrozenSemanticRequestBuilderTest {
    private static final Instant NOW = Instant.parse("2026-09-16T04:00:00Z");
    private final UUID projectId = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private final UUID contractId = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private final UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000103");
    private final UUID stepId = UUID.fromString("00000000-0000-0000-0000-000000000104");
    private final UUID attemptId = UUID.fromString("00000000-0000-0000-0000-000000000105");
    private final UUID candidateId = UUID.fromString("00000000-0000-0000-0000-000000000106");
    private final UUID confirmationId = UUID.fromString("00000000-0000-0000-0000-000000000107");
    private final ContractReviewRun run = run();
    private final ReviewExecutionContext context = context(run);
    private final FrozenSemanticRequestBuilder builder =
            new FrozenSemanticRequestBuilder(new ContractAiTextRedactor());

    @Test void onlyNewFrozenHierarchyPolicyOptsInWhileOldObserveAndStrictWireStayAbsent() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var source = material(1, UUID.randomUUID(), DocumentRole.CONTRACT_BODY, "source-owned hierarchy clause");
        for (String suffix : List.of("", "-money-observe-v1", "-money-observe-v1-hierarchy-v1")) {
            var current = identityVariant(run, runId, projectId, contractId, run.contractSnapshotSha256(),
                    "contract-semantic-work-v3-redaction-v1-grounding-v1" + suffix, run.inputSnapshotIdentity());
            var built = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                    builder.build(input(current, List.of(source)), context(current)));
            com.fasterxml.jackson.databind.JsonNode wire = mapper.valueToTree(built.request());
            if (suffix.endsWith("-hierarchy-v1")) {
                assertThat(wire.path("semanticHierarchyProtocol").asText()).isEqualTo("contract-bounded-hierarchy-v1");
                assertThat(wire.path("semanticMoneyMode").asText()).isEqualTo("OBSERVE");
            } else assertThat(wire.has("semanticHierarchyProtocol")).isFalse();
        }
    }

    @Test void observeModeComesFromFrozenRunPolicyAndHasADistinctOutboundIdentity() {
        var observed = identityVariant(run, runId, projectId, contractId, run.contractSnapshotSha256(),
                "contract-semantic-work-v3-redaction-v1-grounding-v1-money-observe-v1", run.inputSnapshotIdentity());
        var strict = identityVariant(run, runId, projectId, contractId, run.contractSnapshotSha256(),
                "contract-semantic-work-v3-redaction-v1-grounding-v1", run.inputSnapshotIdentity());
        var source = material(1, UUID.randomUUID(), DocumentRole.CONTRACT_BODY, "frozen clause");
        var result = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                builder.build(input(observed, List.of(source)), context(observed)));
        assertThat(result.request().semanticMoneyMode()).isEqualTo("OBSERVE");
        assertThat(result.request().semanticQualityProtocol()).isEqualTo("contract-semantic-quality-v1");
        assertThat(result.requestFingerprint()).isNotEqualTo(
                builder.build(input(strict, List.of(source)), context(strict)).requestFingerprint());
    }

    @Test
    void durableRequestExplicitlyFreezesQualityProtocolWhileLegacyWireStaysUnchanged() {
        var current = identityVariant(run, runId, projectId, contractId, run.contractSnapshotSha256(),
                "contract-semantic-work-v3-redaction-v1-grounding-v1", run.inputSnapshotIdentity());
        var source = material(1, UUID.randomUUID(), DocumentRole.CONTRACT_BODY, "source-owned delivery clause");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode durable = mapper.valueToTree(
                builder.build(input(current, List.of(source)), context(current)).request());
        com.fasterxml.jackson.databind.JsonNode legacy = mapper.valueToTree(
                builder.build(input(List.of(source)), context).request());
        assertThat(durable.path("semanticQualityProtocol").asText()).isEqualTo("contract-semantic-quality-v1");
        assertThat(legacy.has("semanticQualityProtocol")).isFalse();
        assertThat(durable.path("materials")).isEqualTo(legacy.path("materials"));
    }

    @Test
    void currentV3PolicyBuildsAnOutboundRequestWithItsOwnFrozenFingerprint() {
        var current = identityVariant(run, runId, projectId, contractId, run.contractSnapshotSha256(),
                "contract-semantic-work-v3-redaction-v1-grounding-v1", run.inputSnapshotIdentity());
        var source = material(1, UUID.randomUUID(), DocumentRole.CONTRACT_BODY, "frozen v3 source");

        var built = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                builder.build(input(current, List.of(source)), context(current)));

        assertThat(built.request().materials()).singleElement().satisfies(value ->
                assertThat(value.text()).isEqualTo("frozen v3 source"));
        assertThat(built.requestFingerprint()).isNotEqualTo(
                builder.build(input(List.of(source)), context).requestFingerprint());
    }

    @Test
    void historicalV2PolicyCannotBuildANewOutboundRequestForTheV3Runtime() {
        var historical = identityVariant(run, runId, projectId, contractId, run.contractSnapshotSha256(),
                "contract-semantic-work-v2-redaction-v1-grounding-v1", run.inputSnapshotIdentity());
        var source = material(1, UUID.randomUUID(), DocumentRole.CONTRACT_BODY, "preserved old source");

        assertThatThrownBy(() -> builder.build(input(historical, List.of(source)), context(historical)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildsOnlyFromSortedFrozenMaterialsAndRedactsOutboundText() {
        String sensitive = "\u5408\u540c\u91d1\u989d\uff1a67000\u5143\uff0c\u4ea4\u4ed8\u671f20\u65e5";
        FrozenReviewMaterial first = material(1,
                UUID.fromString("00000000-0000-0000-0000-000000000201"),
                DocumentRole.CONTRACT_BODY, sensitive);
        FrozenReviewMaterial second = material(2,
                UUID.fromString("00000000-0000-0000-0000-000000000202"),
                DocumentRole.AWARD_NOTICE, "frozen award text");

        var built = builder.build(input(List.of(second, first)), context);

        assertThat(built.request().projectId()).isEqualTo(projectId);
        assertThat(built.request().materials()).extracting(
                        value -> value.fileId(), value -> value.category())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(first.fileId(), "CONTRACT_BODY"),
                        org.assertj.core.groups.Tuple.tuple(second.fileId(), "AWARD_NOTICE"));
        assertThat(built.request().materials().get(0).text())
                .doesNotContain("67000")
                .contains("20\u65e5");
        assertThat(built.requestFingerprint()).matches("[0-9a-f]{64}");
    }

    @Test
    void fingerprintIsStableAcrossInputOrderButChangesWithFrozenTuple() {
        FrozenReviewMaterial first = material(1,
                UUID.fromString("00000000-0000-0000-0000-000000000211"),
                DocumentRole.CONTRACT_BODY, "first frozen text");
        FrozenReviewMaterial second = material(2,
                UUID.fromString("00000000-0000-0000-0000-000000000212"),
                DocumentRole.SUPPLEMENT, "second frozen text");

        String ordered = builder.build(input(List.of(first, second)), context).requestFingerprint();
        String shuffled = builder.build(input(List.of(second, first)), context).requestFingerprint();
        FrozenReviewMaterial changed = material(2, second.fileId(),
                DocumentRole.SUPPLEMENT, "changed frozen text");

        assertThat(shuffled).isEqualTo(ordered);
        assertThat(builder.build(input(List.of(first, changed)), context).requestFingerprint())
                .isNotEqualTo(ordered);
    }

    @Test
    void rejectsUnverifiedOrDuplicateFrozenMaterialBeforeExternalRequest() {
        UUID duplicate = UUID.fromString("00000000-0000-0000-0000-000000000221");
        FrozenReviewMaterial valid = material(1, duplicate, DocumentRole.CONTRACT_BODY, "verified");
        FrozenReviewMaterial corrupt = new FrozenReviewMaterial(UUID.randomUUID(), 2, UUID.randomUUID(),
                "a".repeat(64), UUID.randomUUID(), 1, "b".repeat(64), "tampered",
                DocumentRole.SUPPLEMENT, null, null, null);
        FrozenReviewMaterial duplicateFile = material(2, duplicate, DocumentRole.SUPPLEMENT, "other");

        assertThatThrownBy(() -> builder.build(input(List.of(corrupt)), context))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.build(input(List.of(valid, duplicateFile)), context))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsPersistedTimestampPrecisionAndMutableExecutionVersionDifferences() {
        ContractReviewRun frozen = withExecutionState(run,
                Instant.parse("2026-09-29T08:50:08.123456Z"), 2);
        ContractReviewRun execution = withExecutionState(run,
                Instant.parse("2026-09-29T08:50:08.123456789Z"), 7);
        FrozenReviewMaterial material = material(1,
                UUID.fromString("00000000-0000-0000-0000-000000000231"),
                DocumentRole.CONTRACT_BODY, "frozen source");

        var built = builder.build(input(frozen, List.of(material)), context(execution));

        assertThat(built.requestFingerprint()).matches("[0-9a-f]{64}");
    }

    @Test
    void rejectsEveryImmutableFrozenRunIdentityMismatch() {
        FrozenReviewMaterial material = material(1,
                UUID.fromString("00000000-0000-0000-0000-000000000232"),
                DocumentRole.CONTRACT_BODY, "frozen source");
        FrozenReviewInput input = input(run, List.of(material));

        for (String field : List.of("runId", "projectId", "contractId", "contractVersion",
                "sourceCandidateId", "sourceConfirmationId", "runSchemaVersion",
                "contractSnapshotSha256", "reviewProfileVersion", "ruleVersion",
                "modelPolicyVersion", "inputSnapshotIdentity", "requestedBy")) {
            assertThat(FrozenSemanticRequestBuilder.sameFrozenRunIdentity(
                    run, identityVariant(run, field))).as(field).isFalse();
        }

        assertContextRejected(input, identityVariant(run, UUID.randomUUID(), projectId,
                contractId, run.contractSnapshotSha256(), run.modelPolicyVersion(),
                run.inputSnapshotIdentity()));
        assertContextRejected(input, identityVariant(run, runId, UUID.randomUUID(),
                contractId, run.contractSnapshotSha256(), run.modelPolicyVersion(),
                run.inputSnapshotIdentity()));
        assertContextRejected(input, identityVariant(run, runId, projectId,
                UUID.randomUUID(), run.contractSnapshotSha256(), run.modelPolicyVersion(),
                run.inputSnapshotIdentity()));
        assertContextRejected(input, identityVariant(run, runId, projectId,
                contractId, "a".repeat(64), run.modelPolicyVersion(),
                run.inputSnapshotIdentity()));
        assertContextRejected(input, identityVariant(run, runId, projectId,
                contractId, run.contractSnapshotSha256(), "future-policy-v2",
                run.inputSnapshotIdentity()));
        assertContextRejected(input, identityVariant(run, runId, projectId,
                contractId, run.contractSnapshotSha256(), run.modelPolicyVersion(),
                "b".repeat(64)));
    }

    @Test
    void rejectsStepAndAttemptIdentityMismatches() {
        FrozenReviewMaterial material = material(1,
                UUID.fromString("00000000-0000-0000-0000-000000000233"),
                DocumentRole.CONTRACT_BODY, "frozen source");
        FrozenReviewInput input = input(run, List.of(material));

        assertThatThrownBy(() -> builder.build(input, context(run, UUID.randomUUID(),
                runId, stepId, run.modelPolicyVersion())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.build(input, context(run, runId,
                UUID.randomUUID(), stepId, run.modelPolicyVersion())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.build(input, context(run, runId,
                runId, UUID.randomUUID(), run.modelPolicyVersion())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsStepPolicyMismatch() {
        FrozenReviewMaterial material = material(1,
                UUID.fromString("00000000-0000-0000-0000-000000000234"),
                DocumentRole.CONTRACT_BODY, "frozen source");

        assertThatThrownBy(() -> builder.build(input(List.of(material)),
                context(run, runId, runId, stepId, "future-policy-v2")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonSemanticStepType() {
        FrozenReviewMaterial material = material(1,
                UUID.fromString("00000000-0000-0000-0000-000000000235"),
                DocumentRole.CONTRACT_BODY, "frozen source");

        assertThatThrownBy(() -> builder.build(input(List.of(material)),
                context(run, runId, runId, stepId, ContractReviewStepType.BREACH,
                        run.modelPolicyVersion())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private FrozenReviewInput input(List<FrozenReviewMaterial> materials) {
        return input(run, materials);
    }

    private FrozenReviewInput input(ContractReviewRun value, List<FrozenReviewMaterial> materials) {
        Contract contract = new Contract(contractId, "C-1", "Frozen Contract", "PROCUREMENT",
                "Party A", "Party B", null, Money.of(new BigDecimal("100.00")), null,
                "ACTIVE", 1, NOW, NOW, List.of(), null);
        return new FrozenReviewInput(value, contract, materials);
    }

    private FrozenReviewMaterial material(int ordinal, UUID fileId, DocumentRole role, String text) {
        return new FrozenReviewMaterial(UUID.randomUUID(), ordinal, fileId,
                ContractSnapshotCanonicalizer.sha256("file:" + fileId), UUID.randomUUID(), 1,
                ContractSnapshotCanonicalizer.sha256(text), text, role, null, null, null);
    }

    private ContractReviewRun run() {
        String snapshot = "{}";
        return new ContractReviewRun(runId, projectId, contractId, 1,
                candidateId, confirmationId, 1,
                snapshot, ContractSnapshotCanonicalizer.sha256(snapshot), "profile-v1", "rule-v1",
                "contract-semantic-java-schema-v1-redaction-v1-grounding-v1", "c".repeat(64),
                "user-1", ContractReviewRunStatus.RUNNING, NOW, NOW, null, null, null, null, 1);
    }

    private ReviewExecutionContext context(ContractReviewRun value) {
        return context(value, value.runId(), value.runId(), stepId, value.modelPolicyVersion());
    }

    private ReviewExecutionContext context(ContractReviewRun value, UUID stepRunId,
            UUID attemptRunId, UUID attemptStepId, String stepPolicyVersion) {
        return context(value, stepRunId, attemptRunId, attemptStepId,
                ContractReviewStepType.SEMANTIC_AI, stepPolicyVersion);
    }

    private ReviewExecutionContext context(ContractReviewRun value, UUID stepRunId,
            UUID attemptRunId, UUID attemptStepId, ContractReviewStepType stepType,
            String stepPolicyVersion) {
        ContractReviewStep step = new ContractReviewStep(stepId, stepRunId,
                stepType,
                true, ContractReviewStepStatus.RUNNING, stepPolicyVersion, 1,
                null, null, null, NOW, null, 1);
        ContractReviewStepAttempt attempt = new ContractReviewStepAttempt(attemptId,
                attemptRunId, attemptStepId, 1,
                "d".repeat(64), ContractReviewStepStatus.RUNNING, null, null, NOW,
                ReviewStepExternalCallState.NOT_STARTED, null, null, null, null,
                NOW, null, null, null, null, 1);
        return new ReviewExecutionContext(value, step, attempt,
                new CurrentUser("user-1", "auditor", "Audit User", Set.of("AUDITOR")),
                new ProjectRequestMetadata("request-1", "trace-1", "127.0.0.1"),
                List.of(), ignored -> { });
    }

    private void assertContextRejected(FrozenReviewInput input, ContractReviewRun value) {
        assertThatThrownBy(() -> builder.build(input, context(value)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ContractReviewRun withExecutionState(ContractReviewRun value,
            Instant startedAt, long versionNo) {
        return new ContractReviewRun(value.runId(), value.projectId(), value.contractId(),
                value.contractVersion(), value.sourceCandidateId(), value.sourceConfirmationId(),
                value.runSchemaVersion(), value.contractSnapshotCanonicalJson(),
                value.contractSnapshotSha256(), value.reviewProfileVersion(), value.ruleVersion(),
                value.modelPolicyVersion(), value.inputSnapshotIdentity(), value.requestedBy(),
                ContractReviewRunStatus.RUNNING, value.requestedAt(), startedAt,
                null, null, null, null, versionNo);
    }

    private ContractReviewRun identityVariant(ContractReviewRun value,
            UUID variantRunId, UUID variantProjectId, UUID variantContractId,
            String snapshotSha256, String modelPolicyVersion, String inputSnapshotIdentity) {
        return new ContractReviewRun(variantRunId, variantProjectId, variantContractId,
                value.contractVersion(), value.sourceCandidateId(), value.sourceConfirmationId(),
                value.runSchemaVersion(), value.contractSnapshotCanonicalJson(), snapshotSha256,
                value.reviewProfileVersion(), value.ruleVersion(), modelPolicyVersion,
                inputSnapshotIdentity, value.requestedBy(), value.status(), value.requestedAt(),
                value.startedAt(), value.completedAt(), value.failedAt(), value.unknownAt(),
                value.safeReasonCode(), value.versionNo());
    }

    private ContractReviewRun identityVariant(ContractReviewRun value, String field) {
        return new ContractReviewRun(
                field.equals("runId") ? UUID.randomUUID() : value.runId(),
                field.equals("projectId") ? UUID.randomUUID() : value.projectId(),
                field.equals("contractId") ? UUID.randomUUID() : value.contractId(),
                field.equals("contractVersion") ? value.contractVersion() + 1 : value.contractVersion(),
                field.equals("sourceCandidateId") ? UUID.randomUUID() : value.sourceCandidateId(),
                field.equals("sourceConfirmationId") ? UUID.randomUUID() : value.sourceConfirmationId(),
                field.equals("runSchemaVersion") ? value.runSchemaVersion() + 1 : value.runSchemaVersion(),
                value.contractSnapshotCanonicalJson(),
                field.equals("contractSnapshotSha256")
                        ? "a".repeat(64) : value.contractSnapshotSha256(),
                field.equals("reviewProfileVersion")
                        ? "profile-v2" : value.reviewProfileVersion(),
                field.equals("ruleVersion") ? "rule-v2" : value.ruleVersion(),
                field.equals("modelPolicyVersion")
                        ? "future-policy-v2" : value.modelPolicyVersion(),
                field.equals("inputSnapshotIdentity")
                        ? "b".repeat(64) : value.inputSnapshotIdentity(),
                field.equals("requestedBy") ? "user-2" : value.requestedBy(),
                value.status(), value.requestedAt(), value.startedAt(), value.completedAt(),
                value.failedAt(), value.unknownAt(), value.safeReasonCode(), value.versionNo());
    }
}
