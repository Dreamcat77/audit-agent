package com.fuquan.audit.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SafeExceptionDiagnosticsTest {

    @Test
    void handlesCauseCycleWithoutReadingMessages() {
        RuntimeException first = new RuntimeException("C:\\SECRET\\first.pdf");
        RuntimeException second = new RuntimeException("/secret/second.pdf");
        first.initCause(second);
        second.initCause(first);

        assertThat(SafeExceptionDiagnostics.findRootCauseType(first))
                .isEqualTo(RuntimeException.class.getName());
        assertThat(SafeExceptionDiagnostics.safeTopStackFrame(first).className())
                .isEqualTo(getClass().getName());
    }

    @Test
    void handlesNullAndEmptyStackTrace() {
        RuntimeException failure = new RuntimeException("not logged");
        failure.setStackTrace(new StackTraceElement[0]);

        assertThat(SafeExceptionDiagnostics.findRootCauseType(null)).isNull();
        assertThat(SafeExceptionDiagnostics.safeTopStackFrame(failure).className()).isNull();
        assertThat(SafeExceptionDiagnostics.safeTopStackFrame(failure).methodName()).isNull();
        assertThat(SafeExceptionDiagnostics.safeTopStackFrame(failure).lineNumber()).isNull();
    }
}
