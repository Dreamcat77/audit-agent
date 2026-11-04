package com.fuquan.audit.budget.application;

import com.fuquan.audit.budget.application.port.BudgetReviewItemRepository;
import com.fuquan.audit.budget.application.port.BudgetReviewTaskRepository;
import com.fuquan.audit.budget.application.port.BudgetValidationConfirmLogRepository;
import com.fuquan.audit.budget.domain.BudgetReviewItem;
import com.fuquan.audit.budget.domain.BudgetReviewTask;
import com.fuquan.audit.budget.domain.BudgetValidationConfirmLog;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * F01 审查查询用例。
 *
 * <p>只负责从应用层 Port 读取预算审查事实，不把分页、任务归属和明细归属判断散落到
 * Controller 中。</p>
 */
@Service
public class BudgetReviewQueryService {

    private final BudgetReviewTaskRepository taskRepository;
    private final BudgetReviewItemRepository itemRepository;
    private final BudgetValidationConfirmLogRepository confirmLogRepository;

    public BudgetReviewQueryService(
            BudgetReviewTaskRepository taskRepository,
            BudgetReviewItemRepository itemRepository,
            BudgetValidationConfirmLogRepository confirmLogRepository) {
        this.taskRepository = taskRepository;
        this.itemRepository = itemRepository;
        this.confirmLogRepository = confirmLogRepository;
    }

    public BudgetReviewTask requireTask(UUID taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(
                        BusinessErrorCode.NOT_FOUND, "预算审查任务不存在: " + taskId));
    }

    public List<BudgetReviewTask> findTaskPage(
            String batchId, String budgetYear, String departmentCode, int page, int size) {
        PageRequest request = PageRequest.of(page, size);
        return taskRepository.findPage(
                batchId, budgetYear, departmentCode, request.offset(), request.size());
    }

    public long countTasks(String batchId, String budgetYear, String departmentCode) {
        return taskRepository.count(batchId, budgetYear, departmentCode);
    }

    public List<BudgetReviewTask> findHistoryPage(String batchId, String budgetYear, String departmentCode,
            com.fuquan.audit.budget.application.port.BudgetHistoryFilter filter, int page, int size) {
        PageRequest request = PageRequest.of(page, size);
        return taskRepository.findHistoryPage(batchId, budgetYear, departmentCode,
                filter, request.offset(), request.size());
    }

    public long countHistory(String batchId, String budgetYear, String departmentCode,
            com.fuquan.audit.budget.application.port.BudgetHistoryFilter filter) {
        return taskRepository.countHistory(batchId, budgetYear, departmentCode, filter);
    }

    public List<BudgetReviewItem> findItems(UUID taskId) {
        requireTask(taskId);
        return itemRepository.findByTaskId(taskId);
    }

    public BudgetReviewItem findItem(UUID taskId, UUID itemId) {
        requireTask(taskId);
        BudgetReviewItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException(
                        BusinessErrorCode.NOT_FOUND, "预算审查明细不存在: " + itemId));
        if (!taskId.equals(item.taskId())) {
            throw new BusinessException(
                    BusinessErrorCode.NOT_FOUND, "预算审查明细不存在: " + itemId);
        }
        return item;
    }

    public List<BudgetValidationConfirmLog> findConfirmLogs(UUID taskId) {
        requireTask(taskId);
        return confirmLogRepository.findByBatchId(taskId);
    }

    public record PageRequest(int page, int size) {
        public static PageRequest of(int page, int size) {
            return new PageRequest(
                    Math.max(page, 1),
                    Math.min(Math.max(size, 1), 100));
        }

        public int offset() {
            return (page - 1) * size;
        }
    }
}
