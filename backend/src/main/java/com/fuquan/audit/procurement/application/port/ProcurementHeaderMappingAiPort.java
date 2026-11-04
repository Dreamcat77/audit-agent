package com.fuquan.audit.procurement.application.port;

import java.util.List;

/** AI assistance for locating and naming spreadsheet columns. It never performs business checks. */
public interface ProcurementHeaderMappingAiPort {

    Result analyze(Request request);

    record Request(String sourceId, String format, List<String> allowedFields,
                   List<SpreadsheetHeaderPreview.PreviewRow> rows) {
        public Request {
            allowedFields = List.copyOf(allowedFields);
            rows = List.copyOf(rows);
        }
    }

    record Result(boolean available, String model, long durationMs,
                  Integer headerRowNumber, List<SpreadsheetHeaderMapping> mappings) {
        public Result {
            mappings = List.copyOf(mappings);
        }

        public static Result unavailable() {
            return new Result(false, null, 0L, null, List.of());
        }
    }
}
