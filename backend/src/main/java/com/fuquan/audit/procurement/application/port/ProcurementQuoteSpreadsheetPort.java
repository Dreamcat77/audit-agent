package com.fuquan.audit.procurement.application.port;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;

/** F06 询比价电子表格输入 Port，不向应用层暴露 POI 类型。 */
public interface ProcurementQuoteSpreadsheetPort {

    ParsedSheet read(InputStream inputStream) throws IOException;

    default SpreadsheetHeaderPreview preview(InputStream inputStream, int maxRows) throws IOException {
        throw new UnsupportedOperationException("spreadsheet preview is not supported");
    }

    default ParsedSheet read(InputStream inputStream, int headerRowNumber,
                             List<SpreadsheetHeaderMapping> mappings) throws IOException {
        throw new UnsupportedOperationException("confirmed spreadsheet mapping is not supported");
    }

    record ParsedSheet(BigDecimal budgetAmount, List<QuoteRow> rows,
                       List<com.fuquan.audit.procurement.domain.ProcurementQuoteComparison.Item> items,
                       List<String> conditions) {
        public ParsedSheet(BigDecimal budgetAmount, List<QuoteRow> rows) {
            this(budgetAmount, rows, List.of(), List.of());
        }
        public ParsedSheet {
            rows = List.copyOf(rows);
            items = List.copyOf(items); conditions = List.copyOf(conditions);
        }
    }

    record QuoteRow(int rowNumber, String supplierName, BigDecimal quoteAmount,
                    String productDescription) {
    }
}
