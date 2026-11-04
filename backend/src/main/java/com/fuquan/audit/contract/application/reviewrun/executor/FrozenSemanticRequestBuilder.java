package com.fuquan.audit.contract.application.reviewrun.executor;

import com.fuquan.audit.contract.application.ai.ContractAiTextRedactor;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisPort.ContractTextAnalysisMaterial;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisPort.ContractTextAnalysisRequest;
import com.fuquan.audit.contract.application.reviewrun.ContractSnapshotCanonicalizer;
import com.fuquan.audit.contract.application.reviewrun.ContractReviewExecutionPolicyResolver;
import com.fuquan.audit.contract.application.reviewrun.FrozenReviewInput;
import com.fuquan.audit.contract.application.reviewrun.FrozenReviewMaterial;
import com.fuquan.audit.contract.application.reviewrun.ReviewExecutionContext;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Creates the external semantic request from one verified run snapshot only. */
@Component
public final class FrozenSemanticRequestBuilder {
    public static final String REDACTION_POLICY_VERSION = "contract-ai-redaction-v1";
    private static final String REQUEST_FINGERPRINT_SCHEMA = "frozen-semantic-request-v1";

    private final ContractAiTextRedactor redactor;

    public FrozenSemanticRequestBuilder(ContractAiTextRedactor redactor) {
        this.redactor = Objects.requireNonNull(redactor);
    }

    public BuiltRequest build(FrozenReviewInput input, ReviewExecutionContext context) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(context, "context must not be null");
        if (!sameFrozenRunIdentity(input.run(), context.run())
                || context.step().stepType() != ContractReviewStepType.SEMANTIC_AI
                || !context.step().runId().equals(input.run().runId())
                || !context.startedAttempt().runId().equals(input.run().runId())
                || !context.startedAttempt().stepId().equals(context.step().stepId())
                || !context.step().stepPolicyVersion().equals(input.run().modelPolicyVersion())
                || !(ContractReviewExecutionPolicyResolver.MODEL_POLICY_VERSION.equals(input.run().modelPolicyVersion())
                     || ContractReviewExecutionPolicyResolver.isDurableModelPolicy(input.run().modelPolicyVersion()))) {
            throw new IllegalArgumentException("semantic execution context does not match frozen run");
        }

        List<FrozenReviewMaterial> sorted = input.materials().stream()
                .sorted(Comparator.comparingInt(FrozenReviewMaterial::ordinal)
                        .thenComparing(value -> value.runMaterialId().toString()))
                .toList();
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException("semantic frozen materials must not be empty");
        }
        Set<UUID> runMaterialIds = new HashSet<>();
        Set<UUID> fileIds = new HashSet<>();
        Set<Integer> ordinals = new HashSet<>();
        List<ContractTextAnalysisMaterial> outbound = new ArrayList<>(sorted.size());
        StringBuilder identity = new StringBuilder();
        append(identity, REQUEST_FINGERPRINT_SCHEMA);
        append(identity, input.run().inputSnapshotIdentity());
        append(identity, input.run().projectId());
        append(identity, input.run().runId());
        append(identity, context.step().stepId());
        append(identity, context.step().stepType());
        append(identity, context.step().stepPolicyVersion());
        append(identity, context.startedAttempt().attemptId());
        append(identity, context.startedAttempt().operationIdentity());
        append(identity, input.run().modelPolicyVersion());
        append(identity, REDACTION_POLICY_VERSION);

        for (FrozenReviewMaterial material : sorted) {
            validate(material, runMaterialIds, fileIds, ordinals);
            String redacted = redactor.redact(material.canonicalText());
            if (redacted.isBlank()) {
                throw new IllegalArgumentException("semantic redacted material must not be blank");
            }
            outbound.add(new ContractTextAnalysisMaterial(
                    material.fileId(), material.effectiveRole().name(), redacted));
            append(identity, material.runMaterialId());
            append(identity, material.ordinal());
            append(identity, material.fileId());
            append(identity, material.fileSha256());
            append(identity, material.parseResultId());
            append(identity, material.parseVersion());
            append(identity, material.canonicalTextSha256());
            append(identity, ContractSnapshotCanonicalizer.sha256(redacted));
            append(identity, material.effectiveRole());
            append(identity, material.roleDecisionId());
            append(identity, material.identityDecisionId());
            append(identity, material.sourceRoleDispositionId());
        }
        String qualityProtocol = ContractReviewExecutionPolicyResolver.isDurableModelPolicy(input.run().modelPolicyVersion())
                ? "contract-semantic-quality-v1" : null;
        if (qualityProtocol != null) append(identity, qualityProtocol);
        String mode = ContractReviewExecutionPolicyResolver.moneyModeForPolicy(input.run().modelPolicyVersion());
        String hierarchy = ContractReviewExecutionPolicyResolver.hierarchyProtocolForPolicy(input.run().modelPolicyVersion());
        if (hierarchy != null) append(identity, hierarchy);
        // Preserve historical STRICT request fingerprints; OBSERVE already has a distinct frozen policy.
        return new BuiltRequest(new ContractTextAnalysisRequest(input.run().projectId(), outbound, qualityProtocol, mode, hierarchy),
                ContractSnapshotCanonicalizer.sha256(identity.toString()));
    }

    static boolean sameFrozenRunIdentity(ContractReviewRun left, ContractReviewRun right) {
        return left != null && right != null
                && Objects.equals(left.runId(), right.runId())
                && Objects.equals(left.projectId(), right.projectId())
                && Objects.equals(left.contractId(), right.contractId())
                && left.contractVersion() == right.contractVersion()
                && Objects.equals(left.sourceCandidateId(), right.sourceCandidateId())
                && Objects.equals(left.sourceConfirmationId(), right.sourceConfirmationId())
                && left.runSchemaVersion() == right.runSchemaVersion()
                && Objects.equals(left.contractSnapshotSha256(), right.contractSnapshotSha256())
                && Objects.equals(left.reviewProfileVersion(), right.reviewProfileVersion())
                && Objects.equals(left.ruleVersion(), right.ruleVersion())
                && Objects.equals(left.modelPolicyVersion(), right.modelPolicyVersion())
                && Objects.equals(left.inputSnapshotIdentity(), right.inputSnapshotIdentity())
                && Objects.equals(left.requestedBy(), right.requestedBy());
    }

    private static void validate(FrozenReviewMaterial material, Set<UUID> runMaterialIds,
            Set<UUID> fileIds, Set<Integer> ordinals) {
        if (material == null || material.runMaterialId() == null || material.ordinal() <= 0
                || material.fileId() == null || material.fileSha256() == null
                || material.parseResultId() == null || material.parseVersion() <= 0
                || material.canonicalTextSha256() == null || material.canonicalText() == null
                || material.effectiveRole() == null
                || !material.fileSha256().matches("[0-9a-f]{64}")
                || !ContractSnapshotCanonicalizer.sha256(material.canonicalText())
                        .equals(material.canonicalTextSha256())
                || !runMaterialIds.add(material.runMaterialId())
                || !fileIds.add(material.fileId()) || !ordinals.add(material.ordinal())) {
            throw new IllegalArgumentException("invalid frozen semantic material tuple");
        }
    }

    private static void append(StringBuilder target, Object value) {
        String text = value == null ? "<null>" : value.toString();
        target.append(text.length()).append(':').append(text).append('|');
    }

    public record BuiltRequest(
            ContractTextAnalysisRequest request, String requestFingerprint) {
        public BuiltRequest {
            Objects.requireNonNull(request);
            if (requestFingerprint == null || !requestFingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid semantic request fingerprint");
            }
        }
    }
}
