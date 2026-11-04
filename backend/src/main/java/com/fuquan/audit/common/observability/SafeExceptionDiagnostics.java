package com.fuquan.audit.common.observability;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Extracts exception type and code location without exposing exception messages. */
public final class SafeExceptionDiagnostics {

    private SafeExceptionDiagnostics() {
    }

    public static String findRootCauseType(Throwable failure) {
        Throwable root = findRootCause(failure);
        return root == null ? null : root.getClass().getName();
    }

    public static SafeStackFrame safeTopStackFrame(Throwable failure) {
        Throwable root = findRootCause(failure);
        if (root == null) {
            return SafeStackFrame.empty();
        }
        StackTraceElement[] trace = root.getStackTrace();
        if (trace == null || trace.length == 0 || trace[0] == null) {
            return SafeStackFrame.empty();
        }
        StackTraceElement frame = trace[0];
        return new SafeStackFrame(
                frame.getClassName(), frame.getMethodName(), frame.getLineNumber());
    }

    public static SafeSqlDiagnostic safeSqlDiagnostic(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && visited.add(current)) {
            if (current instanceof SQLException sql) {
                String state = sql.getSQLState();
                String safeState = state != null && state.matches("[A-Za-z0-9]{1,10}")
                        ? state : "NONE";
                return new SafeSqlDiagnostic(safeState, sql.getErrorCode());
            }
            current = current.getCause();
        }
        return new SafeSqlDiagnostic("NONE", 0);
    }

    private static Throwable findRootCause(Throwable failure) {
        if (failure == null) {
            return null;
        }
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        visited.add(current);
        while (current.getCause() != null && visited.add(current.getCause())) {
            current = current.getCause();
        }
        return current;
    }

    public record SafeStackFrame(String className, String methodName, Integer lineNumber) {
        private static SafeStackFrame empty() {
            return new SafeStackFrame(null, null, null);
        }
    }


    public record SafeSqlDiagnostic(String sqlState, int vendorCode) {
    }
}
