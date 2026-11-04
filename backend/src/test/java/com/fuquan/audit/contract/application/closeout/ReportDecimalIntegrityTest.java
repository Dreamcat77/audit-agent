package com.fuquan.audit.contract.application.closeout;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fuquan.audit.contract.application.reviewrun.ReviewStepOutputReadService;
import com.fuquan.audit.contract.domain.closeout.ContractReviewReport;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewClauseComparison;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import com.fuquan.audit.integration.file.FileSystemStorageAdapter;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReportDecimalIntegrityTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(strings = {"0.00", "150630.00", "0.10", "0.12345678901234567890", "1E+5", "1E-17"})
    void readingAFrozenDecimalDoesNotChangeItsIntegrityBytes(String literal) {
        String frozen = "{\"amount\":" + literal + "}";
        assertEquals(frozen, CloseoutCanonical.json(CloseoutCanonical.tree(frozen)));
    }

    @Test void realSnapshotWithTypedMoneyCanBePublishedAndReopened() {
        var f = new CloseoutFixtures();
        var snapshots = monetarySnapshots(f);
        var snapshot = snapshots.freeze(f.project, f.contract, f.runId);
        var parsed = CloseoutCanonical.tree(snapshot.json());
        assertEquals(snapshot.findingIdentity(),
                CloseoutCanonical.sha256(CloseoutCanonical.json(parsed.path("findings"))));
        assertTrue(snapshot.json().contains("\"capAmount\":150630.00"));
        var tx = new ReportTransactionCoordinator(f.runs, f.closeout, f.idempotency, snapshots, f.audit::add, f.clock);
        var storage = new FileSystemStorageAdapter(root, (source, target) -> Files.createLink(target, source));
        var service = new ContractReviewReportService(f.auth, f.runs, f.closeout, tx,
                new DeterministicHtmlReportRenderer(), storage);
        var report = service.generate(f.project, f.contract, f.runId, "typed-decimal-report", f.metadata);
        assertEquals(ContractReviewReport.Status.AVAILABLE, report.status());
        byte[] first = service.download(f.project, f.contract, f.runId, report.reportId(), f.metadata).bytes();
        assertArrayEquals(first, service.download(f.project, f.contract, f.runId, report.reportId(), f.metadata).bytes());
        assertEquals(report, service.generate(f.project, f.contract, f.runId, "typed-decimal-report", f.metadata));
        assertEquals(1, f.closeout.findReportsByRun(f.runId).size());
    }

    @Test void changingTheMoneyInAFrozenSnapshotStillRejectsPublication() {
        var f = new CloseoutFixtures();
        var original = monetarySnapshots(f).freeze(f.project, f.contract, f.runId);
        var tampered = new ReportSnapshotFactory.Snapshot(
                original.json().replace("\"capAmount\":150630.00", "\"capAmount\":150631.00"),
                original.inputIdentity(), original.findingIdentity(), original.acknowledgementIdentity());
        assertNotEquals(original.json(), tampered.json());
        var snapshots = mock(ReportSnapshotFactory.class);
        when(snapshots.freeze(f.project, f.contract, f.runId)).thenReturn(tampered);
        var tx = new ReportTransactionCoordinator(f.runs, f.closeout, f.idempotency, snapshots, f.audit::add, f.clock);
        var storage = spy(new FileSystemStorageAdapter(root, (source, target) -> Files.createLink(target, source)));
        var service = new ContractReviewReportService(f.auth, f.runs, f.closeout, tx,
                new DeterministicHtmlReportRenderer(), storage);
        var report = service.generate(f.project, f.contract, f.runId, "tampered-decimal-report", f.metadata);
        assertEquals(ContractReviewReport.Status.FAILED, report.status());
        assertEquals("RENDER_REJECTED", report.safeReasonCode());
        verifyNoInteractions(storage);
    }

    private ReportSnapshotFactory monetarySnapshots(CloseoutFixtures f) {
        var output = f.outputs.getRun(f.project, f.contract, f.runId);
        var parts = new ArrayList<>(output.steps());
        for (int index = 0; index < parts.size(); index++) {
            var part = parts.get(index);
            if (part.step().stepType() != ContractReviewStepType.BREACH) continue;
            UUID comparison = UUID.randomUUID();
            var source = part.evidence().get(0);
            var values = new ArrayList<ContractReviewClauseComparison.Value>();
            for (String side : List.of("contract", "comparison")) {
                values.add(new ContractReviewClauseComparison.Value(UUID.randomUUID(), comparison, f.runId, side,
                        source.fileId(), "KNOWN", "late-delivery", "supplier", "contract-total",
                        new BigDecimal("0.10"), "PERCENT", "DAILY", null, new BigDecimal("0.00"),
                        new BigDecimal("150630.00"), null, null, null, null, null, null, null,
                        List.of(source.evidenceId())));
            }
            var typed = new ContractReviewClauseComparison(comparison, f.finding.findingId(), f.runId,
                    part.step().stepId(), f.finding.attemptId(), "late-delivery", "BREACH", "MATCH", 2, values);
            parts.set(index, new ReviewStepOutputReadService.StepOutput(part.run(), part.step(), part.attempts(),
                    part.auditTask(), part.auditCheckResults(), part.findings(), part.evidence(), List.of(typed)));
        }
        var reader = mock(ReviewStepOutputReadService.class);
        when(reader.getRun(f.project, f.contract, f.runId))
                .thenReturn(new ReviewStepOutputReadService.RunOutput(output.manifest(), parts));
        return new ReportSnapshotFactory(reader, f.frozen, f.closeout, f.f02Acks);
    }
}
