package com.fuquan.audit.procurement.application.port;

import java.util.List;

/** Bounded spreadsheet sample returned before a non-standard import is confirmed. */
public record SpreadsheetHeaderPreview(
        int firstRowNumber,
        int rowCount,
        List<PreviewRow> rows,
        Integer suggestedHeaderRowNumber,
        List<SpreadsheetHeaderMapping> mappings,
        boolean aiAvailable,
        String aiModel) {
    public SpreadsheetHeaderPreview {
        rows = List.copyOf(rows);
        mappings = List.copyOf(mappings);
    }

    public record PreviewRow(int rowNumber, List<String> cells) {
        public PreviewRow {
            cells = List.copyOf(cells);
        }
    }
}
