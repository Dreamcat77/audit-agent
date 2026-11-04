package com.fuquan.audit.budget.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.blacklist.application.port.BlacklistCheckPort;
import com.fuquan.audit.budget.application.ai.BudgetSemanticAnalysisPort;
import com.fuquan.audit.budget.application.ai.BudgetSemanticAnalysisResult;
import com.fuquan.audit.budget.application.ai.BudgetSemanticAnalysisUseCase;
import com.fuquan.audit.budget.application.port.BudgetCategoryRuleRepository;
import com.fuquan.audit.budget.application.port.BudgetSubjectMappingRepository;
import com.fuquan.audit.budget.application.port.BudgetProjectConfigRepository;
import com.fuquan.audit.budget.application.port.BudgetReviewItemRepository;
import com.fuquan.audit.budget.application.port.BudgetReviewTaskRepository;
import com.fuquan.audit.budget.application.port.BudgetValidationConfirmLogRepository;
import com.fuquan.audit.budget.application.port.BudgetYearValidityConfigRepository;
import com.fuquan.audit.budget.application.port.BudgetMaterialParser;
import com.fuquan.audit.budget.domain.BudgetReviewItem;
import com.fuquan.audit.budget.domain.BudgetReviewTask;
import com.fuquan.audit.budget.domain.BudgetSubjectMapping;
import com.fuquan.audit.budget.domain.BudgetValidationConfirmLog;
import com.fuquan.audit.budget.infra.memory.InMemoryBudgetReviewItemRepository;
import com.fuquan.audit.budget.infra.memory.InMemoryBudgetReviewTaskRepository;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.observability.ObservabilityProperties;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class BudgetReviewServiceTest {

    private final FlowDebugLogger logger = new FlowDebugLogger(new ObservabilityProperties());
    private int confirmationWrites;

    @Test
    void preparedReferenceUsesOneExtractionAndRejectsForeignIdentityOrChangedRowCount() {
        var service=service(List.of());
        var task=upload(service,"办公设备\t50.00\tABC");
        var prepared=new BudgetReviewService.PreparedMaterial(task,List.of(new BudgetMaterialLine(1,"办公设备",new BigDecimal("50"))));
        assertThatThrownBy(()->service.reviewPreparedReference(UUID.randomUUID(),()->{},prepared)).hasMessageContaining("identity mismatch");
        assertThatThrownBy(()->service.reviewPreparedReference(task.id(),()->{},new BudgetReviewService.PreparedMaterial(task,List.of()))).hasMessageContaining("数量不一致");
        var reviewed=service.reviewPreparedReference(task.id(),()->{},prepared);
        assertThat(reviewed.status()).isEqualTo("REVIEWED");
        assertThat(service.findItems(task.id())).hasSize(1);
    }

    @Test
    void referenceReportAllowsDifferentSubjectsOfTheSameProject() {
        var service = service(List.of());
        var task = upload(service, "办公设备\t50.00\tABC\n日常维修\t60.00\tDEF");
        service.reviewReference(task.id(), () -> {});
        assertThat(service.findItems(task.id())).extracting(BudgetReviewItem::projectCodeStatus)
                .containsExactly("PASSED", "PASSED");
        assertThat(confirmationWrites).isZero();
    }

    @Test
    void referenceReviewChecksEveryRowAndKeepsExceptionsWithoutConfirmation() {
        var service = service(List.of(mapping("办公设备采购", "100.00")));
        var task = upload(service, "\t非法金额\n办公设备采购\t100.01\n办公设备采购\t50.00");
        var leaseChecks = new java.util.concurrent.atomic.AtomicInteger();
        var reviewed = service.reviewReference(task.id(), leaseChecks::incrementAndGet);
        var items = service.findItems(task.id());
        assertThat(reviewed.status()).isEqualTo("REVIEWED");
        assertThat(reviewed.reviewCursor()).isEqualTo(3);
        assertThat(reviewed.pendingStep()).isNull();
        assertThat(items).hasSize(3);
        assertThat(items.get(0).requiredFieldsStatus()).isEqualTo("FAILED");
        assertThat(items.get(0).amountFormatStatus()).isEqualTo("FAILED");
        assertThat(items.get(0).matchStatus()).isEqualTo("UNMATCHED");
        assertThat(items.get(0).riskLevel()).isNotEqualTo("INFO");
        assertThat(items.get(1).amountStatus()).isEqualTo("OVER_BUDGET");
        assertThat(items.get(1).differenceAmount()).isEqualByComparingTo("0.01");
        assertThat(items.get(1).budgetYearValidityStatus()).isEqualTo("PASSED");
        assertThat(items.get(2).amountStatus()).isEqualTo("PASSED");
        assertThat(items.get(2).projectCodeStatus()).isEqualTo("FAILED");
        assertThat(confirmationWrites).isZero();
        assertThat(leaseChecks.get()).isEqualTo(1);
    }

    @Test
    void referenceReviewNeverComparesAgainstAnAmbiguousApproval() {
        var service = service(List.of(mapping("办公设备采购", "100.00"), mapping("办公设备采购", "200.00")));
        var task = upload(service, "办公设备采购\t150.00");
        service.reviewReference(task.id(), () -> {});
        var item = service.findItems(task.id()).get(0);
        assertThat(item.matchStatus()).isEqualTo("SUSPECTED");
        assertThat(item.amountStatus()).isEqualTo("NEED_CONFIRMATION");
        assertThat(item.differenceAmount()).isNull();
        assertThat(item.riskLevel()).isNotEqualTo("INFO");
    }

    @Test
    void referenceReviewCannotSaveResultsAfterLosingLease() {
        var service = service(List.of(mapping("办公设备采购", "100.00")));
        var task = upload(service, "办公设备采购\t50.00");
        assertThatThrownBy(() -> service.reviewReference(task.id(), () -> {
            throw new IllegalStateException("lease lost");
        })).hasMessage("lease lost");
        assertThat(service.findItems(task.id())).isEmpty();
        assertThat(service.requireTask(task.id()).status()).isEqualTo("PARSED");
    }

    @Test
    void exactMatchAndDeclaredAmountWithinApprovalPass() {
        BudgetReviewService service = service(List.of(mapping("办公设备及耗材采购", "100.00")));

        BudgetReviewTask task = upload(service, "办公设备及耗材采购\t99.99");
        service.review(task.id());

        BudgetReviewItem item = service.findItems(task.id()).get(0);
        assertThat(item.matchStatus()).isEqualTo("MATCHED");
        assertThat(item.matchMethod()).isEqualTo("EXACT");
        assertThat(item.amountStatus()).isEqualTo("PASSED");
        assertThat(item.differenceAmount()).isEqualByComparingTo("-0.01");
        assertThat(item.exceptionType()).isNull();
    }

    @Test
    void fuzzyMatchWithinTwoCharactersIsReported() {
        BudgetReviewService service = service(List.of(mapping("办公设备及耗材采购", "100.00")));

        BudgetReviewTask task = upload(service, "办公设备及耗材购\t99.00");
        service.review(task.id());

        BudgetReviewItem item = service.findItems(task.id()).get(0);
        assertThat(item.matchStatus()).isEqualTo("MATCHED");
        assertThat(item.matchMethod()).isEqualTo("FUZZY");
        assertThat(item.matchDistance()).isEqualTo(1);
    }

    @Test
    void multipleCandidatesAreSuspectedInsteadOfPassed() {
        BudgetReviewService service = service(List.of(
                mapping("办公设备采购", "100.00"),
                mapping("办公设备采购", "200.00")));

        BudgetReviewTask task = upload(service, "办公设备采购\t50.00");
        service.review(task.id());

        BudgetReviewItem item = service.findItems(task.id()).get(0);
        assertThat(item.matchStatus()).isEqualTo("SUSPECTED");
        assertThat(item.amountStatus()).isEqualTo("NEED_CONFIRMATION");
        assertThat(item.exceptionType()).isEqualTo("AMBIGUOUS_MATCH");
    }

    @Test
    void overBudgetUsesApprovalAsBaselineAndStoresPositiveDifference() {
        BudgetReviewService service = service(List.of(mapping("办公设备采购", "100.00")));

        BudgetReviewTask task = upload(service, "办公设备采购\t100.01");
        service.review(task.id());

        BudgetReviewItem item = service.findItems(task.id()).get(0);
        assertThat(item.amountStatus()).isEqualTo("OVER_BUDGET");
        assertThat(item.differenceAmount()).isEqualByComparingTo("0.01");
        assertThat(item.exceptionType()).isEqualTo("OVER_BUDGET");
    }

    @Test
    void missingSubjectAndAmountAreSummarizedOnTheSameRow() {
        BudgetReviewService service = service(List.of(mapping("办公设备采购", "100.00")));

        BudgetReviewTask task = upload(service, "\t非法金额");
        service.review(task.id());

        BudgetReviewItem item = service.findItems(task.id()).get(0);
        assertThat(item.matchStatus()).isEqualTo("NOT_CHECKED");
        assertThat(item.amountStatus()).isEqualTo("NOT_CHECKED");
        assertThat(item.riskLevel()).isEqualTo("WARNING");
        assertThat(item.exceptionType()).isEqualTo("REQUIRED_FIELD_MISSING");
        assertThat(item.abnormalReasons()).contains(
                "必填信息缺失：科目名称");
    }

    @Test
    void aiHintsAreAttachedWithoutChangingDeterministicFacts() {
        RecordingBudgetAiPort port = new RecordingBudgetAiPort();
        BudgetReviewService service = service(
                List.of(mapping("办公设备及耗材采购", "100.00")), port);

        BudgetReviewTask task = upload(service, "办公设备及耗材采购\t99.99");
        service.review(task.id());

        BudgetReviewItem item = service.findItems(task.id()).get(0);
        assertThat(item.aiSuggestedCategory()).isEqualTo("办公用品");
        assertThat(item.aiHint()).contains("语义辅助");
        assertThat(item.matchStatus()).isEqualTo("MATCHED");
        assertThat(item.amountStatus()).isEqualTo("PASSED");
        assertThat(item.riskLevel()).isEqualTo("INFO");
        assertThat(port.requestCount).isEqualTo(1);
        assertThat(port.transactionActive).isFalse();
    }

    @Test
    void aiResultIsMatchedByItemIdInsteadOfResponseOrder() {
        BudgetReviewService service = service(
                List.of(mapping("办公设备及耗材采购", "100.00")),
                new ReorderedBudgetAiPort());

        BudgetReviewTask task = upload(service, "办公设备及耗材采购\t99.99");
        service.review(task.id());

        BudgetReviewItem item = service.findItems(task.id()).get(0);
        assertThat(item.aiSuggestedCategory()).isEqualTo("当前行分类");
        assertThat(item.aiHint()).isEqualTo("当前行提示");
        assertThat(item.matchStatus()).isEqualTo("MATCHED");
        assertThat(item.amountStatus()).isEqualTo("PASSED");
    }

    @Test
    void unavailableAiDoesNotChangeDeterministicReviewResult() {
        BudgetReviewService service = service(
                List.of(mapping("办公设备及耗材采购", "100.00")),
                new FailingBudgetAiPort());

        BudgetReviewTask task = upload(service, "办公设备及耗材采购\t99.99");
        service.review(task.id());

        BudgetReviewItem item = service.findItems(task.id()).get(0);
        assertThat(item.matchStatus()).isEqualTo("MATCHED");
        assertThat(item.amountStatus()).isEqualTo("PASSED");
        assertThat(item.riskLevel()).isEqualTo("INFO");
        assertThat(item.aiSuggestedCategory()).isNull();
        assertThat(item.aiHint()).isNull();
    }

    @Test
    void unsupportedFileAndMissingTaskAreBusinessErrors() {
        BudgetReviewService service = service(List.of(mapping("办公设备采购", "100.00")));
        assertThatThrownBy(() -> service.uploadAndParse(
                "material.zip", "application/zip", 10,
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请上传");
        assertThatThrownBy(() -> service.review(UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("任务不存在");
    }

    private BudgetReviewService service(List<BudgetSubjectMapping> mappings) {
        return service(mappings, null);
    }

    private BudgetReviewService service(
            List<BudgetSubjectMapping> mappings,
            BudgetSemanticAnalysisPort aiPort) {
        BudgetSubjectMappingRepository mappingRepository = new BudgetSubjectMappingRepository() {
            @Override
            public List<BudgetSubjectMapping> findBySystemSubjectCode(String code) {
                return mappings.stream().filter(item -> item.systemSubjectCode().equals(code)).toList();
            }

            @Override
            public List<BudgetSubjectMapping> findAllActive() {
                return mappings.stream().filter(item -> "ACTIVE".equals(item.status())).toList();
            }
        };
        BudgetMaterialParser parser = new BudgetMaterialParser() {
            @Override
            public boolean supports(String fileName, String contentType) {
                return fileName.endsWith(".txt");
            }

            @Override
            public List<BudgetMaterialLine> parse(java.io.InputStream inputStream, String fileName)
                    throws java.io.IOException {
                String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                var lines = new java.util.ArrayList<BudgetMaterialLine>();
                for (String row : content.split("\n")) {
                    String[] fields = row.split("\\t", -1);
                    String amountText = fields.length > 1 ? fields[1] : null;
                    lines.add(new BudgetMaterialLine(
                            lines.size() + 2, fields.length > 2 ? fields[2] : "M1001", fields.length > 0 ? fields[0] : null,
                            amountText, BudgetTextNormalizer.amount(amountText), "供应商", "P001"));
                }
                return lines;
            }
        };
        BudgetReviewTaskRepository taskRepository = new InMemoryBudgetReviewTaskRepository();
        BudgetReviewItemRepository itemRepository = new InMemoryBudgetReviewItemRepository();
        BudgetSemanticAnalysisUseCase semanticAnalysis = aiPort == null
                ? null : new BudgetSemanticAnalysisUseCase(itemRepository, aiPort);
        return new BudgetReviewService(
                taskRepository,
                itemRepository,
                mappingRepository,
                List.of(parser),
                logger,
                new BudgetValidationConfirmLogRepository() {
                    @Override
                    public void save(BudgetValidationConfirmLog log) {
                        confirmationWrites++;
                    }

                    @Override
                    public List<BudgetValidationConfirmLog> findByBatchId(UUID batchId) {
                        return List.of();
                    }
                },
                new BudgetCategoryRuleRepository() {},
                new BudgetProjectConfigRepository() {},
                new BudgetYearValidityConfigRepository() {},
                command -> new BlacklistCheckPort.CheckOutcome(
                        command.batchId(), command.supplierName(), command.supplierCode(),
                        false, List.of(), null),
                semanticAnalysis,
                new InMemoryBudgetMaterialStorage());
    }

    private static BudgetReviewTask upload(BudgetReviewService service, String content) {
        return service.uploadAndParse(
                "material.txt", "text/plain", content.getBytes(StandardCharsets.UTF_8).length,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static BudgetSubjectMapping mapping(String name, String amount) {
        return new BudgetSubjectMapping(
                UUID.randomUUID(), "M1001", name, "M1001", name,
                new BigDecimal(amount), "2024", "供应商", "ACTIVE");
    }

    private static final class RecordingBudgetAiPort
            implements BudgetSemanticAnalysisPort {
        private int requestCount;
        private boolean transactionActive;

        @Override
        public BudgetSemanticAnalysisResult analyzeBudgetSemantics(
                BudgetSemanticAnalysisPort.BudgetSemanticAnalysisRequest request) {
            requestCount++;
            transactionActive |= TransactionSynchronizationManager.isActualTransactionActive();
            return new BudgetSemanticAnalysisResult(
                    true,
                    request.items().stream()
                            .map(item -> new BudgetSemanticAnalysisResult.Item(
                                    item.itemId(), "办公用品", "语义辅助提示"))
                            .toList(),
                    "qwen-test", 5L);
        }
    }

    private static final class FailingBudgetAiPort
            implements BudgetSemanticAnalysisPort {
        @Override
        public BudgetSemanticAnalysisResult analyzeBudgetSemantics(
                BudgetSemanticAnalysisPort.BudgetSemanticAnalysisRequest request) {
            throw new IllegalStateException("AI unavailable");
        }
    }

    private static final class ReorderedBudgetAiPort
            implements BudgetSemanticAnalysisPort {
        @Override
        public BudgetSemanticAnalysisResult analyzeBudgetSemantics(
                BudgetSemanticAnalysisPort.BudgetSemanticAnalysisRequest request) {
            UUID currentItemId = request.items().get(0).itemId();
            return new BudgetSemanticAnalysisResult(
                    true,
                    List.of(
                            new BudgetSemanticAnalysisResult.Item(
                                    UUID.randomUUID(), "错误行分类", "错误行提示"),
                            new BudgetSemanticAnalysisResult.Item(
                                    currentItemId, "当前行分类", "当前行提示")),
                    "qwen-test", 5L);
        }
    }
}
