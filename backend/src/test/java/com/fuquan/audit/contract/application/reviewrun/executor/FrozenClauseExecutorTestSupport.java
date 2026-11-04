package com.fuquan.audit.contract.application.reviewrun.executor;

import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.reviewrun.ContractSnapshotCanonicalizer;
import com.fuquan.audit.contract.application.reviewrun.FrozenReviewInput;
import com.fuquan.audit.contract.application.reviewrun.FrozenReviewMaterial;
import com.fuquan.audit.contract.application.reviewrun.ReviewExecutionContext;
import com.fuquan.audit.contract.application.reviewrun.ReviewRunManifestFactory;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class FrozenClauseExecutorTestSupport {
    static final Instant NOW = Instant.parse("2026-09-16T05:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final UUID PROJECT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    static final UUID CONTRACT_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    static final UUID RUN_ID = UUID.fromString("10000000-0000-0000-0000-000000000003");
    static final UUID STEP_ID = UUID.fromString("10000000-0000-0000-0000-000000000004");
    static final UUID ATTEMPT_ID = UUID.fromString("10000000-0000-0000-0000-000000000005");
    static final CurrentUser ACTOR = new CurrentUser(
            "user-1", "auditor", "Audit User", Set.of("AUDITOR"));

    private FrozenClauseExecutorTestSupport() {
    }

    static FrozenReviewMaterial material(int ordinal, UUID fileId, DocumentRole role, String text) {
        return new FrozenReviewMaterial(
                UUID.nameUUIDFromBytes(("run-material-" + ordinal).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                ordinal, fileId, Integer.toHexString(ordinal).repeat(64).substring(0, 64),
                UUID.nameUUIDFromBytes(("parse-result-" + ordinal).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                ordinal, ContractSnapshotCanonicalizer.sha256(text), text, role, null,
                UUID.nameUUIDFromBytes(("identity-decision-" + ordinal)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)), null);
    }

    static FrozenReviewMaterial material(int ordinal, UUID fileId, DocumentRole role,
            String text, String originalFilename) {
        FrozenReviewMaterial material = material(ordinal, fileId, role, text);
        return new FrozenReviewMaterial(material.runMaterialId(), material.ordinal(), material.fileId(),
                material.fileSha256(), material.parseResultId(), material.parseVersion(),
                material.canonicalTextSha256(), material.canonicalText(), material.effectiveRole(),
                material.roleDecisionId(), material.identityDecisionId(),
                material.sourceRoleDispositionId(), originalFilename);
    }

    static Execution execution(ContractReviewStepType type, BigDecimal amount,
            List<FrozenReviewMaterial> materials) {
        String policy = type == ContractReviewStepType.BREACH
                ? ReviewRunManifestFactory.BREACH_POLICY_VERSION : ReviewRunManifestFactory.TERM_POLICY_VERSION;
        return execution(type, amount, materials, policy);
    }

    static Execution execution(ContractReviewStepType type, BigDecimal amount,
            List<FrozenReviewMaterial> materials, String policy) {
        String canonical = "{}";
        var run = new ContractReviewRun(RUN_ID, PROJECT_ID, CONTRACT_ID, 0, null, null, 1,
                canonical, ContractSnapshotCanonicalizer.sha256(canonical), "profile-v1", "rule-v1",
                "model-v1", "a".repeat(64), ACTOR.userId(), ContractReviewRunStatus.RUNNING,
                NOW, NOW, null, null, null, null, 1);
        var contract = new Contract(CONTRACT_ID, "C-1", "Synthetic Contract", "PROCUREMENT",
                "Party A", "Party B", null, Money.of(amount), null, "ACTIVE", 0,
                NOW, NOW, List.of(), null);
        var step = new ContractReviewStep(STEP_ID, RUN_ID, type, true,
                ContractReviewStepStatus.RUNNING, policy, 1, null, null, null, NOW, null, 1);
        var attempt = new ContractReviewStepAttempt(ATTEMPT_ID, RUN_ID, STEP_ID, 1,
                "b".repeat(64), ContractReviewStepStatus.RUNNING, null, null, NOW,
                ReviewStepExternalCallState.NOT_STARTED, null, null, null, null,
                NOW, null, null, null, null, 1);
        var context = new ReviewExecutionContext(run, step, attempt, ACTOR,
                new ProjectRequestMetadata("request-1", "trace-1", "127.0.0.1"),
                List.of(), ignored -> {
                    throw new AssertionError("deterministic clause executor must not mark an external call");
                });
        return new Execution(new FrozenReviewInput(run, contract, materials), context);
    }

    record Execution(FrozenReviewInput input, ReviewExecutionContext context) {
    }
}
