package com.fuquan.audit.integration.dm8;

import java.sql.SQLException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.springframework.dao.DataIntegrityViolationException;

/** Emits only bounded structural coordinates for rejected A-mini durable writes. */
final class ContractFactPersistenceDiagnostics {
    private ContractFactPersistenceDiagnostics() { }

    static <T> T observe(Logger logger, String stage, int itemIndex, Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataIntegrityViolationException failure) {
            log(logger, stage, itemIndex, failure);
            throw failure;
        }
    }

    static void observe(Logger logger, String stage, int itemIndex, Runnable operation) {
        observe(logger, stage, itemIndex, () -> {
            operation.run();
            return null;
        });
    }

    static void log(Logger logger, String stage, int itemIndex,
            DataIntegrityViolationException failure) {
        SQLException sql = sqlCause(failure);
        String sqlState = sql == null || sql.getSQLState() == null
                || !sql.getSQLState().matches("[A-Za-z0-9]{1,10}")
                ? "UNKNOWN" : sql.getSQLState();
        int vendorCode = sql == null ? 0 : sql.getErrorCode();
        logger.warn("contract fact persistence rejected stage={} itemIndex={} sqlState={} vendorCode={}",
                stage, itemIndex, sqlState, vendorCode);
    }

    private static SQLException sqlCause(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sql) return sql;
            current = current.getCause();
        }
        return null;
    }
}
