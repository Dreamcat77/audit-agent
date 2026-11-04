package com.fuquan.audit.blacklist.application;

import com.fuquan.audit.blacklist.application.port.BlacklistAlertRepository;
import com.fuquan.audit.blacklist.domain.BlacklistAlert;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F03 报警处理用例。
 */
@Service
public class BlacklistAlertService {

    private final BlacklistAlertRepository alertRepository;
    private final BlacklistQueryService queryService;

    public BlacklistAlertService(
            BlacklistAlertRepository alertRepository,
            BlacklistQueryService queryService) {
        this.alertRepository = alertRepository;
        this.queryService = queryService;
    }

    @Transactional
    public BlacklistAlert process(
            UUID alertId, String action, String handler, String remark) {
        BlacklistAlert alert = queryService.requireAlert(alertId);
        String normalizedAction = action == null || action.isBlank()
                ? "PROCESSED" : action.trim().toUpperCase(Locale.ROOT);
        BlacklistAlert updated = switch (normalizedAction) {
            case "IGNORED", "IGNORE" -> alert.ignore(handler, remark);
            case "FALSE_POSITIVE", "FALSE" -> alert.falsePositive(handler, remark);
            case "PROCESSED", "RESOLVED" -> alert.process(handler, remark);
            default -> throw new BusinessException(
                    BusinessErrorCode.INVALID_ARGUMENT, "不支持的告警处理动作: " + action);
        };
        if (!"PENDING".equals(alert.status())) {
            if (updated.status().equals(alert.status())) {
                return alert;
            }
            throw new BusinessException(BusinessErrorCode.CONFLICT, "该告警已被处理，请刷新后重试");
        }
        if (!alertRepository.updateIfStatus(updated, alert.status())) {
            BlacklistAlert current = queryService.requireAlert(alertId);
            if (updated.status().equals(current.status())) {
                return current;
            }
            throw new BusinessException(BusinessErrorCode.CONFLICT, "该告警已被其他用户处理，请刷新后重试");
        }
        return updated;
    }
}
