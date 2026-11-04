package com.fuquan.audit.budget.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.budget.domain.BudgetCheckResult;
import com.fuquan.audit.budget.domain.BudgetSubjectMapping;
import com.fuquan.audit.budget.domain.ProcurementDemand;
import com.fuquan.audit.budget.infra.memory.InMemoryBudgetApprovalCheckRepository;
import com.fuquan.audit.budget.infra.memory.InMemoryBudgetSubjectMappingRepository;
import com.fuquan.audit.budget.infra.memory.InMemoryProcurementDemandRepository;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.observability.ObservabilityProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BudgetCheckServiceTest {

    private static final UUID PROC_PASS = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID PROC_AMOUNT_MISMATCH = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID PROC_NOT_FOUND = UUID.fromString("00000000-0000-0000-0000-000000000999");

    private final FlowDebugLogger flowLogger = new FlowDebugLogger(new ObservabilityProperties());

    @Test
    void allChecksPassWhenProcurementMatchesBudget() {
        BudgetCheckService service = new BudgetCheckService(
                new InMemoryProcurementDemandRepository(),
                new InMemoryBudgetSubjectMappingRepository(),
                new InMemoryBudgetApprovalCheckRepository(),
                flowLogger);

        List<BudgetCheckResult> results = service.checkBudgetApproval(PROC_PASS);

        assertThat(results).hasSize(4);
        assertThat(results).allMatch(BudgetCheckResult::passed);
        assertThat(results).anyMatch(r -> "SUBJECT_CODE".equals(r.checkType()));
        assertThat(results).anyMatch(r -> "BUDGET_AMOUNT".equals(r.checkType()));
    }

    @Test
    void amountMismatchDetectedWhenBudgetDiffersFromActual() {
        BudgetCheckService service = new BudgetCheckService(
                new InMemoryProcurementDemandRepository(),
                new InMemoryBudgetSubjectMappingRepository(),
                new InMemoryBudgetApprovalCheckRepository(),
                flowLogger);

        List<BudgetCheckResult> results = service.checkBudgetApproval(PROC_AMOUNT_MISMATCH);

        assertThat(results).hasSize(4);
        assertThat(results).anyMatch(r ->
                "BUDGET_AMOUNT".equals(r.checkType()) && !r.passed());
    }

    @Test
    void subjectCodeMismatchProducesFailedSubjectCheck() {
        List<BudgetCheckResult> results = serviceFor(
                procurement("M1001", "办公设备", "100.00", "供应商A"),
                List.of(mapping("M1001", "M9999", "办公设备", "100.00", "供应商A")),
                new InMemoryBudgetApprovalCheckRepository()).checkBudgetApproval(PROC_PASS);

        assertThat(results).hasSize(4);
        assertThat(results).anyMatch(result ->
                "SUBJECT_CODE".equals(result.checkType())
                        && !result.passed()
                        && "M9999".equals(result.expectedValue())
                        && "M1001".equals(result.actualValue())
                        && result.errorDetail() != null);
    }

    @Test
    void supplierNameMismatchProducesFailedSupplierCheck() {
        List<BudgetCheckResult> results = serviceFor(
                procurement("M1001", "办公设备", "100.00", "供应商B"),
                List.of(mapping("M1001", "M1001", "办公设备", "100.00", "供应商A")),
                new InMemoryBudgetApprovalCheckRepository()).checkBudgetApproval(PROC_PASS);

        assertThat(results).hasSize(4);
        assertThat(results).anyMatch(result ->
                "SUPPLIER_NAME".equals(result.checkType())
                        && !result.passed()
                        && "供应商A".equals(result.expectedValue())
                        && "供应商B".equals(result.actualValue()));
    }

    @Test
    void subjectNameMismatchProducesFailedSubjectNameCheck() {
        List<BudgetCheckResult> results = serviceFor(
                procurement("M1001", "办公设备B", "100.00", "供应商A"),
                List.of(mapping("M1001", "M1001", "办公设备A", "100.00", "供应商A")),
                new InMemoryBudgetApprovalCheckRepository()).checkBudgetApproval(PROC_PASS);

        assertThat(results).hasSize(4);
        assertThat(results).anyMatch(result ->
                "SUBJECT_NAME".equals(result.checkType())
                        && !result.passed()
                        && "办公设备A".equals(result.expectedValue())
                        && "办公设备B".equals(result.actualValue()));
    }

    @Test
    void multipleMismatchesProduceFourFailedChecks() {
        List<BudgetCheckResult> results = serviceFor(
                procurement("M1001", "实际名称", "101.00", "供应商B"),
                List.of(mapping("M1001", "M9999", "预算名称", "100.00", "供应商A")),
                new InMemoryBudgetApprovalCheckRepository()).checkBudgetApproval(PROC_PASS);

        assertThat(results).hasSize(4);
        assertThat(results).allMatch(result -> !result.passed());
        assertThat(results).allMatch(result -> result.errorDetail() != null);
    }

    @Test
    void emptyFieldsStillProduceFourChecksAndExplicitFailures() {
        List<BudgetCheckResult> results = serviceFor(
                procurement("M1001", null, null, null),
                List.of(mapping("M1001", "M1001", null, null, null)),
                new InMemoryBudgetApprovalCheckRepository()).checkBudgetApproval(PROC_PASS);

        assertThat(results).hasSize(4);
        assertThat(results).anyMatch(result ->
                "SUBJECT_CODE".equals(result.checkType()) && result.passed());
        assertThat(results.stream()
                .filter(result -> !"SUBJECT_CODE".equals(result.checkType()))
                .toList())
                .allMatch(result -> !result.passed() && result.errorDetail() != null);
    }

    @Test
    void blankSubjectCodeStillProducesFourPersistedFailureChecks() {
        InMemoryBudgetApprovalCheckRepository checkRepository = new InMemoryBudgetApprovalCheckRepository();
        List<BudgetCheckResult> results = serviceFor(
                procurement("", null, null, null),
                List.of(),
                checkRepository).checkBudgetApproval(PROC_PASS);

        assertThat(results).hasSize(4);
        assertThat(results).allMatch(result ->
                !result.passed()
                        && result.errorDetail().contains("科目编码为空"));
        assertThat(checkRepository.stored()).hasSize(4);
    }

    @Test
    void amountComparisonUsesNumericZeroToleranceWithoutRounding() {
        List<BudgetCheckResult> equalResults = serviceFor(
                procurement("M1001", "办公设备", "100.000", "供应商A"),
                List.of(mapping("M1001", "M1001", "办公设备", "100.00", "供应商A")),
                new InMemoryBudgetApprovalCheckRepository()).checkBudgetApproval(PROC_PASS);
        List<BudgetCheckResult> differentResults = serviceFor(
                procurement("M1001", "办公设备", "100.001", "供应商A"),
                List.of(mapping("M1001", "M1001", "办公设备", "100.00", "供应商A")),
                new InMemoryBudgetApprovalCheckRepository()).checkBudgetApproval(PROC_PASS);

        assertThat(equalResults).filteredOn(result -> "BUDGET_AMOUNT".equals(result.checkType()))
                .singleElement().extracting(BudgetCheckResult::passed).isEqualTo(true);
        assertThat(differentResults).filteredOn(result -> "BUDGET_AMOUNT".equals(result.checkType()))
                .singleElement().extracting(BudgetCheckResult::passed).isEqualTo(false);
    }

    @Test
    void multipleActiveMappingsAreRejectedInsteadOfChoosingFirst() {
        InMemoryBudgetApprovalCheckRepository checkRepository = new InMemoryBudgetApprovalCheckRepository();
        BudgetCheckService service = serviceFor(
                procurement("M1001", "办公设备", "100.00", "供应商A"),
                List.of(
                        mapping("M1001", "M1001", "办公设备", "100.00", "供应商A"),
                        mapping("M1001", "M1002", "办公设备", "100.00", "供应商A")),
                checkRepository);

        assertThatThrownBy(() -> service.checkBudgetApproval(PROC_PASS))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("multiple active budget subject mappings")
                .extracting("errorCode")
                .isEqualTo(com.fuquan.audit.common.exception.BusinessErrorCode.CONFLICT);
        assertThat(checkRepository.stored()).isEmpty();
    }

    @Test
    void throwsBusinessExceptionWhenProcurementNotFound() {
        BudgetCheckService service = new BudgetCheckService(
                new InMemoryProcurementDemandRepository(),
                new InMemoryBudgetSubjectMappingRepository(),
                new InMemoryBudgetApprovalCheckRepository(),
                flowLogger);

        assertThatThrownBy(() -> service.checkBudgetApproval(PROC_NOT_FOUND))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("procurement demand not found");
    }

    @Test
    void checkResultsArePersisted() {
        InMemoryBudgetApprovalCheckRepository checkRepo = new InMemoryBudgetApprovalCheckRepository();
        BudgetCheckService service = new BudgetCheckService(
                new InMemoryProcurementDemandRepository(),
                new InMemoryBudgetSubjectMappingRepository(),
                checkRepo,
                flowLogger);

        service.checkBudgetApproval(PROC_PASS);

        assertThat(checkRepo.stored()).hasSize(4);
        assertThat(checkRepo.stored())
                .extracting(BudgetCheckResult::checkType)
                .containsExactly("SUBJECT_CODE", "BUDGET_AMOUNT", "SUPPLIER_NAME", "SUBJECT_NAME");
    }

    private BudgetCheckService serviceFor(
            ProcurementDemand procurement,
            List<BudgetSubjectMapping> mappings,
            InMemoryBudgetApprovalCheckRepository checkRepository) {
        return new BudgetCheckService(
                id -> Optional.of(procurement),
                code -> mappings,
                checkRepository,
                flowLogger);
    }

    private static ProcurementDemand procurement(
            String subjectCode,
            String subjectName,
            String amount,
            String supplierName) {
        return new ProcurementDemand(
                PROC_PASS,
                "测试采购",
                "BA-TEST",
                subjectCode,
                subjectName,
                amount == null ? null : new BigDecimal(amount),
                supplierName,
                "APPROVED",
                Instant.parse("2024-01-01T00:00:00Z"));
    }

    private static BudgetSubjectMapping mapping(
            String systemSubjectCode,
            String budgetSubjectCode,
            String budgetSubjectName,
            String budgetAmount,
            String supplierName) {
        return new BudgetSubjectMapping(
                UUID.randomUUID(),
                systemSubjectCode,
                budgetSubjectName,
                budgetSubjectCode,
                budgetSubjectName,
                budgetAmount == null ? null : new BigDecimal(budgetAmount),
                "2024",
                supplierName,
                "ACTIVE");
    }
}
