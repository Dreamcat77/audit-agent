package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.reviewrun.ContractSnapshotCanonicalizer;
import com.fuquan.audit.contract.application.reviewrun.ReviewRunManifestFactory;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import com.fuquan.audit.contract.domain.reviewrun.ReviewStepExternalCallState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class Dm8ReviewStepTransitionRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");

    @Test
    void mapperUsesExactOwnerStatusAndVersionForAllThreeGuardedUpdates() {
        var mapper = mock(ContractReviewRunMapper.class);
        var adapter = new Dm8ContractReviewRunRepositories(mapper);
        var fixture = fixture();
        when(mapper.updateAttemptCas(any(), eq("PENDING"), eq(0L))).thenReturn(1);
        when(mapper.updateStepCas(any(), eq("PENDING"), eq(0L))).thenReturn(1);
        when(mapper.updateRunCas(any(), eq("PENDING"), eq(0L))).thenReturn(1);
        var attempt = running(fixture.attempt());
        var step = running(fixture.step());
        var run = running(fixture.run());
        assertThat(adapter.transition(fixture.attempt(), attempt)).isEqualTo(attempt);
        assertThat(adapter.transition(fixture.step(), step)).isEqualTo(step);
        assertThat(adapter.transition(fixture.run(), run)).isEqualTo(run);
        verify(mapper).updateAttemptCas(any(), eq("PENDING"), eq(0L));
        verify(mapper).updateStepCas(any(), eq("PENDING"), eq(0L));
        verify(mapper).updateRunCas(any(), eq("PENDING"), eq(0L));
    }

    @Test
    void zeroAffectedRowsConflictAndNeverPretendSuccess() {
        var mapper = mock(ContractReviewRunMapper.class);
        var adapter = new Dm8ContractReviewRunRepositories(mapper);
        var fixture = fixture();
        assertThatThrownBy(() -> adapter.transition(fixture.attempt(), running(fixture.attempt())))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> adapter.transition(fixture.step(), running(fixture.step())))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> adapter.transition(fixture.run(), running(fixture.run())))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void sqlGuardsByExactIdentityStatusAndVersionWithoutBlindUpdate() throws Exception {
        String xml = Files.readString(Path.of("src", "main", "resources", "mappers", "dm8",
                "ContractReviewRunMapper.xml")).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        assertThat(xml).contains("UPDATE CONTRACT_REVIEW_RUN SET", "UPDATE CONTRACT_REVIEW_STEP SET",
                "UPDATE CONTRACT_REVIEW_STEP_ATTEMPT SET", "RUN_ID=#{ROW.RUNID}",
                "STEP_ID=#{ROW.STEPID}", "ATTEMPT_ID=#{ROW.ATTEMPTID}",
                "STATUS=#{EXPECTEDSTATUS}", "VERSION_NO=#{EXPECTEDVERSIONNO}");
    }

    private static Fixture fixture() {
        UUID runId = UUID.randomUUID();
        String canonical = "{\"contractVersion\":0}";
        var run = new ContractReviewRun(runId, UUID.randomUUID(), UUID.randomUUID(), 0,
                null, null, 1, canonical, ContractSnapshotCanonicalizer.sha256(canonical),
                "profile-v1", "rule-v1", "model-v1", "a".repeat(64), UUID.randomUUID().toString(),
                ContractReviewRunStatus.PENDING, NOW, null, null, null, null, null, 0);
        var manifest = new ReviewRunManifestFactory().create(runId, NOW);
        var step = manifest.steps().stream().filter(value ->
                value.stepType() == ContractReviewStepType.SEMANTIC_AI).findFirst().orElseThrow();
        var attempt = manifest.attempts().stream().filter(value ->
                value.stepId().equals(step.stepId())).findFirst().orElseThrow();
        return new Fixture(run, step, attempt);
    }

    private static ContractReviewRun running(ContractReviewRun value) {
        return new ContractReviewRun(value.runId(), value.projectId(), value.contractId(),
                value.contractVersion(), value.sourceCandidateId(), value.sourceConfirmationId(),
                value.runSchemaVersion(), value.contractSnapshotCanonicalJson(), value.contractSnapshotSha256(),
                value.reviewProfileVersion(), value.ruleVersion(), value.modelPolicyVersion(),
                value.inputSnapshotIdentity(), value.requestedBy(), ContractReviewRunStatus.RUNNING,
                value.requestedAt(), NOW, null, null, null, null, 1);
    }

    private static ContractReviewStep running(ContractReviewStep value) {
        return new ContractReviewStep(value.stepId(), value.runId(), value.stepType(), value.required(),
                ContractReviewStepStatus.RUNNING, value.stepPolicyVersion(), value.currentAttemptNo(),
                null, null, null, NOW, null, 1);
    }

    private static ContractReviewStepAttempt running(ContractReviewStepAttempt value) {
        return new ContractReviewStepAttempt(value.attemptId(), value.runId(), value.stepId(),
                value.attemptNo(), value.operationIdentity(), ContractReviewStepStatus.RUNNING,
                null, null, NOW, ReviewStepExternalCallState.NOT_STARTED, null, null, null, null,
                NOW, null, null, null, null, 1);
    }

    private record Fixture(ContractReviewRun run, ContractReviewStep step,
            ContractReviewStepAttempt attempt) { }
}
