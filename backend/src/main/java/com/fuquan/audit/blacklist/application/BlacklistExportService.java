package com.fuquan.audit.blacklist.application;

import com.fuquan.audit.blacklist.application.port.BlacklistSpreadsheetPort;
import com.fuquan.audit.blacklist.domain.BlacklistAlert;
import com.fuquan.audit.blacklist.domain.BlacklistSupplier;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * F03 Excel 导出用例。
 */
@Service
public class BlacklistExportService {

    private final BlacklistSpreadsheetPort spreadsheetPort;

    public BlacklistExportService(BlacklistSpreadsheetPort spreadsheetPort) {
        this.spreadsheetPort = spreadsheetPort;
    }

    public byte[] exportEntries(List<BlacklistSupplier> entries) {
        try {
            return spreadsheetPort.exportEntries(entries);
        } catch (IOException exception) {
            throw new BusinessException(BusinessErrorCode.INTERNAL_ERROR, "黑名单导出失败", exception);
        }
    }

    public byte[] exportAlerts(List<BlacklistAlert> alerts) {
        try {
            return spreadsheetPort.exportAlerts(alerts);
        } catch (IOException exception) {
            throw new BusinessException(BusinessErrorCode.INTERNAL_ERROR, "黑名单导出失败", exception);
        }
    }
}
