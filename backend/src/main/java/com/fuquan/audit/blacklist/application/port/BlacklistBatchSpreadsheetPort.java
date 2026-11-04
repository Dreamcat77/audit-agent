package com.fuquan.audit.blacklist.application.port;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import com.fuquan.audit.procurement.application.port.SpreadsheetHeaderMapping;
import com.fuquan.audit.procurement.application.port.SpreadsheetHeaderPreview;

/**
 * F03 批量黑名单检查的电子表格输入 Port。
 *
 * <p>Application 层只接收采购供应商字段，不暴露 Apache POI 类型。</p>
 */
public interface BlacklistBatchSpreadsheetPort {

    List<SupplierRow> readSupplierRows(InputStream inputStream) throws IOException;

    default SpreadsheetHeaderPreview preview(InputStream inputStream, int maxRows) throws IOException {
        throw new UnsupportedOperationException("spreadsheet preview is not supported");
    }

    default List<SupplierRow> readSupplierRows(InputStream inputStream, int headerRowNumber,
                                               List<SpreadsheetHeaderMapping> mappings)
            throws IOException {
        throw new UnsupportedOperationException("confirmed spreadsheet mapping is not supported");
    }

    record SupplierRow(
            int rowNumber,
            String supplierName,
            String supplierCode,
            String procurementId) {
    }
}
