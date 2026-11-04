package com.fuquan.audit.blacklist.application;

import com.fuquan.audit.blacklist.application.port.BlacklistAlertRepository;
import com.fuquan.audit.blacklist.application.port.BlacklistRuleRepository;
import com.fuquan.audit.blacklist.application.port.BlacklistSupplierOperationLogRepository;
import com.fuquan.audit.blacklist.application.port.BlacklistSupplierRepository;
import com.fuquan.audit.blacklist.domain.BlacklistAlert;
import com.fuquan.audit.blacklist.domain.BlacklistRule;
import com.fuquan.audit.blacklist.domain.BlacklistSupplier;
import com.fuquan.audit.blacklist.domain.BlacklistSupplierOperationLog;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * F03 黑名单只读查询用例。
 */
@Service
public class BlacklistQueryService {

    private final BlacklistAlertRepository alertRepository;
    private final BlacklistSupplierRepository supplierRepository;
    private final BlacklistRuleRepository ruleRepository;
    private final BlacklistSupplierOperationLogRepository operationLogRepository;

    public BlacklistQueryService(
            BlacklistAlertRepository alertRepository,
            BlacklistSupplierRepository supplierRepository,
            BlacklistRuleRepository ruleRepository,
            BlacklistSupplierOperationLogRepository operationLogRepository) {
        this.alertRepository = alertRepository;
        this.supplierRepository = supplierRepository;
        this.ruleRepository = ruleRepository;
        this.operationLogRepository = operationLogRepository;
    }

    public List<BlacklistAlert> findAlerts(String status) {
        return alertRepository.findAll(status);
    }

    public List<BlacklistAlert> findAlertsForExport(
            String batchId, String alertLevel, String status, String supplierName) {
        return alertRepository.findPage(
                batchId, alertLevel, status, supplierName, 0, Integer.MAX_VALUE);
    }

    public Page<BlacklistAlert> findAlertPage(
            String batchId, String alertLevel, String status, String supplierName,
            int page, int size) {
        PageRequest request = PageRequest.of(page, size);
        return new Page<>(
                alertRepository.findPage(
                        batchId, alertLevel, status, supplierName,
                        request.offset(), request.size()),
                request.page(), request.size(),
                alertRepository.count(batchId, alertLevel, status, supplierName));
    }

    public BlacklistAlert requireAlert(UUID id) {
        return alertRepository.findById(id)
                .orElseThrow(() -> new BusinessException(
                        BusinessErrorCode.NOT_FOUND, "blacklist alert not found: " + id));
    }

    public List<BlacklistSupplier> findActiveEntries() {
        return supplierRepository.findAllActive();
    }

    public List<BlacklistSupplier> findEntriesForExport(
            String keyword, String blacklistType, Boolean active) {
        return supplierRepository.findPage(
                keyword, blacklistType, active, 0, Integer.MAX_VALUE);
    }

    public Page<BlacklistSupplier> findEntryPage(
            String keyword, String blacklistType, Boolean active, int page, int size) {
        PageRequest request = PageRequest.of(page, size);
        return new Page<>(
                supplierRepository.findPage(
                        keyword, blacklistType, active, request.offset(), request.size()),
                request.page(), request.size(),
                supplierRepository.count(keyword, blacklistType, active));
    }

    public BlacklistSupplier requireEntry(UUID id) {
        return supplierRepository.findById(id)
                .orElseThrow(() -> new BusinessException(
                        BusinessErrorCode.NOT_FOUND, "blacklist entry not found: " + id));
    }

    public List<BlacklistSupplierOperationLog> findEntryOperations(UUID id) {
        requireEntry(id);
        return operationLogRepository.findByBlacklistId(id);
    }

    public List<BlacklistRule> findRules() {
        return ruleRepository.findAll();
    }

    public record Page<T>(List<T> items, int page, int size, long total) {
        public long totalPages() {
            return total == 0 ? 0 : (total + size - 1) / size;
        }
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
