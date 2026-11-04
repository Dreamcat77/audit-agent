package com.fuquan.audit.procurement.application.port;

/** A user-confirmable mapping from an uploaded column to a canonical field. */
public record SpreadsheetHeaderMapping(
        String sourceHeader,
        int sourceColumnIndex,
        String standardField,
        Double confidence) {
}
