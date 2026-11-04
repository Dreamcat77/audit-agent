package com.fuquan.audit.procurement.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuquan.audit.procurement.application.port.ProcurementCollusionRepository;
import com.fuquan.audit.procurement.application.port.ProcurementQuoteCheckRepository.SupplierQuoteTimeline;
import com.fuquan.audit.procurement.domain.ProcurementCollusionAssessment;
import com.fuquan.audit.procurement.domain.ProcurementProject;
import com.fuquan.audit.procurement.domain.ProcurementSplitPurchaseAssessment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

class ProcurementSplitPurchaseDetectionTest {

    @Test
    void flagsTwoSupplierQuotesWithinThirtyDaysWhenTheyReachTheFiftyThousandThreshold() {
        UUID currentProject = UUID.randomUUID();
        List<SupplierQuoteTimeline> records = List.of(
                quote(currentProject, "P-001", "项目一", "供应商甲", "30000.00", "2026-03-01T00:00:00Z"),
                quote(UUID.randomUUID(), "P-002", "项目二", "供应商甲", "22000.00", "2026-03-20T00:00:00Z"));

        var findings = ProcurementCollusionReviewService.splitFindings(currentProject, records);

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.totalAmount()).isEqualByComparingTo("52000.00");
            assertThat(finding.thresholdAmount()).isEqualByComparingTo("50000.00");
            assertThat(finding.riskScore()).isEqualTo(70);
            assertThat(finding.records()).hasSize(2);
        });
    }

    @Test
    void ignoresRecordsOutsideTheThirtyDayWindowAndWindowsWithoutTheCurrentProject() {
        UUID currentProject = UUID.randomUUID();
        List<SupplierQuoteTimeline> records = List.of(
                quote(currentProject, "P-001", "项目一", "供应商甲", "30000.00", "2026-03-01T00:00:00Z"),
                quote(UUID.randomUUID(), "P-002", "项目二", "供应商甲", "30000.00", "2026-04-02T00:00:00Z"),
                quote(UUID.randomUUID(), "P-003", "项目三", "供应商乙", "30000.00", "2026-03-02T00:00:00Z"),
                quote(UUID.randomUUID(), "P-004", "项目四", "供应商乙", "30000.00", "2026-03-03T00:00:00Z"));

        assertThat(ProcurementCollusionReviewService.splitFindings(currentProject, records)).isEmpty();
    }

    @Test
    void backfillsSplitPurchaseAssessmentWhenAnExistingScoreHasNoSplitResult() {
        UUID projectId = UUID.randomUUID();
        ProcurementProjectQueryService projectQueryService = mock(ProcurementProjectQueryService.class);
        ProcurementCollusionRepository repository = mock(ProcurementCollusionRepository.class);
        ProcurementProject project = new ProcurementProject(projectId, "CG-001", "办公设备采购", null, null,
                new BigDecimal("50000"), null, "REVIEWING", Instant.parse("2026-03-01T00:00:00Z"));
        ProcurementCollusionAssessment score = new ProcurementCollusionAssessment(UUID.randomUUID(), projectId,
                0, "INFO", BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, List.of(), Instant.now());
        when(projectQueryService.requireById(projectId)).thenReturn(project);
        when(repository.findLatestAssessment(projectId)).thenReturn(Optional.of(score));
        when(repository.findLatestSplitPurchaseAssessment(projectId)).thenReturn(Optional.empty());
        when(repository.findDocuments(projectId)).thenReturn(List.of());

        ProcurementCollusionReviewService service = new ProcurementCollusionReviewService(projectQueryService,
                null, null, repository, null, null, null, java.time.Clock.systemUTC());

        var result = service.requireLatest(projectId);

        ArgumentCaptor<ProcurementSplitPurchaseAssessment> saved =
                ArgumentCaptor.forClass(ProcurementSplitPurchaseAssessment.class);
        verify(repository).saveSplitPurchaseAssessment(saved.capture());
        assertThat(saved.getValue().projectId()).isEqualTo(projectId);
        assertThat(saved.getValue().riskLevel()).isEqualTo("INFO");
        assertThat(saved.getValue().findings()).isEmpty();
        assertThat(result.splitPurchaseAssessment()).isEqualTo(saved.getValue());
    }

    private static SupplierQuoteTimeline quote(UUID projectId, String projectCode, String projectName,
            String supplier, String amount, String time) {
        return new SupplierQuoteTimeline(projectId, projectCode, projectName, supplier,
                new BigDecimal(amount), Instant.parse(time));
    }
}
