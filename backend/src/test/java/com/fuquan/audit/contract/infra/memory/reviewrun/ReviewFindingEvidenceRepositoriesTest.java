package com.fuquan.audit.contract.infra.memory.reviewrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.reviewrun.ContractSnapshotCanonicalizer;
import com.fuquan.audit.contract.application.reviewrun.ReviewRunManifestFactory;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewEvidence;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewFinding;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunMaterial;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import com.fuquan.audit.contract.domain.reviewrun.ReviewFindingSource;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReviewFindingEvidenceRepositoriesTest {
    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");

    @Test
    void appendAndReopenUseExactAttemptAndFrozenParseOwnership() {
        var fixture = fixture();
        var finding = finding(fixture, 1);
        var evidence = evidence(fixture, finding);
        fixture.results().insert(finding);
        fixture.results().insert(evidence);
        assertThat(fixture.results().findFindingsByAttemptId(fixture.attemptId()))
                .containsExactly(finding);
        assertThat(fixture.results().findEvidenceByAttemptId(fixture.attemptId()))
                .containsExactly(evidence);
        assertThat(fixture.results().findFindingsByRunId(fixture.runId()))
                .containsExactly(finding);
        assertThat(fixture.results().findEvidenceByRunId(fixture.runId()))
                .containsExactly(evidence);
    }

    @Test
    void cannotBindFindingOrEvidenceToForeignAttemptOrUnselectedParse() {
        var fixture = fixture();
        var finding = finding(fixture, 1);
        var wrongFinding = new ContractReviewFinding(finding.findingId(), UUID.randomUUID(),
                finding.stepId(), finding.attemptId(), finding.ordinal(), finding.findingType(),
                finding.category(), finding.statusCode(), finding.attentionCode(), finding.riskLevel(),
                finding.summary(), finding.recommendation(), finding.findingSchemaVersion(),
                finding.policyVersion(), finding.source(), finding.modelProvider(), finding.modelName(),
                finding.modelRevision(), finding.promptVersion(), finding.modelSchemaVersion(),
                finding.createdAt(), finding.versionNo());
        assertThatThrownBy(() -> fixture.results().insert(wrongFinding))
                .isInstanceOf(BusinessException.class);
        fixture.results().insert(finding);
        var selected = evidence(fixture, finding);
        var foreignParse = new ContractReviewEvidence(selected.evidenceId(), selected.findingId(),
                selected.runId(), selected.stepId(), selected.attemptId(), selected.ordinal(),
                selected.fileId(), selected.fileSha256(), UUID.randomUUID(), selected.parseVersion(),
                selected.canonicalTextSha256(), selected.evidenceRole(), selected.validationStatus(),
                selected.validationPolicyVersion(), selected.pageNumber(), selected.paragraphIndex(),
                selected.tableIndex(), selected.rowIndex(), selected.cellIndex(), selected.segmentIndex(),
                selected.locatorAvailable(), selected.startOffset(), selected.endOffset(),
                selected.excerptSha256(), selected.boundedExcerpt(), selected.locatorSchemaVersion(),
                selected.createdAt(), selected.versionNo());
        assertThatThrownBy(() -> fixture.results().insert(foreignParse))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void duplicateFindingOrdinalAndCrossFindingEvidenceAreRejected() {
        var fixture = fixture();
        var first = finding(fixture, 1);
        fixture.results().insert(first);
        var duplicate = new ContractReviewFinding(UUID.randomUUID(), first.runId(), first.stepId(),
                first.attemptId(), 1, first.findingType(), first.category(), first.statusCode(),
                first.attentionCode(), first.riskLevel(), first.summary(), first.recommendation(),
                first.findingSchemaVersion(), first.policyVersion(), first.source(),
                first.modelProvider(), first.modelName(), first.modelRevision(), first.promptVersion(),
                first.modelSchemaVersion(), first.createdAt(), first.versionNo());
        assertThatThrownBy(() -> fixture.results().insert(duplicate))
                .isInstanceOf(BusinessException.class);
        var foreign = evidence(fixture, first);
        var wrongFinding = new ContractReviewEvidence(foreign.evidenceId(), UUID.randomUUID(),
                foreign.runId(), foreign.stepId(), foreign.attemptId(), foreign.ordinal(),
                foreign.fileId(), foreign.fileSha256(), foreign.parseResultId(), foreign.parseVersion(),
                foreign.canonicalTextSha256(), foreign.evidenceRole(), foreign.validationStatus(),
                foreign.validationPolicyVersion(), null, null, null, null, null, null,
                foreign.locatorAvailable(), foreign.startOffset(), foreign.endOffset(),
                foreign.excerptSha256(), foreign.boundedExcerpt(), foreign.locatorSchemaVersion(),
                foreign.createdAt(), foreign.versionNo());
        assertThatThrownBy(() -> fixture.results().insert(wrongFinding))
                .isInstanceOf(BusinessException.class);
    }

    private static Fixture fixture() {
        var store = new InMemoryContractReviewRunRepositories();
        UUID runId = UUID.randomUUID(), projectId = UUID.randomUUID(), fileId = UUID.randomUUID();
        String canonical = "{\"contractVersion\":0}";
        var run = new ContractReviewRun(runId, projectId, UUID.randomUUID(), 0,
                null, null, 1, canonical, ContractSnapshotCanonicalizer.sha256(canonical),
                "profile-v1", "rule-v1", "model-v1", "a".repeat(64), UUID.randomUUID().toString(),
                ContractReviewRunStatus.PENDING, NOW, null, null, null, null, null, 0);
        store.insert(run);
        var material = new ContractReviewRunMaterial(UUID.randomUUID(), runId, 1,
                projectId, fileId, "b".repeat(64), UUID.randomUUID(), 1, "c".repeat(64),
                DocumentRole.CONTRACT_BODY, null, UUID.randomUUID(), null, run.requestedBy(), NOW);
        store.insert(material);
        var manifest = new ReviewRunManifestFactory().create(runId, NOW);
        manifest.steps().forEach(store::insert);
        manifest.attempts().forEach(store::insert);
        var step = manifest.steps().stream().filter(v -> v.stepType() == ContractReviewStepType.BREACH)
                .findFirst().orElseThrow();
        var attempt = manifest.attempts().stream().filter(v -> v.stepId().equals(step.stepId()))
                .findFirst().orElseThrow();
        return new Fixture(new InMemoryContractReviewResultsRepositories(store, store, store),
                runId, step.stepId(), attempt.attemptId(), material);
    }

    private static ContractReviewFinding finding(Fixture fixture, int ordinal) {
        return new ContractReviewFinding(UUID.randomUUID(), fixture.runId(), fixture.stepId(),
                fixture.attemptId(), ordinal, "BREACH", "CLAUSE", "NEEDS_REVIEW", null, null,
                "核对违约约定", null, 1, "breach-v1", ReviewFindingSource.DETERMINISTIC,
                null, null, null, null, null, NOW, 0);
    }

    private static ContractReviewEvidence evidence(Fixture fixture, ContractReviewFinding finding) {
        var material = fixture.material();
        return new ContractReviewEvidence(UUID.randomUUID(), finding.findingId(), finding.runId(),
                finding.stepId(), finding.attemptId(), 1, material.fileId(), material.fileSha256(),
                material.parseResultId(), material.parseVersion(), material.canonicalTextSha256(),
                "PROBLEM", "VALIDATED", "ground-v1", null, null, null, null, null, null,
                true, 0, 2, "d".repeat(64), "违约", 1, NOW, 0);
    }

    private record Fixture(InMemoryContractReviewResultsRepositories results, UUID runId,
            UUID stepId, UUID attemptId, ContractReviewRunMaterial material) { }
}
