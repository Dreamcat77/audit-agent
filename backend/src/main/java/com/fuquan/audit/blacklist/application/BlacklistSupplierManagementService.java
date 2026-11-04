package com.fuquan.audit.blacklist.application;

import com.fuquan.audit.blacklist.application.port.BlacklistSupplierOperationLogRepository;
import com.fuquan.audit.blacklist.application.port.BlacklistSupplierRepository;
import com.fuquan.audit.blacklist.application.port.BlacklistSpreadsheetPort;
import com.fuquan.audit.blacklist.domain.BlacklistSupplier;
import com.fuquan.audit.blacklist.domain.BlacklistSupplierOperationLog;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.security.domain.CurrentUser;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F03 黑名单主数据管理服务。
 *
 * <p>移除采用停用而非物理删除，并与操作日志放在同一事务中。</p>
 */
@Service
public class BlacklistSupplierManagementService {

    private final BlacklistSupplierRepository supplierRepository;
    private final BlacklistSupplierOperationLogRepository operationLogRepository;
    private final BlacklistSpreadsheetPort spreadsheetPort;

    public BlacklistSupplierManagementService(
            BlacklistSupplierRepository supplierRepository,
            BlacklistSupplierOperationLogRepository operationLogRepository,
            BlacklistSpreadsheetPort spreadsheetPort) {
        this.supplierRepository = supplierRepository;
        this.operationLogRepository = operationLogRepository;
        this.spreadsheetPort = spreadsheetPort;
    }

    @Transactional
    public BlacklistSupplier add(AddCommand command, CurrentUser operator) {
        return supplierRepository.save(BlacklistSupplier.create(
                command.supplierName(),
                command.supplierCode(),
                defaultValue(command.blacklistType(), "本单位黑名单"),
                defaultValue(command.reason(), "未说明"),
                defaultValue(command.source(), "内部录入"),
                command.effectiveDate(),
                null,
                operator.username()));
    }

    @Transactional
    public BlacklistSupplier update(
            UUID blacklistId, UpdateCommand command, CurrentUser operator) {
        BlacklistSupplier existing = supplierRepository.findById(blacklistId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND,
                        "blacklist entry not found: " + blacklistId));
        BlacklistSupplier updated = existing.update(
                command.supplierName(),
                command.supplierCode(),
                defaultValue(command.blacklistType(), existing.blacklistType()),
                defaultValue(command.reason(), existing.reason()),
                defaultValue(command.source(), existing.source()),
                command.effectiveDate() != null
                        ? command.effectiveDate() : existing.effectiveDate(),
                null,
                operator.username());
        return supplierRepository.update(updated);
    }

    /**
     * 导入采用“逐行收集错误、有效行继续入库”的策略。
     *
     * <p>整个批次仍在一个事务边界内；Repository Adapter 决定最终的批量落库方式。</p>
     */
    @Transactional
    public ImportResult importEntries(InputStream inputStream, CurrentUser operator) {
        try {
            int imported = 0;
            List<String> errors = new java.util.ArrayList<>();
            for (BlacklistSpreadsheetPort.ImportRow row : spreadsheetPort.readImport(inputStream)) {
                try {
                    if (row.supplierName() == null || row.supplierName().isBlank()) {
                        throw new IllegalArgumentException("供应商名称不能为空");
                    }
                    add(new AddCommand(
                            row.supplierName(), row.supplierCode(), row.blacklistType(),
                            row.reason(), row.source(), parseDate(row.effectiveDateText())),
                            operator);
                    imported++;
                } catch (RuntimeException exception) {
                    errors.add("第" + row.rowNumber() + "行：" + exception.getMessage());
                }
            }
            return new ImportResult(imported, errors.size(), List.copyOf(errors));
        } catch (java.io.IOException exception) {
            throw new BusinessException(
                    BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                    "Excel 导入失败: " + exception.getMessage(), exception);
        }
    }

    @Transactional
    public RemovalResult remove(UUID blacklistId, CurrentUser operator, String reason) {
        BlacklistSupplier existing = supplierRepository.findById(blacklistId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND,
                        "blacklist entry not found: " + blacklistId));
        if (!existing.active()) {
            throw new BusinessException(BusinessErrorCode.CONFLICT,
                    "blacklist entry has already been removed: " + blacklistId);
        }
        String normalizedReason = reason == null || reason.isBlank()
                ? "未填写移除原因" : reason.trim();
        BlacklistSupplier updated = supplierRepository.update(existing.deactivate());
        BlacklistSupplierOperationLog log = BlacklistSupplierOperationLog.removed(
                updated.id(), operator.userId(), operator.displayName(), normalizedReason);
        operationLogRepository.save(log);
        return new RemovalResult(updated, log);
    }

    private static LocalDate parseDate(String value) {
        return value == null || value.isBlank() ? LocalDate.now() : LocalDate.parse(value);
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record AddCommand(
            String supplierName,
            String supplierCode,
            String blacklistType,
            String reason,
            String source,
            LocalDate effectiveDate) {
    }

    public record UpdateCommand(
            String supplierName,
            String supplierCode,
            String blacklistType,
            String reason,
            String source,
            LocalDate effectiveDate) {
    }

    public record ImportResult(int imported, int failed, List<String> errors) {
    }

    public record RemovalResult(
            BlacklistSupplier entry, BlacklistSupplierOperationLog operationLog) {}
}
