package com.fuquan.audit.procurement.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.PipelineDiagnostics;
import com.fuquan.audit.procurement.application.port.ProcurementProjectQueryPort;
import com.fuquan.audit.procurement.application.port.ProcurementTenderDocumentRepository;
import com.fuquan.audit.procurement.application.port.ProcurementTenderFindingRepository;
import com.fuquan.audit.procurement.application.port.ProcurementTenderRuleRepository;
import com.fuquan.audit.procurement.application.port.ProcurementTenderReviewAiPort;
import com.fuquan.audit.procurement.domain.ProcurementTenderDocument;
import com.fuquan.audit.procurement.domain.ProcurementTenderDocumentStatus;
import com.fuquan.audit.procurement.domain.ProcurementTenderFinding;
import com.fuquan.audit.procurement.domain.ProcurementTenderParseResult;
import com.fuquan.audit.procurement.domain.ProcurementTenderRule;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** F07 第二步：只使用可解释关键词/正则规则扫描招标文件。 */
@Service
public class ProcurementTenderRuleScanService {
    private static final Logger LOG = LoggerFactory.getLogger(ProcurementTenderRuleScanService.class);
    private static final int MAX_SNIPPET_LENGTH = 240;

    private final ProcurementProjectQueryPort projectQueryPort;
    private final ProcurementTenderDocumentRepository documentRepository;
    private final ProcurementTenderRuleRepository ruleRepository;
    private final ProcurementTenderFindingRepository findingRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ProcurementTenderReviewAiPort aiPort;
    private final long aiTimeoutMs;
    private final ProcurementReviewWorkflowService workflowService;
    private ProcurementTenderScanCommitter committer;
    private final ThreadPoolExecutor aiExecutor = new ThreadPoolExecutor(0, 2, 30L, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> {
                Thread thread = new Thread(task, "procurement-tender-ai");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    @jakarta.annotation.PreDestroy
    public void closeAiExecutor() { aiExecutor.shutdownNow(); }

    @Autowired
    public void setCommitter(ProcurementTenderScanCommitter committer) { this.committer=committer; }

    public ProcurementTenderRuleScanService(ProcurementProjectQueryPort projectQueryPort,
            ProcurementTenderDocumentRepository documentRepository,
            ProcurementTenderRuleRepository ruleRepository,
            ProcurementTenderFindingRepository findingRepository,
            ObjectMapper objectMapper, Clock clock) {
        this(projectQueryPort, documentRepository, ruleRepository, findingRepository, objectMapper, clock,
                null, 950_000L, null);
    }

    public ProcurementTenderRuleScanService(ProcurementProjectQueryPort projectQueryPort,
            ProcurementTenderDocumentRepository documentRepository,
            ProcurementTenderRuleRepository ruleRepository,
            ProcurementTenderFindingRepository findingRepository,
            ObjectMapper objectMapper, Clock clock, ProcurementTenderReviewAiPort aiPort,
            @Value("${audit.procurement-tender-review.ai-timeout-ms:950000}") long aiTimeoutMs) {
        this(projectQueryPort, documentRepository, ruleRepository, findingRepository, objectMapper, clock, aiPort,
                aiTimeoutMs, null);
    }

    @Autowired
    public ProcurementTenderRuleScanService(ProcurementProjectQueryPort projectQueryPort,
            ProcurementTenderDocumentRepository documentRepository,
            ProcurementTenderRuleRepository ruleRepository,
            ProcurementTenderFindingRepository findingRepository,
            ObjectMapper objectMapper, Clock clock, ProcurementTenderReviewAiPort aiPort,
            @Value("${audit.procurement-tender-review.ai-timeout-ms:950000}") long aiTimeoutMs,
            ProcurementReviewWorkflowService workflowService) {
        this.projectQueryPort = projectQueryPort;
        this.documentRepository = documentRepository;
        this.ruleRepository = ruleRepository;
        this.findingRepository = findingRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.aiPort = aiPort;
        this.aiTimeoutMs = Math.max(1L, aiTimeoutMs);
        this.workflowService = workflowService;
    }

    public ScanResult scan(UUID projectId, UUID reviewId) {
        ProcurementTenderDocument document = requireAvailableDocument(projectId, reviewId);
        ProcurementTenderParseResult parsed;
        List<ProcurementTenderFinding> findings;
        AiAnalysis aiAnalysis;
        try (var span = PipelineDiagnostics.start("PROCUREMENT", "F10_TENDER_REVIEW", reviewId,
                java.util.Map.of())) {
          try {
            parsed = parseResult(document);
            List<ProcurementTenderRule> rules = ruleRepository.findEnabled();
            List<ProcurementTenderFinding> ruleFindings = parsed.segments().stream()
                    .flatMap(segment -> rules.stream().map(rule -> match(rule, segment, projectId, reviewId)))
                    .filter(Objects::nonNull).toList();
            aiAnalysis = analyzeWithAi(projectId, reviewId, parsed, ruleFindings);
            findings = mergeFindings(ruleFindings, aiAnalysis.findings());
            if (committer != null) committer.commit(projectId, reviewId, findings, aiAnalysis.status().name());
            else findingRepository.replaceForReview(projectId, reviewId, findings);
            span.outcome(aiAnalysis.status().name(), java.util.Map.of("segmentCount", parsed.segments().size(),
                    "ruleCount", rules.size(), "findingCount", findings.size(),
                    "inputChars", parsed.fullText().codePointCount(0, parsed.fullText().length())));
          } catch (RuntimeException failure) {
            span.fail(failure);
            throw failure;
          }
        }
        if (committer == null && workflowService != null) {
            ProcurementReviewWorkflowService.State workflow = workflowService.recordTenderReview(projectId,
                    "已完成招标文件规则扫描和 AI 语义分析，共 " + findings.size() + " 条风险");
            LOG.info("F10 招标文件审查流程已推进 projectId={} completedStep={} currentStepOrder={}",
                    projectId, ProcurementReviewWorkflowService.TENDER_DOCUMENT,
                    workflow.workflow().currentStepOrder());
        }
        LOG.info("F10 招标文件规则及 AI 扫描完成 projectId={} reviewId={} findingCount={} aiStatus={}",
                projectId, reviewId, findings.size(), aiAnalysis.status());
        return new ScanResult(projectId, reviewId, findings.size(), findings, aiAnalysis.status());
    }

    @Transactional(readOnly = true)
    public List<ProcurementTenderFinding> findFindings(UUID projectId, UUID reviewId) {
        requireProject(projectId);
        requireDocument(projectId, reviewId);
        Map<String, ProcurementTenderRule> rules = ruleRepository.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(ProcurementTenderRule::ruleCode, rule -> rule,
                        (left, right) -> left));
        return findingRepository.findByReviewId(projectId, reviewId).stream()
                .map(finding -> enrichRuleMetadata(finding, rules.get(finding.ruleCode())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ProcurementTenderRule> findRules() {
        return ruleRepository.findAll();
    }

    private ProcurementTenderFinding match(ProcurementTenderRule rule,
            ProcurementTenderParseResult.Segment segment, UUID projectId, UUID reviewId) {
        if (segment.text().isBlank()) return null;
        Matcher matcher;
        try {
            matcher = matcher(rule, segment.text());
        } catch (PatternSyntaxException exception) {
            LOG.warn("F07 招标规则正则无效，跳过 ruleCode={}", rule.ruleCode(), exception);
            return null;
        }
        if (!TenderRuleMatcher.nextAffirmative(matcher, segment.text())) return null;
        String matched = snippet(segment.text(), matcher.start(), matcher.end());
        return new ProcurementTenderFinding(UUID.randomUUID(), projectId, reviewId, rule.ruleCode(),
                rule.ruleName(), rule.riskCategory(), rule.riskLevel(), matched, segment.sourceLocator(),
                segment.pageNumber(), rule.remediationSuggestion(), "RULE", null, clock.instant(),
                rule.legalBasis(), rule.ruleExplanation());
    }

    private AiAnalysis analyzeWithAi(UUID projectId, UUID reviewId, ProcurementTenderParseResult parsed,
            List<ProcurementTenderFinding> ruleFindings) {
        try (var span = PipelineDiagnostics.start("PROCUREMENT", "F10_AI_REVIEW", reviewId,
                java.util.Map.of("segmentCount", parsed.segments().size(),
                        "inputChars", parsed.fullText().codePointCount(0, parsed.fullText().length()),
                        "findingCount", ruleFindings.size()))) {
          if (aiPort == null) {
              span.outcome("AI_UNAVAILABLE", java.util.Map.of("reasonCode", "AI_NOT_CONFIGURED"));
              return new AiAnalysis(AiStatus.AI_UNAVAILABLE, List.of());
          }
        ProcurementTenderReviewAiPort.Request request = new ProcurementTenderReviewAiPort.Request(
                projectId, reviewId, parsed.fullText(), parsed.segments().stream()
                        .map(segment -> new ProcurementTenderReviewAiPort.Segment(segment.sourceLocator(),
                                segment.text(), segment.pageNumber(), segment.confidence()))
                        .toList(), ruleFindings.stream()
                        .map(finding -> new ProcurementTenderReviewAiPort.RuleFinding(finding.ruleCode(),
                                finding.riskCategory(), finding.riskLevel(), finding.matchedText(),
                                finding.sourceLocator(), finding.remediationSuggestion()))
                        .toList());
        java.util.concurrent.Future<ProcurementTenderReviewAiPort.Result> future;
        try { future = aiExecutor.submit(() -> aiPort.analyze(request)); }
        catch (RejectedExecutionException saturated) {
            span.outcome("AI_UNAVAILABLE", java.util.Map.of("reasonCode", "AI_CAPACITY_BUSY"));
            return new AiAnalysis(AiStatus.AI_UNAVAILABLE, List.of());
        }
        try {
            ProcurementTenderReviewAiPort.Result result = future.get(aiTimeoutMs, TimeUnit.MILLISECONDS);
            var findings = result.findings().stream()
                    .map(finding -> toAiFinding(projectId, reviewId, finding))
                    .toList();
            span.outcome("AVAILABLE", java.util.Map.of("findingCount", findings.size()));
            return new AiAnalysis(AiStatus.AVAILABLE, findings);
        } catch (java.util.concurrent.TimeoutException exception) {
            future.cancel(true);
            span.outcome("AI_TIMEOUT", java.util.Map.of("reasonCode", "DOWNSTREAM_TIMEOUT"));
            return new AiAnalysis(AiStatus.AI_TIMEOUT, List.of());
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            span.outcome("AI_UNAVAILABLE", java.util.Map.of("reasonCode", "CALL_INTERRUPTED"));
            return new AiAnalysis(AiStatus.AI_UNAVAILABLE, List.of());
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof ProcurementTenderReviewAiPort.InvalidResponseException) {
                span.outcome("AI_INVALID_RESPONSE", java.util.Map.of("reasonCode", "INVALID_PROVIDER_RESPONSE"));
                return new AiAnalysis(AiStatus.AI_INVALID_RESPONSE, List.of());
            }
            if (cause instanceof ProcurementTenderReviewAiPort.TimeoutException) {
                span.outcome("AI_TIMEOUT", java.util.Map.of("reasonCode", "DOWNSTREAM_TIMEOUT"));
                return new AiAnalysis(AiStatus.AI_TIMEOUT, List.of());
            }
            span.outcome("AI_UNAVAILABLE", java.util.Map.of("reasonCode", "AI_CALL_FAILED"));
            return new AiAnalysis(AiStatus.AI_UNAVAILABLE, List.of());
        } catch (RuntimeException exception) {
            span.outcome("AI_UNAVAILABLE", java.util.Map.of("reasonCode", "AI_CALL_FAILED"));
            return new AiAnalysis(AiStatus.AI_UNAVAILABLE, List.of());
        }
        }
    }

    private ProcurementTenderFinding toAiFinding(UUID projectId, UUID reviewId,
            ProcurementTenderReviewAiPort.Finding finding) {
        return new ProcurementTenderFinding(UUID.randomUUID(), projectId, reviewId,
                "AI-" + UUID.randomUUID(), "AI 语义疑似风险", finding.riskCategory(), finding.riskLevel(),
                finding.matchedText(), finding.sourceLocator(), finding.pageNumber(),
                finding.remediationSuggestion(), "AI", finding.rationale(), clock.instant());
    }

    private static List<ProcurementTenderFinding> mergeFindings(List<ProcurementTenderFinding> ruleFindings,
            List<ProcurementTenderFinding> aiFindings) {
        List<ProcurementTenderFinding> merged = new ArrayList<>(ruleFindings);
        for (ProcurementTenderFinding aiFinding : aiFindings) {
            int duplicateIndex = findDuplicate(merged, aiFinding);
            if (duplicateIndex < 0) {
                merged.add(aiFinding);
                continue;
            }
            ProcurementTenderFinding current = merged.get(duplicateIndex);
            String riskLevel = higherRisk(current.riskLevel(), aiFinding.riskLevel());
            String reason = current.riskReason() == null ? aiFinding.riskReason()
                    : current.riskReason() + "；AI：" + aiFinding.riskReason();
            merged.set(duplicateIndex, new ProcurementTenderFinding(current.id(), current.projectId(),
                    current.reviewId(), current.ruleCode(), current.ruleName(), current.riskCategory(), riskLevel,
                    current.matchedText(), current.sourceLocator(), current.pageNumber(),
                    current.remediationSuggestion(), "RULE_AND_AI", reason, current.createdTime(),
                    current.legalBasis(), current.ruleExplanation()));
        }
        return List.copyOf(merged);
    }

    private static int findDuplicate(List<ProcurementTenderFinding> findings,
            ProcurementTenderFinding candidate) {
        for (int index = 0; index < findings.size(); index++) {
            ProcurementTenderFinding existing = findings.get(index);
            if (existing.riskCategory().equals(candidate.riskCategory())
                    && existing.sourceLocator().equals(candidate.sourceLocator())
                    && overlaps(existing.matchedText(), candidate.matchedText())) return index;
        }
        return -1;
    }

    private static boolean overlaps(String left, String right) {
        String a = left.replaceAll("\\s+", "");
        String b = right.replaceAll("\\s+", "");
        return a.contains(b) || b.contains(a);
    }

    private static String higherRisk(String left, String right) {
        return riskRank(left) >= riskRank(right) ? left : right;
    }

    private static ProcurementTenderFinding enrichRuleMetadata(ProcurementTenderFinding finding,
            ProcurementTenderRule rule) {
        if (rule == null || (finding.legalBasis() != null && finding.ruleExplanation() != null)) return finding;
        return new ProcurementTenderFinding(finding.id(), finding.projectId(), finding.reviewId(), finding.ruleCode(),
                finding.ruleName(), finding.riskCategory(), finding.riskLevel(), finding.matchedText(),
                finding.sourceLocator(), finding.pageNumber(), finding.remediationSuggestion(), finding.source(),
                finding.riskReason(), finding.createdTime(), rule.legalBasis(), rule.ruleExplanation());
    }

    private static int riskRank(String value) {
        return switch (value) {
            case "CRITICAL" -> 3;
            case "WARNING" -> 2;
            default -> 1;
        };
    }

    private static Matcher matcher(ProcurementTenderRule rule, String text) {
        return TenderRuleMatcher.matcher(rule,text);
    }

    private static String snippet(String text, int start, int end) {
        int from = Math.max(0, start - 60);
        int to = Math.min(text.length(), Math.max(end, start + 1) + 120);
        String result = text.substring(from, to).strip();
        if (result.length() <= MAX_SNIPPET_LENGTH) return result;
        return result.substring(0, MAX_SNIPPET_LENGTH);
    }

    private ProcurementTenderParseResult parseResult(ProcurementTenderDocument document) {
        if (document.parsedContentJson() == null || document.parsedContentJson().isBlank()) {
            throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                    "招标文件没有可供扫描的解析文本");
        }
        try {
            return objectMapper.readValue(document.parsedContentJson(), ProcurementTenderParseResult.class);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                    "招标文件解析结果格式无效，请重新上传", exception);
        }
    }

    private ProcurementTenderDocument requireAvailableDocument(UUID projectId, UUID reviewId) {
        ProcurementTenderDocument document = requireDocument(projectId, reviewId);
        if (document.status() != ProcurementTenderDocumentStatus.AVAILABLE) {
            throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                    "招标文件尚未成功解析，无法执行关键词扫描: " + document.status());
        }
        return document;
    }

    private ProcurementTenderDocument requireDocument(UUID projectId, UUID reviewId) {
        requireProject(projectId);
        if (reviewId == null) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "招标文件审查记录ID不能为空");
        }
        ProcurementTenderDocument document = documentRepository.findById(reviewId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND,
                        "招标文件审查记录不存在: " + reviewId));
        if (!document.projectId().equals(projectId)) {
            throw new BusinessException(BusinessErrorCode.NOT_FOUND,
                    "招标文件审查记录不属于当前采购项目");
        }
        return document;
    }

    private void requireProject(UUID projectId) {
        if (projectId == null) throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "项目ID不能为空");
        projectQueryPort.findById(projectId).orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND,
                "采购项目不存在: " + projectId));
    }

    public record ScanResult(UUID projectId, UUID reviewId, int findingCount,
                             List<ProcurementTenderFinding> findings, AiStatus aiStatus) {
        public ScanResult(UUID projectId, UUID reviewId, int findingCount,
                List<ProcurementTenderFinding> findings) {
            this(projectId, reviewId, findingCount, findings, AiStatus.AI_UNAVAILABLE);
        }

        public ScanResult {
            findings = List.copyOf(findings);
            Objects.requireNonNull(aiStatus, "aiStatus must not be null");
        }
    }

    public enum AiStatus {
        AVAILABLE, AI_TIMEOUT, AI_UNAVAILABLE, AI_INVALID_RESPONSE
    }

    private record AiAnalysis(AiStatus status, List<ProcurementTenderFinding> findings) {
    }
}
