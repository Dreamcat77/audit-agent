package com.fuquan.audit.procurement.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.procurement.application.port.ProcurementProjectQueryPort;
import com.fuquan.audit.procurement.application.port.ProcurementReviewWorkflowRepository;
import com.fuquan.audit.procurement.application.port.ProcurementTenderDocumentRepository;
import com.fuquan.audit.procurement.application.port.ProcurementTenderFindingRepository;
import com.fuquan.audit.procurement.application.port.ProcurementTenderRuleRepository;
import com.fuquan.audit.procurement.application.port.ProcurementTenderReviewAiPort;
import com.fuquan.audit.procurement.domain.ProcurementProject;
import com.fuquan.audit.procurement.domain.ProcurementReviewStep;
import com.fuquan.audit.procurement.domain.ProcurementReviewWorkflow;
import com.fuquan.audit.procurement.domain.ProcurementTenderDocument;
import com.fuquan.audit.procurement.domain.ProcurementTenderDocumentStatus;
import com.fuquan.audit.procurement.domain.ProcurementTenderFinding;
import com.fuquan.audit.procurement.domain.ProcurementTenderParseResult;
import com.fuquan.audit.procurement.domain.ProcurementTenderRule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProcurementTenderRuleScanServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-23T01:00:00Z");

    private final UUID projectId = UUID.randomUUID();
    private final UUID reviewId = UUID.randomUUID();
    private final InMemoryDocuments documents = new InMemoryDocuments();
    private final InMemoryFindings findings = new InMemoryFindings();
    private final ProcurementTenderRuleRepository rules = new RuleRepository(TenderRuleCatalog.defaults(NOW));
    private ProcurementTenderRuleScanService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new ProcurementTenderRuleScanService(new Projects(projectId), documents, rules, findings,
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
        documents.documents.put(reviewId, document(ProcurementTenderDocumentStatus.AVAILABLE,
                new ProcurementTenderParseResult("招标文件正文", List.of(
                        new ProcurementTenderParseResult.Segment("pdf/page:3",
                                "必须使用某品牌产品，本地注册企业优先，不接受联合体。", 3, 1.0),
                        new ProcurementTenderParseResult.Segment("pdf/page:4",
                                "供应商注册资本不低于1000万元，必须具有ISO认证，必须支持某系统。", 4, 1.0)))));
    }

    @Test
    void scansBuiltInRulesAndPreservesSourceLocation() {
        var result = service.scan(projectId, reviewId);

        assertThat(result.findingCount()).isEqualTo(8);
        assertThat(result.findings()).extracting(ProcurementTenderFinding::ruleCode)
                .containsExactlyInAnyOrder(
                        "F07-BRAND-002", "F07-BRAND-004", "F07-REGION-004", "F07-EXCLUSIVE-001",
                        "F07-QUAL-001", "F07-QUAL-002", "F07-TECH-002", "F07-TECH-003");
        assertThat(result.findings()).anySatisfy(finding -> {
            assertThat(finding.riskLevel()).isEqualTo("CRITICAL");
            assertThat(finding.sourceLocator()).isEqualTo("pdf/page:3");
            assertThat(finding.pageNumber()).isEqualTo(3);
            assertThat(finding.matchedText()).contains("品牌");
            assertThat(finding.remediationSuggestion()).contains("性能参数");
        });
        assertThat(result.findings()).anySatisfy(finding -> {
            assertThat(finding.ruleCode()).isEqualTo("F07-QUAL-001");
            assertThat(finding.sourceLocator()).isEqualTo("pdf/page:4");
            assertThat(finding.matchedText()).contains("注册资本不低于");
        });
    }

    @Test
    void rescanningReplacesPreviousFindings() {
        var first = service.scan(projectId, reviewId);
        var second = service.scan(projectId, reviewId);

        assertThat(findings.saved).hasSize(1);
        assertThat(findings.saved.get(reviewId)).hasSize(first.findingCount()).hasSize(second.findingCount());
        assertThat(findings.replaceCount).isEqualTo(2);
    }

    @Test
    void disabledRulesAreSkipped() {
        ProcurementTenderRule disabled = new ProcurementTenderRule(
                UUID.randomUUID(), "DISABLED", "停用规则", "指定品牌", "KEYWORD", "品牌", "WARNING",
                "无需整改", "MANUAL", false, NOW, NOW);
        ProcurementTenderRuleScanService disabledService = new ProcurementTenderRuleScanService(
                new Projects(projectId), documents, new RuleRepository(List.of(disabled)), findings,
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(disabledService.scan(projectId, reviewId).findings()).isEmpty();
    }

    @Test
    void documentMustBeAvailable() throws Exception {
        documents.documents.put(reviewId, document(ProcurementTenderDocumentStatus.PARSE_FAILED,
                new ProcurementTenderParseResult("", List.of())));

        assertThatThrownBy(() -> service.scan(projectId, reviewId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("尚未成功解析");
    }

    @Test
    void reviewMustBelongToProject() {
        UUID anotherProject = UUID.randomUUID();
        assertThatThrownBy(() -> service.scan(anotherProject, reviewId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("采购项目不存在");
    }

    @Test
    void invalidParsedJsonReturnsClearValidationError() {
        ProcurementTenderDocument source = documents.documents.get(reviewId);
        documents.documents.put(reviewId, new ProcurementTenderDocument(source.id(), source.projectId(),
                source.fileName(), source.fileExtension(), source.contentType(), source.fileSize(), source.sha256(),
                source.storageKey(), source.status(), source.parseMethod(), source.parseEngine(),
                source.parseConfidence(), source.pageCount(), "{invalid", source.parseError(), source.createdBy(),
                source.createdTime(), source.updatedTime()));

        assertThatThrownBy(() -> service.scan(projectId, reviewId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("解析结果格式无效");
    }

    @Test
    void noMatchReturnsEmptyResultAndClearsPreviousFindings() throws Exception {
        var first = service.scan(projectId, reviewId);
        ProcurementTenderDocument source = documents.documents.get(reviewId);
        ProcurementTenderParseResult parsed = new ProcurementTenderParseResult("普通采购说明", List.of(
                new ProcurementTenderParseResult.Segment("docx/body/paragraph:18", "符合公开竞争要求", null, null)));
        documents.documents.put(reviewId, document(ProcurementTenderDocumentStatus.AVAILABLE, parsed));

        var second = service.scan(projectId, reviewId);

        assertThat(first.findingCount()).isGreaterThan(0);
        assertThat(second.findings()).isEmpty();
        assertThat(findings.saved.get(reviewId)).isEmpty();
        assertThat(source).isNotNull();
    }

    @Test
    void builtInCatalogContainsAllListedRulesAndSources() {
        List<ProcurementTenderRule> catalog = TenderRuleCatalog.defaults(NOW);

        assertThat(catalog).hasSize(62).allSatisfy(rule -> {
            assertThat(rule.source()).isEqualTo("BUILTIN");
            assertThat(rule.enabled()).isTrue();
            assertThat(rule.remediationSuggestion()).isNotBlank();
            assertThat(rule.legalBasis()).isNotBlank();
            assertThat(rule.ruleExplanation()).isNotBlank();
        });
        assertThat(catalog).extracting(ProcurementTenderRule::riskCategory)
                .contains(
                        "指定品牌", "地域限制", "排他性条件", "资质门槛", "技术参数倾向",
                        "评分标准倾向", "付款条件", "工期要求", "售后服务");
        assertThat(catalog).extracting(ProcurementTenderRule::ruleCode)
                .contains("F07-BRAND-008", "F07-REGION-008", "F07-EXCLUSIVE-008", "F07-QUAL-008",
                        "F07-TECH-008", "F07-SCORE-006", "F07-PAYMENT-006", "F07-DURATION-005",
                        "F07-SERVICE-005");
    }

    @Test
    void broadRegionRuleDoesNotMatchAdministrativeOrganizationName() throws Exception {
        documents.documents.put(reviewId, document(ProcurementTenderDocumentStatus.AVAILABLE,
                new ProcurementTenderParseResult("本市财政局发布公告", List.of(
                        new ProcurementTenderParseResult.Segment("pdf/page:1", "本市财政局发布公告", 1, 1.0)))));

        assertThat(service.scan(projectId, reviewId).findings())
                .noneMatch(finding -> finding.ruleCode().equals("F07-REGION-001"));
    }

    @Test
    void mergesAiFindingWithRuleFindingAndKeepsAiOnlyFinding() throws Exception {
        documents.documents.put(reviewId, document(ProcurementTenderDocumentStatus.AVAILABLE,
                new ProcurementTenderParseResult("招标文件正文", List.of(
                        new ProcurementTenderParseResult.Segment("pdf/page:3", "必须使用某品牌产品。", 3, 1.0),
                        new ProcurementTenderParseResult.Segment("pdf/page:5", "不得因既往合作关系排除其他投标人。", 5, 1.0)))));
        ProcurementTenderReviewAiPort ai = request -> new ProcurementTenderReviewAiPort.Result("test-model", 4,
                List.of(
                        new ProcurementTenderReviewAiPort.Finding("必须使用某品牌产品", "pdf/page:3", 3,
                                "指定品牌", "INFO", "AI 识别到品牌指向，但需核对同等产品说明", "改为性能参数", 0.93),
                        new ProcurementTenderReviewAiPort.Finding("不得因既往合作关系排除其他投标人。", "pdf/page:5", 5,
                                "排他性条件", "WARNING", "AI 识别到隐性排他性", "删除排除性表述", 0.88)));
        ProcurementTenderRuleScanService aiService = new ProcurementTenderRuleScanService(new Projects(projectId),
                documents, rules, findings, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), ai, 1000L);

        var result = aiService.scan(projectId, reviewId);

        assertThat(result.aiStatus()).isEqualTo(ProcurementTenderRuleScanService.AiStatus.AVAILABLE);
        assertThat(result.findings()).anySatisfy(finding -> {
            assertThat(finding.source()).isEqualTo("RULE_AND_AI");
            assertThat(finding.riskLevel()).isEqualTo("CRITICAL");
            assertThat(finding.riskReason()).contains("AI 识别到品牌指向");
        });
        assertThat(result.findings()).anySatisfy(finding -> {
            assertThat(finding.source()).isEqualTo("AI");
            assertThat(finding.riskReason()).contains("隐性排他性");
        });
    }

    @Test
    void differentRiskCategoriesOnTheSameTextAreNotMerged() throws Exception {
        String text = "必须使用某品牌产品，交付期限前后不一致";
        documents.documents.put(reviewId, document(ProcurementTenderDocumentStatus.AVAILABLE,
                new ProcurementTenderParseResult(text, List.of(
                        new ProcurementTenderParseResult.Segment("pdf/page:3", text, 3, 1.0)))));
        ProcurementTenderReviewAiPort ai = request -> new ProcurementTenderReviewAiPort.Result("test", 1,
                List.of(new ProcurementTenderReviewAiPort.Finding(text, "pdf/page:3", 3,
                        "工期要求", "WARNING", "需核对交付期限", "统一期限", 0.9)));
        var aiService = new ProcurementTenderRuleScanService(new Projects(projectId), documents, rules, findings,
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), ai, 1000L);
        try {
            var result = aiService.scan(projectId, reviewId);
            assertThat(result.findings()).anyMatch(f -> f.riskCategory().equals("指定品牌") && f.source().equals("RULE"));
            assertThat(result.findings()).anyMatch(f -> f.riskCategory().equals("工期要求") && f.source().equals("AI")
                    && f.remediationSuggestion().equals("统一期限"));
        } finally { aiService.closeAiExecutor(); }
    }

    @Test
    void timedOutAiWorkIsInterruptedInsteadOfOccupyingTheSharedPool() throws Exception {
        var stopped = new java.util.concurrent.CountDownLatch(1);
        ProcurementTenderReviewAiPort slowAi = request -> {
            try { new java.util.concurrent.CountDownLatch(1).await(); }
            catch (InterruptedException interrupted) { stopped.countDown(); Thread.currentThread().interrupt(); }
            return new ProcurementTenderReviewAiPort.Result("interrupted-model", 0, List.of());
        };
        var bounded = new ProcurementTenderRuleScanService(new Projects(projectId), documents, rules, findings,
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), slowAi, 100L);
        try {
            assertThat(bounded.scan(projectId, reviewId).aiStatus()).isEqualTo(ProcurementTenderRuleScanService.AiStatus.AI_TIMEOUT);
            assertThat(stopped.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally { bounded.closeAiExecutor(); }
    }

    @Test
    void saturatedAiWorkersDoNotQueueMoreRequests() throws Exception {
        var started = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        ProcurementTenderReviewAiPort busyAi = request -> {
            calls.incrementAndGet(); started.countDown();
            try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return new ProcurementTenderReviewAiPort.Result("bounded-model", 0, List.of());
        };
        var bounded = new ProcurementTenderRuleScanService(new Projects(projectId), documents, rules, findings,
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), busyAi, 5000L);
        var callers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first=callers.submit(() -> bounded.scan(projectId, reviewId));
            var second=callers.submit(() -> bounded.scan(projectId, reviewId));
            assertThat(started.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(bounded.scan(projectId, reviewId).aiStatus()).isEqualTo(ProcurementTenderRuleScanService.AiStatus.AI_UNAVAILABLE);
            assertThat(calls.get()).isEqualTo(2);
            release.countDown(); first.get(2, java.util.concurrent.TimeUnit.SECONDS); second.get(2, java.util.concurrent.TimeUnit.SECONDS);
        } finally { release.countDown(); callers.shutdownNow(); bounded.closeAiExecutor(); }
    }

    @Test
    void aiTimeoutKeepsRuleFindingsAndReportsDegradedStatus() throws Exception {
        ProcurementTenderReviewAiPort slowAi = request -> {
            try {
                Thread.sleep(100L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return new ProcurementTenderReviewAiPort.Result("slow-model", 100L, List.of());
        };
        ProcurementTenderRuleScanService aiService = new ProcurementTenderRuleScanService(new Projects(projectId),
                documents, rules, findings, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), slowAi, 10L);

        var result = aiService.scan(projectId, reviewId);

        assertThat(result.aiStatus()).isEqualTo(ProcurementTenderRuleScanService.AiStatus.AI_TIMEOUT);
        assertThat(result.findings()).isNotEmpty().allSatisfy(finding ->
                assertThat(finding.source()).isEqualTo("RULE"));
    }

    @Test
    void invalidAiResponseKeepsRuleFindingsAndReportsDegradedStatus() throws Exception {
        ProcurementTenderReviewAiPort invalidAi = request -> {
            throw new ProcurementTenderReviewAiPort.InvalidResponseException(
                    new IllegalArgumentException("invalid response"));
        };
        ProcurementTenderRuleScanService aiService = new ProcurementTenderRuleScanService(new Projects(projectId),
                documents, rules, findings, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), invalidAi, 1000L);

        var result = aiService.scan(projectId, reviewId);

        assertThat(result.aiStatus()).isEqualTo(ProcurementTenderRuleScanService.AiStatus.AI_INVALID_RESPONSE);
        assertThat(result.findings()).isNotEmpty();
    }

    @Test
    void correctedRegexBoundariesMatchRepresentativeF07Phrases() throws Exception {
        List<ProcurementTenderParseResult.Segment> segments = List.of(
                new ProcurementTenderParseResult.Segment("pdf/page:1", "服务器必须采用华为FusionServer系列或同等性能产品", 1, 1.0),
                new ProcurementTenderParseResult.Segment("pdf/page:2", "必须支持华为VRP操作系统", 2, 1.0),
                new ProcurementTenderParseResult.Segment("pdf/page:3", "必须使用华为OceanStor系列产品", 3, 1.0),
                new ProcurementTenderParseResult.Segment("pdf/page:4", "必须提供华为或联想品牌的授权书", 4, 1.0));
        documents.documents.put(reviewId, document(ProcurementTenderDocumentStatus.AVAILABLE,
                new ProcurementTenderParseResult("正则转义回归样例", segments)));

        assertThat(service.scan(projectId, reviewId).findings())
                .extracting(ProcurementTenderFinding::ruleCode)
                .contains("F07-TECH-001", "F07-TECH-002", "F07-TECH-003", "F07-BRAND-003");
    }

    @Test
    void builtInRegexDoesNotContainDatabaseNewlineEscapes() {
        assertThat(TenderRuleCatalog.defaults(NOW))
                .filteredOn(rule -> rule.matchType().equals("REGEX"))
                .allSatisfy(rule -> assertThat(rule.matchContent()).doesNotContain("\\r", "\\n"));
    }

    @Test
    void everyBuiltInRuleMatchesItsRepresentativePhrase() throws Exception {
        String text = String.join("。",
                "指定品牌", "必须使用某品牌", "某品牌授权", "某品牌产品", "仅限某品牌", "建议采用某品牌",
                "必须兼容某品牌", "与现有系统同品牌", "本市企业", "本地企业", "本地设有服务点", "本地注册",
                "本地服务", "本地纳税", "本地社保", "注册地在北京市", "不接受联合体", "独家授权", "唯一代理",
                "唯一供应商", "不接受分包", "必须为原厂", "必须为厂家直投", "不接受代理商投标",
                "注册资本不低于1000万", "必须具有ISO认证", "必须具有相关资质", "近3年具有相关业绩",
                "具有相关行业经验5年以上", "必须具有相关证书", "必须通过ISO认证", "具有一级等级资质",
                "必须采用某系列", "必须支持某系统", "必须使用某产品", "必须兼容某系统", "必须与原方案一致",
                "特定设备技术参数", "必须满足国家标准", "仅支持TCP协议", "主观分占比30%以上",
                "评分标准不明确", "由评委主观打分", "特定专家打分", "评分标准模糊", "主观评分20分以上",
                "预付款比例30%以上", "质保金比例5%以下", "付款周期90天以上", "验收后2年内付款",
                "无预付款", "付款条件苛刻", "工期30天以内", "必须2小时内响应", "必须在5天内完成",
                "工期明显短于常规", "紧急工期", "要求本地服务团队", "响应时间2小时以内", "服务期限3年以上",
                "必须提供驻场服务", "7×24小时服务");
        documents.documents.put(reviewId, document(ProcurementTenderDocumentStatus.AVAILABLE,
                new ProcurementTenderParseResult(text, List.of(
                        new ProcurementTenderParseResult.Segment("docx/body/paragraph:1", text, null, null)))));

        assertThat(service.scan(projectId, reviewId).findings())
                .extracting(ProcurementTenderFinding::ruleCode)
                .containsExactlyInAnyOrderElementsOf(rules.findAll().stream()
                        .map(ProcurementTenderRule::ruleCode).toList());
    }

    @Test
    void scanCompletesTenderWorkflowStepAndAdvancesToReport() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ProcurementReviewWorkflowService workflowService = new ProcurementReviewWorkflowService(
                new ProcurementProjectQueryService(new Projects(projectId)), new MemoryWorkflowRepository(), clock);
        workflowService.start(projectId);
        workflowService.recordQuoteCheck(projectId, "询比价分析完成");
        workflowService.recordBlacklistCheck(projectId, "黑名单比对完成");
        ProcurementTenderRuleScanService workflowAwareService = new ProcurementTenderRuleScanService(
                new Projects(projectId), documents, rules, findings, new ObjectMapper(), clock, null, 1_000L,
                workflowService);

        workflowAwareService.scan(projectId, reviewId);

        ProcurementReviewWorkflowService.State workflow = workflowService.require(projectId);
        ProcurementReviewStep tenderStep = workflow.steps().stream()
                .filter(step -> ProcurementReviewWorkflowService.TENDER_DOCUMENT.equals(step.stepCode()))
                .findFirst().orElseThrow();
        assertThat(tenderStep.status()).isEqualTo(ProcurementReviewStep.COMPLETED);
        assertThat(tenderStep.completedTime()).isEqualTo(NOW);
        assertThat(tenderStep.resultSummary()).contains("共 ");
        assertThat(workflow.workflow().currentStepOrder()).isEqualTo(7);
    }

    private ProcurementTenderDocument document(ProcurementTenderDocumentStatus status,
            ProcurementTenderParseResult parsed) throws Exception {
        return new ProcurementTenderDocument(reviewId, projectId, "招标文件.pdf", "pdf", "application/pdf", 100,
                "a".repeat(64), "procurement/" + reviewId, status, null, null, null, 4,
                new ObjectMapper().writeValueAsString(parsed), null, "user-1", NOW, NOW);
    }

    private static final class Projects implements ProcurementProjectQueryPort {
        private final UUID projectId;

        private Projects(UUID projectId) { this.projectId = projectId; }

        @Override
        public Page findPage(String status, String keyword, int offset, int limit) {
            return new Page(List.of(), 0);
        }

        @Override
        public Optional<ProcurementProject> findById(UUID id) {
            return id.equals(projectId)
                    ? Optional.of(new ProcurementProject(projectId, "PR-1", "测试采购项目", "M1001", "办公设备",
                    null, null, "DRAFT", NOW))
                    : Optional.empty();
        }
    }

    private static final class RuleRepository implements ProcurementTenderRuleRepository {
        private final List<ProcurementTenderRule> all;

        private RuleRepository(List<ProcurementTenderRule> all) { this.all = List.copyOf(all); }

        @Override
        public List<ProcurementTenderRule> findAll() { return all; }

        @Override
        public List<ProcurementTenderRule> findEnabled() {
            return all.stream().filter(ProcurementTenderRule::enabled).toList();
        }
    }

    private static final class InMemoryDocuments implements ProcurementTenderDocumentRepository {
        private final Map<UUID, ProcurementTenderDocument> documents = new HashMap<>();

        @Override
        public void save(ProcurementTenderDocument document) { documents.put(document.id(), document); }

        @Override
        public void update(ProcurementTenderDocument document) { documents.put(document.id(), document); }

        @Override
        public Optional<ProcurementTenderDocument> findById(UUID id) { return Optional.ofNullable(documents.get(id)); }

        @Override
        public Optional<ProcurementTenderDocument> findLatestByProjectId(UUID projectId) {
            return documents.values().stream().filter(document -> document.projectId().equals(projectId)).findFirst();
        }
    }

    private static final class InMemoryFindings implements ProcurementTenderFindingRepository {
        private final Map<UUID, List<ProcurementTenderFinding>> saved = new HashMap<>();
        private int replaceCount;

        @Override
        public synchronized void replaceForReview(UUID projectId, UUID reviewId, List<ProcurementTenderFinding> findings) {
            saved.put(reviewId, List.copyOf(findings));
            replaceCount++;
        }

        @Override
        public List<ProcurementTenderFinding> findByReviewId(UUID projectId, UUID reviewId) {
            return saved.getOrDefault(reviewId, List.of());
        }
    }

    private static final class MemoryWorkflowRepository implements ProcurementReviewWorkflowRepository {
        private Snapshot snapshot;

        @Override
        public void create(ProcurementReviewWorkflow workflow, List<ProcurementReviewStep> steps) {
            snapshot = new Snapshot(workflow, steps);
        }

        @Override
        public void update(ProcurementReviewWorkflow workflow, List<ProcurementReviewStep> steps) {
            snapshot = new Snapshot(workflow, steps);
        }

        @Override
        public Optional<Snapshot> findByProjectId(UUID projectId) {
            return snapshot != null && snapshot.workflow().projectId().equals(projectId)
                    ? Optional.of(snapshot) : Optional.empty();
        }
    }
}
