package com.fuquan.audit.procurement.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.procurement.application.port.ProcurementProjectQueryPort;
import com.fuquan.audit.procurement.application.port.ProcurementQuoteCheckRepository;
import com.fuquan.audit.procurement.application.port.ProcurementQuoteSpreadsheetPort;
import com.fuquan.audit.procurement.domain.ProcurementQuoteCheck;
import com.fuquan.audit.procurement.domain.ProcurementProject;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProcurementQuoteCheckServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000102");

    @Test
    void calculatesWarningForOverBudgetQuoteAndPersistsResult() throws Exception {
        CapturingRepository repository = new CapturingRepository();
        ProcurementQuoteCheckService service = new ProcurementQuoteCheckService(
                input -> new ProcurementQuoteSpreadsheetPort.ParsedSheet(null, List.of(
                        new ProcurementQuoteSpreadsheetPort.QuoteRow(
                                2, "供应商A", new BigDecimal("120.129"), "办公电脑"),
                        new ProcurementQuoteSpreadsheetPort.QuoteRow(
                                3, "供应商B", new BigDecimal("100"), "办公电脑"))),
                repository,
                projectPort(new BigDecimal("100")));

        ProcurementQuoteCheck result = service.upload(PROJECT_ID.toString(),
                new java.io.ByteArrayInputStream(new byte[0]));

        assertThat(result.riskLevel()).isEqualTo("WARNING");
        assertThat(result.budgetAmount()).isEqualByComparingTo("100.00");
        assertThat(result.quotes().get(0).quoteAmount()).isEqualByComparingTo("120.13");
        assertThat(result.quotes().get(0).overBudgetAmount()).isEqualByComparingTo("20.13");
        assertThat(result.quotes().get(0).overBudgetRatio()).isEqualByComparingTo("0.2013");
        assertThat(result.coefficientOfVariation().scale()).isEqualTo(4);
        assertThat(repository.saved).isSameAs(result);
    }

    @Test
    void usesProjectBudgetAndIgnoresSpreadsheetBudget() throws Exception {
        ProcurementQuoteCheckService service = new ProcurementQuoteCheckService(
                input -> new ProcurementQuoteSpreadsheetPort.ParsedSheet(new BigDecimal("1"), List.of(
                        new ProcurementQuoteSpreadsheetPort.QuoteRow(
                                2, "供应商A", new BigDecimal("120"), "服务"))),
                new CapturingRepository(),
                projectPort(new BigDecimal("100")));

        ProcurementQuoteCheck result = service.upload(PROJECT_ID.toString(),
                new java.io.ByteArrayInputStream(new byte[0]));

        assertThat(result.budgetAmount()).isEqualByComparingTo("100.00");
        assertThat(result.quotes()).singleElement()
                .satisfies(quote -> assertThat(quote.overBudget()).isTrue());
        assertThat(result.riskLevel()).isEqualTo("WARNING");
        assertThat(result.highlyConcentrated()).isFalse();
        assertThat(result.coefficientOfVariation()).isEqualByComparingTo("0.0000");
    }

    @Test
    void oneQuoteWithinBudgetDoesNotTriggerConcentrationRisk() {
        ProcurementQuoteCheck result = ProcurementQuoteCheck.analyze("ONE", new BigDecimal("100"), List.of(
                new ProcurementQuoteCheck.QuoteInput(2, "A", new BigDecimal("90"), "x")));

        assertThat(result.highlyConcentrated()).isFalse();
        assertThat(result.riskLevel()).isEqualTo("INFO");
    }

    @Test
    void twoSimilarQuotesDoNotTriggerConcentrationRisk() {
        ProcurementQuoteCheck result = ProcurementQuoteCheck.analyze("TWO", new BigDecimal("100"), List.of(
                new ProcurementQuoteCheck.QuoteInput(2, "A", new BigDecimal("90"), "x"),
                new ProcurementQuoteCheck.QuoteInput(3, "B", new BigDecimal("90"), "x")));

        assertThat(result.coefficientOfVariation()).isEqualByComparingTo("0.0000");
        assertThat(result.highlyConcentrated()).isFalse();
        assertThat(result.riskLevel()).isEqualTo("INFO");
    }

    @Test
    void classifiesCriticalWhenOverBudgetAndQuotesAreHighlyConcentrated() {
        ProcurementQuoteCheck result = ProcurementQuoteCheck.analyze("BATCH-3", new BigDecimal("100"), List.of(
                new ProcurementQuoteCheck.QuoteInput(2, "A", new BigDecimal("110"), "x"),
                new ProcurementQuoteCheck.QuoteInput(3, "B", new BigDecimal("110.01"), "x"),
                new ProcurementQuoteCheck.QuoteInput(4, "C", new BigDecimal("109.99"), "x")));

        assertThat(result.riskLevel()).isEqualTo("CRITICAL");
        assertThat(result.coefficientOfVariation()).isLessThan(new BigDecimal("0.0500"));
        assertThat(result.quotes()).allSatisfy(item -> assertThat(item.suspicious()).isTrue());
    }

    @Test
    void usesUnroundedCvForConcentrationBoundary() {
        ProcurementQuoteCheck result = ProcurementQuoteCheck.analyze("BATCH-BOUNDARY",
                new BigDecimal("200"), List.of(
                        new ProcurementQuoteCheck.QuoteInput(2, "A", new BigDecimal("100"), "x"),
                        new ProcurementQuoteCheck.QuoteInput(3, "B", new BigDecimal("100"), "x"),
                        new ProcurementQuoteCheck.QuoteInput(4, "C", new BigDecimal("110.99"), "x")));

        assertThat(result.coefficientOfVariation()).isEqualByComparingTo("0.0500");
        assertThat(result.highlyConcentrated()).isTrue();
        assertThat(result.riskLevel()).isEqualTo("WARNING");
    }

    @Test
    void rejectsMissingBudgetAndEmptyQuotes() {
        ProcurementQuoteCheckService service = new ProcurementQuoteCheckService(
                input -> new ProcurementQuoteSpreadsheetPort.ParsedSheet(null, List.of()),
                new CapturingRepository(),
                projectPort(null));

        assertThatThrownBy(() -> service.upload(PROJECT_ID.toString(),
                new java.io.ByteArrayInputStream(new byte[0])))
                .hasMessageContaining("采购项目未填写预算金额");
        assertThatThrownBy(() -> ProcurementQuoteCheck.analyze(
                "BATCH-5", new BigDecimal("100"), List.of()))
                .hasMessageContaining("至少需要一条");
    }

    private static ProcurementProjectQueryPort projectPort(BigDecimal budgetAmount) {
        ProcurementProject project = new ProcurementProject(
                PROJECT_ID, "BA-2024-002", "2024年IT设备采购", "M2001",
                "信息技术设备采购", budgetAmount, null, "APPROVED", Instant.now());
        return new ProcurementProjectQueryPort() {
            @Override
            public Page findPage(String status, String keyword, int offset, int limit) {
                return new Page(List.of(project), 1);
            }

            @Override
            public Optional<ProcurementProject> findById(UUID id) {
                return PROJECT_ID.equals(id) ? Optional.of(project) : Optional.empty();
            }
        };
    }

    private static final class CapturingRepository implements ProcurementQuoteCheckRepository {
        private ProcurementQuoteCheck saved;

        @Override
        public void save(ProcurementQuoteCheck check) { saved = check; }

        @Override
        public Optional<ProcurementQuoteCheck> findByBatchId(String batchId) {
            return Optional.ofNullable(saved);
        }
    }
}
