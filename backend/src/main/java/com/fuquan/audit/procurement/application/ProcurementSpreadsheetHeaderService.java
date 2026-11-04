package com.fuquan.audit.procurement.application;

import com.fuquan.audit.blacklist.application.port.BlacklistBatchSpreadsheetPort;
import com.fuquan.audit.procurement.application.port.ProcurementHeaderMappingAiPort;
import com.fuquan.audit.procurement.application.port.ProcurementQuoteSpreadsheetPort;
import com.fuquan.audit.procurement.application.port.SpreadsheetHeaderMapping;
import com.fuquan.audit.procurement.application.port.SpreadsheetHeaderPreview;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.stereotype.Service;

/** Coordinates rule-first spreadsheet inspection and optional AI assistance. */
@Service
public class ProcurementSpreadsheetHeaderService {
    private static final int PREVIEW_ROWS = 10;
    private final ProcurementQuoteSpreadsheetPort quotePort;
    private final BlacklistBatchSpreadsheetPort blacklistPort;
    private final ProcurementHeaderMappingAiPort aiPort;

    public ProcurementSpreadsheetHeaderService(ProcurementQuoteSpreadsheetPort quotePort,
                                               BlacklistBatchSpreadsheetPort blacklistPort,
                                               ProcurementHeaderMappingAiPort aiPort) {
        this.quotePort = quotePort;
        this.blacklistPort = blacklistPort;
        this.aiPort = aiPort;
    }

    public SpreadsheetHeaderPreview previewQuote(InputStream inputStream) throws IOException {
        SpreadsheetHeaderPreview rule = quotePort.preview(inputStream, PREVIEW_ROWS);
        return rule.mappings().isEmpty() ? withAi(rule, "F06_QUOTE") : rule;
    }

    public SpreadsheetHeaderPreview previewBlacklist(InputStream inputStream) throws IOException {
        SpreadsheetHeaderPreview rule = blacklistPort.preview(inputStream, PREVIEW_ROWS);
        return rule.mappings().isEmpty() ? withAi(rule, "F03_BLACKLIST") : rule;
    }

    private SpreadsheetHeaderPreview withAi(SpreadsheetHeaderPreview preview, String format) {
        try {
            List<String> allowedFields = "F03_BLACKLIST".equals(format)
                    ? List.of("SUPPLIER_NAME", "SUPPLIER_CODE", "PROCUREMENT_ID")
                    : List.of("SUPPLIER_NAME", "QUOTE_AMOUNT", "PRODUCT_DESCRIPTION");
            ProcurementHeaderMappingAiPort.Result result = aiPort.analyze(
                    new ProcurementHeaderMappingAiPort.Request(format, "XLSX_OR_XLS", allowedFields, preview.rows()));
            if (!result.available()) return preview;
            List<SpreadsheetHeaderMapping> mappings = result.mappings().stream()
                    .filter(mapping -> mapping.sourceColumnIndex() >= 0
                            && mapping.sourceColumnIndex() < maxColumns(preview.rows())
                            && mapping.standardField() != null)
                    .toList();
            return new SpreadsheetHeaderPreview(preview.firstRowNumber(), preview.rowCount(), preview.rows(),
                    result.headerRowNumber(), mappings, true, result.model());
        } catch (RuntimeException exception) {
            return preview;
        }
    }

    private static int maxColumns(List<SpreadsheetHeaderPreview.PreviewRow> rows) {
        return rows.stream().mapToInt(row -> row.cells().size()).max().orElse(0);
    }
}
