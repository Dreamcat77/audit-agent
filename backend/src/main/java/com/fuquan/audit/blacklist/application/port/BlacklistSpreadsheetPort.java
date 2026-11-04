package com.fuquan.audit.blacklist.application.port;

import com.fuquan.audit.blacklist.domain.BlacklistAlert;
import com.fuquan.audit.blacklist.domain.BlacklistSupplier;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * F03 黑名单电子表格出站 Port。
 *
 * <p>Application 只接收业务字段，不暴露 Apache POI 类型。</p>
 */
public interface BlacklistSpreadsheetPort {

    List<ImportRow> readImport(InputStream inputStream) throws IOException;

    byte[] exportEntries(List<BlacklistSupplier> entries) throws IOException;

    byte[] exportAlerts(List<BlacklistAlert> alerts) throws IOException;

    record ImportRow(
            int rowNumber,
            String supplierName,
            String supplierCode,
            String blacklistType,
            String reason,
            String source,
            String effectiveDateText) {
    }
}
