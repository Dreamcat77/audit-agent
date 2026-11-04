package com.fuquan.audit.contract.application.project.factai;

import com.fuquan.audit.common.observability.SafeExceptionDiagnostics;
import com.fuquan.audit.common.web.RequestIdFilter;
import org.slf4j.Logger;
import org.slf4j.MDC;

/** Emits bounded structural diagnostics without provider values or document text. */
final class ContractFactExtractionDiagnostics {
    private ContractFactExtractionDiagnostics() { }

    static void log(Logger logger, RuntimeException failure) {
        var sql = SafeExceptionDiagnostics.safeSqlDiagnostic(failure);
        var frame = SafeExceptionDiagnostics.safeTopStackFrame(failure);
        logger.error("event=DM8_COMPLETION diagnosticSchemaVersion=1 module=CONTRACT layer=APPLICATION "
                        + "operation=extractContractFacts stage=DM8_COMPLETION outcome=OUTCOME_UNCERTAIN "
                        + "reasonCode=UNEXPECTED_FAILURE requestId={} traceId={} failureType={} rootType={} "
                        + "sqlState={} vendorCode={} stackClass={} stackMethod={} stackLine={}",
                MDC.get(RequestIdFilter.MDC_KEY), MDC.get("traceId"), failure.getClass().getSimpleName(),
                simpleRootType(failure), sql.sqlState(), sql.vendorCode(),
                frame.className(), frame.methodName(), frame.lineNumber());
    }

    private static String simpleRootType(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String value = current.getClass().getSimpleName();
        return value.matches("[A-Za-z0-9_$]{1,100}") ? value : "UNKNOWN";
    }
}
