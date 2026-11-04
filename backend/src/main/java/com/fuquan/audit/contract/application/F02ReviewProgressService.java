package com.fuquan.audit.contract.application;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.F02RuleCodes;
import com.fuquan.audit.review.application.FindingAcknowledgementService;
import com.fuquan.audit.review.application.FindingAcknowledgementView;
import com.fuquan.audit.review.application.port.AuditCheckResultRepository;
import com.fuquan.audit.review.application.port.AuditTaskRepository;
import com.fuquan.audit.review.domain.AuditCheckResult;
import com.fuquan.audit.review.domain.AuditTask;
import com.fuquan.audit.review.domain.AuditTaskStatus;
import com.fuquan.audit.review.domain.RiskLevel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** Assembles F02 presentation progress from durable contract and review facts without writing. */
@Service
public class F02ReviewProgressService {

    private static final String CONTRACT_SOURCE_TYPE = "CONTRACT";
    private static final String SOURCE_PREPARATION = "SOURCE_PREPARATION";
    private static final String FINDING = "FINDING";
    private static final List<String> RULE_TITLES = List.of(
            "金额大小写一致性",
            "明细数量与单价计算",
            "明细合计与合同金额",
            "合同金额与中标金额",
            "合同乙方与中标供应商");
    private static final Map<String, RulePresentation> RULE_PRESENTATIONS = rulePresentations();
    private static final Comparator<String> NULLABLE_ITEM_KEY_ORDER =
            Comparator.nullsFirst(Comparator.naturalOrder());
    private static final Comparator<AuditCheckResult> FINDING_ORDER =
            Comparator.comparingInt(F02ReviewProgressService::ruleOrder)
                    .thenComparing(F02ReviewProgressService::unknownRuleCode)
                    .thenComparing(AuditCheckResult::itemKey, NULLABLE_ITEM_KEY_ORDER)
                    .thenComparing(AuditCheckResult::id);

    private final ContractReviewService contractReviewService;
    private final AuditTaskRepository auditTaskRepository;
    private final AuditCheckResultRepository checkResultRepository;
    private final FindingAcknowledgementService acknowledgementService;
    private final FindingSourceProjectionService sourceProjectionService;

    public F02ReviewProgressService(
            ContractReviewService contractReviewService,
            AuditTaskRepository auditTaskRepository,
            AuditCheckResultRepository checkResultRepository,
            FindingAcknowledgementService acknowledgementService) {
        this(contractReviewService, auditTaskRepository, checkResultRepository, acknowledgementService, null);
    }

    @Autowired
    public F02ReviewProgressService(
            ContractReviewService contractReviewService,
            AuditTaskRepository auditTaskRepository,
            AuditCheckResultRepository checkResultRepository,
            FindingAcknowledgementService acknowledgementService,
            FindingSourceProjectionService sourceProjectionService) {
        this.contractReviewService = Objects.requireNonNull(
                contractReviewService, "contractReviewService must not be null");
        this.auditTaskRepository = Objects.requireNonNull(
                auditTaskRepository, "auditTaskRepository must not be null");
        this.checkResultRepository = Objects.requireNonNull(
                checkResultRepository, "checkResultRepository must not be null");
        this.acknowledgementService = Objects.requireNonNull(
                acknowledgementService, "acknowledgementService must not be null");
        this.sourceProjectionService = sourceProjectionService;
    }

    /** Returns progress only when both path identities own the real F02 audit task. */
    @Transactional(readOnly = true)
    public F02ReviewProgress getProgress(UUID contractId, UUID taskId) {
        Contract contract = contractReviewService.getContract(contractId);
        if (!contract.id().equals(contractId)) {
            throw notFound();
        }
        AuditTask task = auditTaskRepository.findById(taskId)
                .filter(found -> found.id().equals(taskId))
                .filter(found -> CONTRACT_SOURCE_TYPE.equals(found.sourceType()))
                .filter(found -> found.sourceId().equals(contractId))
                .filter(found -> ContractReviewService.REVIEW_TYPE.equals(found.reviewType()))
                .orElseThrow(F02ReviewProgressService::notFound);

        List<AuditCheckResult> findings = checkResultRepository.findByTaskId(taskId);
        if (findings.stream().anyMatch(finding -> !finding.taskId().equals(taskId))) {
            throw notFound();
        }
        Map<UUID, FindingAcknowledgementView> acknowledgementsByResult =
                acknowledgementService.findByTaskId(taskId).stream()
                        .collect(Collectors.toUnmodifiableMap(
                                FindingAcknowledgementView::checkResultId,
                                Function.identity()));
        List<AuditCheckResult> orderedFindings = findings.stream().sorted(FINDING_ORDER).toList();

        List<F02ReviewProgressItem> items = new ArrayList<>(orderedFindings.size() + 1);
        items.add(new F02ReviewProgressItem(
                SOURCE_PREPARATION,
                null,
                "读取合同及比对来源",
                0,
                task.status().name(),
                null,
                task.startedAt(),
                null));
        for (int index = 0; index < orderedFindings.size(); index++) {
            AuditCheckResult finding = orderedFindings.get(index);
            items.add(new F02ReviewProgressItem(
                    FINDING,
                    finding.id(),
                    title(finding.ruleCode()),
                    index + 1,
                    finding.checkStatus().name(),
                    finding.riskLevel().name(),
                    finding.createdAt(),
                    acknowledgementsByResult.get(finding.id()),
                    sourceProjectionService == null ? null : sourceProjectionService.project(contract, finding)));
        }

        long riskCount = findings.stream()
                .filter(finding -> finding.riskLevel() != RiskLevel.GREEN)
                .count();
        return new F02ReviewProgress(items.size(), items, riskSummary(riskCount));
    }

    /** Summarizes the same durable progress without treating DONE as complete before risk acknowledgement. */
    public F02ReviewProgressSummary summarize(UUID contractId, AuditTask task) {
        Objects.requireNonNull(task, "task must not be null");
        F02ReviewProgress progress = getProgress(contractId, task.id());
        int pendingIndex = -1;
        long riskCount = 0;
        for (int index = 0; index < progress.items().size(); index++) {
            F02ReviewProgressItem item = progress.items().get(index);
            if (!FINDING.equals(item.type()) || item.risk() == null || "GREEN".equals(item.risk())) {
                continue;
            }
            riskCount++;
            boolean acknowledged = item.checkResultId() != null
                    && item.acknowledgement() != null
                    && item.checkResultId().equals(item.acknowledgement().checkResultId());
            if (!acknowledged && pendingIndex < 0) {
                pendingIndex = index;
            }
        }
        boolean done = task.status() == AuditTaskStatus.DONE;
        boolean materialInsufficient = progress.items().stream()
                .anyMatch(item -> FINDING.equals(item.type()) && "NOT_EVALUATED".equals(item.status()));
        boolean pendingAcknowledgement = done && pendingIndex >= 0;
        int current = done
                ? pendingIndex >= 0 ? Math.min(pendingIndex + 1, progress.total()) : progress.total()
                : task.status() == AuditTaskStatus.PROCESSING && task.startedAt() != null
                        ? Math.min(1, progress.total()) : 0;
        return new F02ReviewProgressSummary(
                current,
                progress.total(),
                done && !pendingAcknowledgement,
                pendingAcknowledgement,
                riskCount,
                materialInsufficient,
                progress.riskSummary());
    }

    private static Map<String, RulePresentation> rulePresentations() {
        if (F02RuleCodes.ALL.size() != RULE_TITLES.size()) {
            throw new IllegalStateException("every F02 rule must have one progress title");
        }
        return IntStream.range(0, F02RuleCodes.ALL.size())
                .boxed()
                .collect(Collectors.toUnmodifiableMap(
                        F02RuleCodes.ALL::get,
                        index -> new RulePresentation(index, RULE_TITLES.get(index))));
    }

    private static int ruleOrder(AuditCheckResult finding) {
        RulePresentation presentation = RULE_PRESENTATIONS.get(finding.ruleCode());
        return presentation == null ? Integer.MAX_VALUE : presentation.order();
    }

    private static String unknownRuleCode(AuditCheckResult finding) {
        return RULE_PRESENTATIONS.containsKey(finding.ruleCode()) ? "" : finding.ruleCode();
    }

    private static String title(String ruleCode) {
        RulePresentation presentation = RULE_PRESENTATIONS.get(ruleCode);
        return presentation == null ? ruleCode : presentation.title();
    }

    private static String riskSummary(long riskCount) {
        if (riskCount == 0) {
            return "无风险";
        }
        return riskCount <= 2 ? "低风险" : "高风险";
    }

    private static BusinessException notFound() {
        return new BusinessException(BusinessErrorCode.NOT_FOUND);
    }

    private record RulePresentation(int order, String title) {
    }
}
