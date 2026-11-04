package com.fuquan.audit.procurement.application;

import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.procurement.domain.ProcurementProject;
import com.fuquan.audit.procurement.application.port.ProcurementAgentReviewAiPort;
import com.fuquan.audit.procurement.application.port.ProcurementAgentReviewAiPort.Item;
import com.fuquan.audit.procurement.application.port.ProcurementAgentReviewAiPort.ItemResult;
import com.fuquan.audit.procurement.application.port.ProcurementAgentReviewAiPort.Request;
import com.fuquan.audit.procurement.application.port.ProcurementAgentReviewAiPort.Result;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 基于采购项目事实的预审用例：规则负责判定，AI 仅负责解释和建议。 */
@Service
public class ProcurementAgentReviewService {

    private final ProcurementProjectQueryService projectQueryService;
    private final ProcurementAgentReviewAiPort aiPort;
    private final FlowDebugLogger flowDebugLogger;
    private final long aiTimeoutMs;

    /** 保留无 AI 构造入口，便于纯规则单元测试和离线使用。 */
    public ProcurementAgentReviewService(ProcurementProjectQueryService projectQueryService) {
        this(projectQueryService, null, null, 60_000L);
    }

    public ProcurementAgentReviewService(ProcurementProjectQueryService projectQueryService,
                                         ProcurementAgentReviewAiPort aiPort) {
        this(projectQueryService, aiPort, null, 60_000L);
    }

    @Autowired
    public ProcurementAgentReviewService(
            ProcurementProjectQueryService projectQueryService,
            ProcurementAgentReviewAiPort aiPort,
            FlowDebugLogger flowDebugLogger,
            @Value("${audit.procurement-agent-review.ai-timeout-ms:60000}") long aiTimeoutMs) {
        this.projectQueryService = projectQueryService;
        this.aiPort = aiPort;
        this.flowDebugLogger = flowDebugLogger;
        this.aiTimeoutMs = Math.max(1L, aiTimeoutMs);
    }

    public ReviewResult review(UUID projectId) {
        ProcurementProject project = projectQueryService.requireById(projectId);
        List<ReviewItem> items = new ArrayList<>();

        addReasonablenessCheck(items, "PROJECT_NAME", "项目名称合理性", project.projectName(),
                value -> value.length() >= 5 && containsChinese(value) && !isNumeric(value),
                "长度不少于 5 个字符、包含中文且不能为纯数字",
                "项目名称可能过短、未包含中文或为纯数字，请确认是否准确");
        addBudgetCheck(items, project.budgetAmount());
        addReasonablenessCheck(items, "SUBJECT_CODE", "预算科目编码合理性", project.subjectCode(),
                value -> value.matches("(?i)(?=.*[a-z0-9])[a-z0-9._-]{2,64}"),
                "2-64 位字母、数字、点、下划线或横线，至少包含一个字母或数字",
                "预算科目编码格式可能不规范，请确认编码是否准确");
        addReasonablenessCheck(items, "SUBJECT_NAME", "预算科目名称合理性", project.subjectName(),
                value -> value.length() >= 2 && !isNumeric(value),
                "长度不少于 2 个字符且不能为纯数字",
                "预算科目名称可能过短或为纯数字，请确认名称是否准确");
        addReasonablenessCheck(items, "DEPARTMENT_NAME", "申请部门合理性", project.departmentName(),
                value -> value.length() >= 2 && !isNumeric(value),
                "长度不少于 2 个字符且不能为纯数字",
                "申请部门可能过短或为纯数字，请确认部门是否准确");
        addReasonablenessCheck(items, "APPLICANT_NAME", "申请人合理性", project.applicantName(),
                value -> value.length() >= 2 && !isNumeric(value),
                "长度不少于 2 个字符且不能为纯数字",
                "申请人可能过短或为纯数字，请确认申请人是否准确");

        String riskLevel = items.stream()
                .map(ReviewItem::riskLevel)
                .max(ProcurementAgentReviewService::compareRisk)
                .orElse("INFO");
        String suggestion = items.stream()
                .filter(item -> !"PASSED".equals(item.status()))
                .map(ReviewItem::suggestion)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .reduce((left, right) -> left + "；" + right)
                .orElse("已检查的基础信息未发现需要核对的问题");
        ReviewResult ruleResult = new ReviewResult(project.id(), project.projectCode(), project.projectName(),
                riskLevel, suggestion, null, Instant.now(), List.copyOf(items));
        return enrichWithAi(project, ruleResult);
    }

    private ReviewResult enrichWithAi(ProcurementProject project, ReviewResult ruleResult) {
        if (aiPort == null) return ruleResult;

        UUID summaryId = UUID.nameUUIDFromBytes(
                (project.id() + ":agent-review-summary").getBytes(StandardCharsets.UTF_8));
        List<Item> aiItems = new ArrayList<>();
        Map<UUID, ReviewItem> ruleItems = new LinkedHashMap<>();
        for (int index = 0; index < ruleResult.items().size(); index++) {
            ReviewItem item = ruleResult.items().get(index);
            UUID itemId = UUID.nameUUIDFromBytes(
                    (project.id() + ":agent-review-item:" + index).getBytes(StandardCharsets.UTF_8));
            ruleItems.put(itemId, item);
            aiItems.add(new Item(itemId, item.checkType(), item.checkName(), item.status(),
                    item.riskLevel(), item.detail(), item.suggestion(),
                    item.expectedValue(), item.actualValue()));
        }
        aiItems.add(new Item(summaryId, "SUMMARY", "项目整体预审结论", ruleResult.riskLevel(),
                ruleResult.riskLevel(), ruleResult.suggestion(), ruleResult.suggestion(),
                "项目基础信息完整且规则风险已完成判定", project.projectName()));

        Result aiResult;
        try {
            aiResult = CompletableFuture.supplyAsync(() -> aiPort.analyze(new Request(
                            project.id(), project.projectCode(), project.projectName(),
                            project.departmentName(), project.procurementType(), project.procurementMethod(),
                            project.applicantName(), project.budgetAmount() == null
                                    ? null : project.budgetAmount().toPlainString(), aiItems)))
                    .orTimeout(aiTimeoutMs, TimeUnit.MILLISECONDS)
                    .join();
        } catch (CompletionException exception) {
            logAiDegraded(project, exception.getCause() instanceof TimeoutException
                    ? "TIMEOUT" : "ERROR");
            return ruleResult;
        } catch (RuntimeException exception) {
            logAiDegraded(project, "ERROR");
            return ruleResult;
        }
        if (aiResult == null || !aiResult.available()) {
            logAiDegraded(project, "UNAVAILABLE");
            return ruleResult;
        }

        Map<UUID, ItemResult> aiById = new LinkedHashMap<>();
        for (ItemResult item : aiResult.items()) {
            if (item != null && ruleItems.containsKey(item.itemId())) aiById.put(item.itemId(), item);
        }
        List<ReviewItem> enrichedItems = new ArrayList<>();
        for (Map.Entry<UUID, ReviewItem> entry : ruleItems.entrySet()) {
            ItemResult aiItem = aiById.get(entry.getKey());
            ReviewItem ruleItem = entry.getValue();
            if (aiItem == null) {
                enrichedItems.add(ruleItem);
                continue;
            }
            boolean needsConfirmation = ruleItem.needsConfirmation()
                    || Boolean.TRUE.equals(aiItem.needsConfirmation());
            boolean aiRaisedConfirmation = !ruleItem.needsConfirmation()
                    && Boolean.TRUE.equals(aiItem.needsConfirmation())
                    && "PASSED".equals(ruleItem.status());
            enrichedItems.add(new ReviewItem(
                    ruleItem.checkType(), ruleItem.checkName(),
                    aiRaisedConfirmation ? "PENDING_CONFIRMATION" : ruleItem.status(),
                    aiRaisedConfirmation ? "WARNING" : ruleItem.riskLevel(),
                    ruleItem.detail(),
                    aiRaisedConfirmation ? firstNonBlank(aiItem.aiSuggestion(), "请人工确认该信息语义是否准确")
                            : ruleItem.suggestion(),
                    ruleItem.expectedValue(), ruleItem.actualValue(),
                    aiItem.aiExplanation(), aiItem.aiSuggestion(), needsConfirmation));
        }
        String enrichedRisk = enrichedItems.stream().map(ReviewItem::riskLevel)
                .max(ProcurementAgentReviewService::compareRisk).orElse(ruleResult.riskLevel());
        String enrichedSuggestion = enrichedItems.stream()
                .filter(item -> !"PASSED".equals(item.status()))
                .map(ReviewItem::suggestion)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .reduce((left, right) -> left + "；" + right)
                .orElse(ruleResult.suggestion());
        ItemResult summary = aiResult.items().stream()
                .filter(item -> item != null && summaryId.equals(item.itemId()))
                .findFirst().orElse(null);
        // The adapter returns the dedicated summary separately; keep it ahead of the
        // summary item fallback so a valid top-level AI conclusion is not discarded.
        String aiSummary = firstNonBlank(aiResult.aiSummary(),
                summary == null ? null : firstNonBlank(summary.aiExplanation(), summary.aiSuggestion()));
        if (aiSummary == null) {
            aiSummary = enrichedItems.stream()
                    .map(item -> firstNonBlank(item.aiExplanation(), item.aiSuggestion()))
                    .filter(value -> value != null && !value.isBlank())
                    .findFirst()
                    .orElse(null);
        }
        if (aiSummary == null && enrichedItems.stream().noneMatch(item -> item.aiExplanation() != null
                || item.aiSuggestion() != null)) return ruleResult;
        if (flowDebugLogger != null) {
            flowDebugLogger.logAiAnalysis("PROCUREMENT_AGENT_REVIEW_AI_COMPLETE", "PROCUREMENT",
                    "AGENT_REVIEW", project.id().toString(), "SUCCESS", aiResult.model(),
                    aiResult.durationMs(), 0, enrichedItems.size());
        }
        return new ReviewResult(ruleResult.projectId(), ruleResult.projectCode(), ruleResult.projectName(),
                enrichedRisk, enrichedSuggestion, aiSummary, ruleResult.generatedTime(),
                List.copyOf(enrichedItems));
    }

    private void logAiDegraded(ProcurementProject project, String outcome) {
        if (flowDebugLogger != null) {
            flowDebugLogger.log("PROCUREMENT_AGENT_REVIEW_AI_DEGRADED", "PROCUREMENT", "APPLICATION",
                    "AGENT_REVIEW", Map.of("projectId", project.id().toString(), "outcome", outcome));
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static void addReasonablenessCheck(List<ReviewItem> items, String type, String name,
                                               String value, java.util.function.Predicate<String> reasonable,
                                               String expected, String warning) {
        boolean present = value != null && !value.isBlank();
        boolean valid = present && reasonable.test(value.trim());
        String missing = name.replace("合理性", "") + "缺失，请补充";
        items.add(new ReviewItem(type, name,
                !present ? "FAILED" : valid ? "PASSED" : "PENDING_CONFIRMATION",
                !present ? "CRITICAL" : valid ? "INFO" : "WARNING",
                !present ? missing : valid ? "信息已填写且格式合理" : warning,
                !present ? missing : valid ? null : warning,
                expected, value, null, null, present && !valid));
    }

    private static boolean containsChinese(String value) {
        return value.codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private static boolean isNumeric(String value) {
        return value.matches("[0-9０-９]+(?:\\.[0-9０-９]+)?");
    }

    private static void addBudgetCheck(List<ReviewItem> items, BigDecimal amount) {
        boolean valid = amount != null && amount.signum() > 0;
        items.add(new ReviewItem("BUDGET_AMOUNT", "预算金额有效性", valid ? "PASSED" : "FAILED",
                valid ? "INFO" : "CRITICAL",
                valid ? "预算金额已填写且大于零" : "预算金额缺失或不大于零",
                valid ? null : "请补充有效的预算金额", "金额大于 0", amount == null ? null : amount.toPlainString(),
                null, null, false));
    }

    /** Read projection for snapshots produced before the reference-only workflow. */
    static ReviewResult referenceResult(ReviewResult saved) {
        if (saved.items().stream().noneMatch(item -> "PROJECT_STATUS".equals(item.checkType()))) return saved;
        List<ReviewItem> items = saved.items().stream()
                .filter(item -> !"PROJECT_STATUS".equals(item.checkType())).toList();
        String risk = items.stream().map(ReviewItem::riskLevel)
                .max(ProcurementAgentReviewService::compareRisk).orElse("INFO");
        String suggestion = items.stream().filter(item -> !"PASSED".equals(item.status()))
                .map(ReviewItem::suggestion).filter(value -> value != null && !value.isBlank())
                .distinct().reduce((left, right) -> left + "；" + right)
                .orElse("已检查的基础信息未发现需要核对的问题");
        return new ReviewResult(saved.projectId(), saved.projectCode(), saved.projectName(), risk,
                suggestion, null, saved.generatedTime(), List.copyOf(items));
    }

    private static int compareRisk(String left, String right) {
        return Integer.compare(riskRank(left), riskRank(right));
    }

    private static int riskRank(String value) {
        return switch (value) {
            case "CRITICAL" -> 3;
            case "WARNING" -> 2;
            default -> 1;
        };
    }

    public record ReviewResult(UUID projectId, String projectCode, String projectName,
                               String riskLevel, String suggestion, String aiSummary,
                               Instant generatedTime,
                               List<ReviewItem> items) {}

    public record ReviewItem(String checkType, String checkName, String status, String riskLevel,
                              String detail, String suggestion, String expectedValue,
                              String actualValue, String aiExplanation,
                              String aiSuggestion, boolean needsConfirmation) {}
}
