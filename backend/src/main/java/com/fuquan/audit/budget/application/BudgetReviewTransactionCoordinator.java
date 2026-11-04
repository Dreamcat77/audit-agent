package com.fuquan.audit.budget.application;

import com.fuquan.audit.budget.application.port.BudgetCategoryRuleRepository;
import com.fuquan.audit.budget.application.port.BudgetProjectConfigRepository;
import com.fuquan.audit.budget.application.port.BudgetReviewItemRepository;
import com.fuquan.audit.budget.application.port.BudgetReviewTaskRepository;
import com.fuquan.audit.budget.application.port.BudgetSubjectMappingRepository;
import com.fuquan.audit.budget.application.port.BudgetValidationConfirmLogRepository;
import com.fuquan.audit.budget.application.port.BudgetYearValidityConfigRepository;
import com.fuquan.audit.budget.domain.BudgetCategoryRule;
import com.fuquan.audit.budget.domain.BudgetProjectConfig;
import com.fuquan.audit.budget.domain.BudgetReviewItem;
import com.fuquan.audit.budget.domain.BudgetReviewTask;
import com.fuquan.audit.budget.domain.BudgetSubjectMapping;
import com.fuquan.audit.budget.domain.BudgetValidationConfirmLog;
import com.fuquan.audit.budget.domain.BudgetYearValidityConfig;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * F01 预算审查的短事务协调器。
 *
 * <p>规则、文件解析和外部 AI 调用都在协调器事务之外执行。该组件只负责读取一致的
 * 审查快照，以及原子保存结果、游标、任务状态和人工确认事实。</p>
 */
@Component
public class BudgetReviewTransactionCoordinator {

    private final BudgetReviewTaskRepository taskRepository;
    private final BudgetReviewItemRepository itemRepository;
    private final BudgetSubjectMappingRepository mappingRepository;
    private final BudgetCategoryRuleRepository categoryRuleRepository;
    private final BudgetProjectConfigRepository projectConfigRepository;
    private final BudgetYearValidityConfigRepository yearValidityConfigRepository;
    private final BudgetValidationConfirmLogRepository confirmLogRepository;

    public BudgetReviewTransactionCoordinator(
            BudgetReviewTaskRepository taskRepository,
            BudgetReviewItemRepository itemRepository,
            BudgetSubjectMappingRepository mappingRepository,
            BudgetCategoryRuleRepository categoryRuleRepository,
            BudgetProjectConfigRepository projectConfigRepository,
            BudgetYearValidityConfigRepository yearValidityConfigRepository,
            BudgetValidationConfirmLogRepository confirmLogRepository) {
        this.taskRepository = taskRepository;
        this.itemRepository = itemRepository;
        this.mappingRepository = mappingRepository;
        this.categoryRuleRepository = categoryRuleRepository;
        this.projectConfigRepository = projectConfigRepository;
        this.yearValidityConfigRepository = yearValidityConfigRepository;
        this.confirmLogRepository = confirmLogRepository;
    }

    /**
     * 读取一次 F01 审查所需的任务、已有结果和活动配置。
     *
     * <p>调用方拿到快照后在事务外解析文件、执行规则和调用 AI。</p>
     */
    @Transactional(readOnly = true)
    public ReviewSnapshot loadReview(UUID taskId) {
        BudgetReviewTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(
                        BusinessErrorCode.NOT_FOUND, "预算审查任务不存在: " + taskId));
        return new ReviewSnapshot(
                task,
                itemRepository.findByTaskId(taskId),
                mappingRepository.findAllActive(),
                categoryRuleRepository.findAllActive(),
                projectConfigRepository.findAllActive(),
                yearValidityConfigRepository.findAllActive());
    }

    /**
     * 在一个短写事务中保存本轮结果和新的任务游标/状态。
     */
    @Transactional
    public BudgetReviewTask saveProgress(
            BudgetReviewTask nextTask,
            List<BudgetReviewItem> items) {
        return saveProgress(nextTask,items,()->{});
    }

    @Transactional
    public BudgetReviewTask saveProgress(BudgetReviewTask nextTask,List<BudgetReviewItem> items,Runnable verifyLease) {
        verifyLease.run();
        for (BudgetReviewItem item : items) {
            itemRepository.deleteByTaskAndRow(item.taskId(), item.rowNumber());
            itemRepository.saveAll(List.of(item));
        }
        taskRepository.save(nextTask);
        return nextTask;
    }

    /**
     * 先独立提交人工确认日志和恢复位置，再允许调用方继续事务外审查。
     */
    @Transactional
    public ConfirmationPreparation confirmAndPrepare(
            UUID taskId,
            int rowNumber,
            String expectedPendingStep,
            BudgetValidationConfirmLog confirmation,
            BudgetReviewTask resumableTask) {
        BudgetReviewTask task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new BusinessException(
                        BusinessErrorCode.NOT_FOUND, "预算审查任务不存在: " + taskId));
        boolean alreadyConfirmed = !"WAITING_CONFIRMATION".equals(task.status())
                && confirmLogRepository.findByBatchId(taskId).stream()
                        .anyMatch(log -> log.rowNumber() == rowNumber);
        if (!"WAITING_CONFIRMATION".equals(task.status())) {
            if (alreadyConfirmed && "REVIEWED".equals(task.status())) {
                return new ConfirmationPreparation(task, task, true);
            }
            if (alreadyConfirmed && "PARSED".equals(task.status())) {
                throw new BusinessException(
                        BusinessErrorCode.CONFLICT, "该异常确认已受理，续检处理中，请稍后刷新");
            }
            throw new BusinessException(
                    BusinessErrorCode.CONFLICT, "当前任务没有待确认异常");
        }
        if (!Integer.valueOf(rowNumber).equals(task.pendingRowNumber())) {
            throw new BusinessException(
                    BusinessErrorCode.CONFLICT, "待确认异常已变化，请刷新后重试");
        }
        if (!Objects.equals(expectedPendingStep, task.pendingStep())) {
            throw new BusinessException(
                    BusinessErrorCode.CONFLICT, "待确认步骤已变化，请刷新后重试");
        }
        confirmLogRepository.save(confirmation);
        taskRepository.save(resumableTask);
        return new ConfirmationPreparation(task, resumableTask, alreadyConfirmed);
    }

    /**
     * 续检发生未捕获异常时使用独立事务收口，确保已提交的确认日志不被抹掉。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BudgetReviewTask fail(BudgetReviewTask task, String detail) {
        return fail(task,detail,()->{});
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BudgetReviewTask fail(BudgetReviewTask task,String detail,Runnable verifyLease) {
        verifyLease.run();
        BudgetReviewTask failed = task.withStatus(
                "FAILED", task.parsedLineCount(),
                detail == null || detail.isBlank() ? "预算审查执行失败" : detail);
        taskRepository.save(failed);
        return failed;
    }

    public record ReviewSnapshot(
            BudgetReviewTask task,
            List<BudgetReviewItem> existingItems,
            List<BudgetSubjectMapping> mappings,
            List<BudgetCategoryRule> categoryRules,
            List<BudgetProjectConfig> projectConfigs,
            List<BudgetYearValidityConfig> yearValidityConfigs) {

        public ReviewSnapshot {
            existingItems = List.copyOf(existingItems);
            mappings = List.copyOf(mappings);
            categoryRules = List.copyOf(categoryRules);
            projectConfigs = List.copyOf(projectConfigs);
            yearValidityConfigs = List.copyOf(yearValidityConfigs);
        }
    }

    public record ConfirmationPreparation(
            BudgetReviewTask currentTask,
            BudgetReviewTask resumableTask,
            boolean alreadyConfirmed) {
    }
}
