package com.fuquan.audit.contract.application.closeout;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.review.domain.FindingAcknowledgement;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ReportSnapshotTest {
    @Test void completedRunCannotHideIncompleteRequiredStep() {
        var f = new CloseoutFixtures();
        var actual = f.outputs.getRun(f.project, f.contract, f.runId);
        var steps = new ArrayList<>(actual.steps());
        var part = steps.get(1); var step = part.step();
        var incomplete = new com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep(step.stepId(), step.runId(),
                step.stepType(), true, com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus.RUNNING,
                step.stepPolicyVersion(), step.currentAttemptNo(), null, null, null, step.startedAt(), null, step.versionNo());
        steps.set(1, new com.fuquan.audit.contract.application.reviewrun.ReviewStepOutputReadService.StepOutput(
                part.run(), incomplete, part.attempts(), part.auditTask(), part.auditCheckResults(), part.findings(), part.evidence()));
        var reader = mock(com.fuquan.audit.contract.application.reviewrun.ReviewStepOutputReadService.class);
        when(reader.getRun(f.project, f.contract, f.runId)).thenReturn(
                new com.fuquan.audit.contract.application.reviewrun.ReviewStepOutputReadService.RunOutput(actual.manifest(), steps));
        assertThrows(BusinessException.class,
                () -> new ReportSnapshotFactory(reader, f.frozen, f.closeout, f.f02Acks).freeze(f.project, f.contract, f.runId));
    }
    private ReportSnapshotFactory factory(CloseoutFixtures f) {
        return new ReportSnapshotFactory(f.outputs, f.frozen, f.closeout, f.f02Acks);
    }
    @ParameterizedTest @EnumSource(value = ContractReviewRunStatus.class, names = {"FAILED", "UNKNOWN", "PARTIALLY_COMPLETED"})
    void terminalFailedOrUnknownRunsProduceStageReports(ContractReviewRunStatus status) {
        var f = new CloseoutFixtures(status);
        var actual = f.outputs.getRun(f.project, f.contract, f.runId);
        var steps = new ArrayList<>(actual.steps());
        var part = steps.get(1); var step = part.step();
        var terminalStepStatus = status == ContractReviewRunStatus.FAILED
                || status == ContractReviewRunStatus.PARTIALLY_COMPLETED
                ? com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus.FAILED
                : com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus.UNKNOWN;
        var terminalStep = new com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep(step.stepId(), step.runId(),
                step.stepType(), true, terminalStepStatus, step.stepPolicyVersion(), step.currentAttemptNo(),
                null, null, null, step.startedAt(), null, step.versionNo() + 1);
        steps.set(1, new com.fuquan.audit.contract.application.reviewrun.ReviewStepOutputReadService.StepOutput(
                part.run(), terminalStep, part.attempts(), part.auditTask(), part.auditCheckResults(), List.of(), List.of()));
        var reader = mock(com.fuquan.audit.contract.application.reviewrun.ReviewStepOutputReadService.class);
        when(reader.getRun(f.project, f.contract, f.runId)).thenReturn(
                new com.fuquan.audit.contract.application.reviewrun.ReviewStepOutputReadService.RunOutput(actual.manifest(), steps));
        var snapshot = new ReportSnapshotFactory(reader, f.frozen, f.closeout, f.f02Acks).freeze(f.project, f.contract, f.runId);
        assertEquals(status.name(), CloseoutCanonical.tree(snapshot.json()).path("businessProjection").path("runStatus").asText());
        assertTrue(new String(new DeterministicHtmlReportRenderer().render(snapshot.json()).bytes(), StandardCharsets.UTF_8)
                .contains("不构成完整审核结论"));
    }
    @Test void partiallyCompletedRunProducesAStageReportWhenARequiredStepIsStillActive() {
        var f = new CloseoutFixtures(ContractReviewRunStatus.PARTIALLY_COMPLETED);
        var actual = f.outputs.getRun(f.project, f.contract, f.runId);
        var steps = new ArrayList<>(actual.steps());
        var part = steps.get(1); var step = part.step();
        var running = new com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep(step.stepId(), step.runId(),
                step.stepType(), true, com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus.RUNNING,
                step.stepPolicyVersion(), step.currentAttemptNo(), null, null, null, step.startedAt(), null, step.versionNo());
        steps.set(1, new com.fuquan.audit.contract.application.reviewrun.ReviewStepOutputReadService.StepOutput(
                part.run(), running, part.attempts(), part.auditTask(), part.auditCheckResults(), part.findings(), part.evidence()));
        var reader = mock(com.fuquan.audit.contract.application.reviewrun.ReviewStepOutputReadService.class);
        when(reader.getRun(f.project, f.contract, f.runId)).thenReturn(
                new com.fuquan.audit.contract.application.reviewrun.ReviewStepOutputReadService.RunOutput(actual.manifest(), steps));
        var snapshot = new ReportSnapshotFactory(reader, f.frozen, f.closeout, f.f02Acks).freeze(f.project, f.contract, f.runId);
        assertEquals("RUNNING", CloseoutCanonical.tree(snapshot.json()).path("findings").get(1).path("status").asText());
        assertTrue(new String(new DeterministicHtmlReportRenderer().render(snapshot.json()).bytes(), StandardCharsets.UTF_8)
                .contains("不构成完整审核结论"));
    }
    @Test void unknownRunProducesStageReportWhenSiblingStepsAreStillPending() {
        var f = new CloseoutFixtures(ContractReviewRunStatus.UNKNOWN);
        var actual = f.outputs.getRun(f.project, f.contract, f.runId);
        var steps = new ArrayList<>(actual.steps());
        for (int index : List.of(2, 3)) {
            var part = steps.get(index); var step = part.step();
            var status = index == 2
                    ? com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus.UNKNOWN
                    : com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus.PENDING;
            var changed = new com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep(step.stepId(), step.runId(),
                    step.stepType(), true, status, step.stepPolicyVersion(), step.currentAttemptNo(), null, null,
                    null, null, null, step.versionNo() + 1);
            steps.set(index, new com.fuquan.audit.contract.application.reviewrun.ReviewStepOutputReadService.StepOutput(
                    part.run(), changed, List.of(), null, List.of(), List.of(), List.of()));
        }
        var reader = mock(com.fuquan.audit.contract.application.reviewrun.ReviewStepOutputReadService.class);
        when(reader.getRun(f.project, f.contract, f.runId)).thenReturn(
                new com.fuquan.audit.contract.application.reviewrun.ReviewStepOutputReadService.RunOutput(actual.manifest(), steps));
        var snapshot = new ReportSnapshotFactory(reader, f.frozen, f.closeout, f.f02Acks).freeze(f.project, f.contract, f.runId);
        var root = CloseoutCanonical.tree(snapshot.json());
        assertEquals("UNKNOWN", root.path("findings").get(2).path("status").asText());
        assertEquals("PENDING", root.path("findings").get(3).path("status").asText());
        assertTrue(new String(new DeterministicHtmlReportRenderer().render(snapshot.json()).bytes(), StandardCharsets.UTF_8)
                .contains("不构成完整审核结论"));
    }
    @ParameterizedTest @EnumSource(value = ContractReviewRunStatus.class, names = {"PENDING", "RUNNING"})
    void nonTerminalRunsCannotProduceReports(ContractReviewRunStatus status) {
        var f = new CloseoutFixtures(status);
        assertThrows(BusinessException.class, () -> factory(f).freeze(f.project, f.contract, f.runId));
    }
    @Test void completedSnapshotIncludesBothExplicitUnacknowledgedTokens() {
        var f = new CloseoutFixtures(); var snapshot = factory(f).freeze(f.project, f.contract, f.runId);
        var json = CloseoutCanonical.tree(snapshot.json());
        assertEquals(f.run.inputSnapshotIdentity(), snapshot.inputIdentity());
        assertEquals(2, json.path("acknowledgements").size());
        assertEquals("NONE", json.path("acknowledgements").get(0).path("state").asText());
        assertEquals("NONE", json.path("acknowledgements").get(1).path("state").asText());
        assertEquals(snapshot, factory(f).freeze(f.project, f.contract, f.runId));
    }
    @Test void f02AndNonF02AcknowledgementsChangeAckIdentityNotFindingIdentity() {
        var f = new CloseoutFixtures(); var before = factory(f).freeze(f.project, f.contract, f.runId);
        when(f.f02Acks.findByTaskId(f.check.taskId())).thenReturn(List.of(new FindingAcknowledgement(UUID.randomUUID(),
                f.check.id(), f.actor.userId(), f.now, "request", "trace", f.check.ruleVersion())));
        var f02 = factory(f).freeze(f.project, f.contract, f.runId);
        assertNotEquals(before.acknowledgementIdentity(), f02.acknowledgementIdentity());
        new AcknowledgementTransactionCoordinator(new CloseoutOwnership(f.outputs, f.auth), f.closeout, f.idempotency, f.audit::add, f.clock)
                .create(f.project, f.contract, f.runId, f.finding.findingId(), 0, "处理意见", "ack", f.actor.userId(), f.metadata);
        var both = factory(f).freeze(f.project, f.contract, f.runId);
        assertNotEquals(f02.acknowledgementIdentity(), both.acknowledgementIdentity());
        assertEquals(before.findingIdentity(), both.findingIdentity());
    }
    @Test void f02TaskProvenanceMismatchBlocksReport() {
        var f = new CloseoutFixtures(); when(f.tasks.findById(f.check.taskId())).thenReturn(Optional.empty());
        assertThrows(BusinessException.class, () -> factory(f).freeze(f.project, f.contract, f.runId));
    }
    @Test void acknowledgementRuleVersionMismatchBlocksReport() {
        var f = new CloseoutFixtures();
        when(f.f02Acks.findByTaskId(f.check.taskId())).thenReturn(List.of(new FindingAcknowledgement(UUID.randomUUID(),
                f.check.id(), f.actor.userId(), f.now, "request", "trace", f.check.ruleVersion() + 1)));
        assertThrows(BusinessException.class, () -> factory(f).freeze(f.project, f.contract, f.runId));
    }
    @Test void sortedSnapshotSurvivesRepositoryOrderingAndRendererEscapesText() {
        var f = new CloseoutFixtures(); var before = factory(f).freeze(f.project, f.contract, f.runId);
        var delegated = mock(com.fuquan.audit.contract.application.reviewrun.ReviewStepOutputReadService.class);
        var output = f.outputs.getRun(f.project, f.contract, f.runId);
        var reversed = new ArrayList<>(output.steps()); Collections.reverse(reversed);
        when(delegated.getRun(f.project, f.contract, f.runId)).thenReturn(
                new com.fuquan.audit.contract.application.reviewrun.ReviewStepOutputReadService.RunOutput(output.manifest(), reversed));
        var after = new ReportSnapshotFactory(delegated, f.frozen, f.closeout, f.f02Acks).freeze(f.project, f.contract, f.runId);
        assertEquals(before, after);
        var renderer = new DeterministicHtmlReportRenderer();
        String malicious = before.json().replace("请核对违约条款", "<script>alert(1)</script>");
        var one = renderer.render(malicious); var two = renderer.render(malicious);
        assertArrayEquals(one.bytes(), two.bytes());
        assertEquals("text/html; charset=utf-8", one.mediaType());
        String html = new String(one.bytes(), StandardCharsets.UTF_8);
        assertFalse(html.contains("<script>")); assertTrue(html.contains("&lt;script&gt;"));
        assertFalse(html.contains(f.runId.toString())); assertFalse(html.contains("F02_DETERMINISTIC"));
    }

    @Test void newReportShowsFrozenFindingStatusAndEvidenceWithoutExposingResourceIds() {
        var f = new CloseoutFixtures();
        var snapshot = factory(f).freeze(f.project, f.contract, f.runId);
        assertEquals(4, CloseoutCanonical.tree(snapshot.json()).path("schemaVersion").asInt());

        String html = new String(new DeterministicHtmlReportRenderer().render(snapshot.json()).bytes(),
                StandardCharsets.UTF_8);
        assertTrue(html.contains("建议复核"));
        assertTrue(html.contains("规则校验结果"));
        assertTrue(html.contains("合同正文"));
        assertTrue(html.contains("合成合同条款"));
        assertFalse(html.contains(f.finding.findingId().toString()));
        assertFalse(html.contains(f.runId.toString()));
    }

    @Test void legacyPreparedReportSnapshotStillRendersTheOriginalBytes() {
        String legacy = """
                {"schemaVersion":1,"contractName":"旧合同","findings":[{"stepType":"BREACH",
                "checks":[],"findings":[{"findingId":"old","summary":"旧摘要","recommendation":"旧建议"}]}],
                "acknowledgements":[]}
                """;
        String expected = "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><title>合同审核报告</title></head>"
                + "<body><h1>合同审核报告</h1><h2>旧合同</h2>"
                + "<p>本报告基于已完成审核时冻结的材料和结果。人工确认表示已查看并留下处理意见，不表示问题已解决。智能辅助提示需由业务人员判断。</p>"
                + "<section><h2>违约条款审查</h2><article><p>旧摘要</p><p>旧建议</p><p>尚未确认查看</p></article></section></body></html>\n";
        assertEquals(expected, new String(new DeterministicHtmlReportRenderer().render(legacy).bytes(),
                StandardCharsets.UTF_8));
    }

    @Test void newReportEscapesFrozenEvidenceInsteadOfExecutingMarkup() {
        var f = new CloseoutFixtures();
        String snapshot = factory(f).freeze(f.project, f.contract, f.runId).json()
                .replace("合成合同条款", "<script>alert(1)</script>");
        String html = new String(new DeterministicHtmlReportRenderer().render(snapshot).bytes(),
                StandardCharsets.UTF_8);
        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
    }
}
