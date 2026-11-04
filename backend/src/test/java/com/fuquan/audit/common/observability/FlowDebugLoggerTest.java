package com.fuquan.audit.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

class FlowDebugLoggerTest {

    @Test
    void propertyOffDisablesFlowDebugWithoutWarning() {
        Logger logger = mock(Logger.class);
        when(logger.isDebugEnabled()).thenReturn(true);

        FlowDebugLogger flowDebug = new FlowDebugLogger(properties(false), logger);

        assertThat(flowDebug.isEnabled()).isFalse();
        verify(logger, never()).warn(FlowDebugLogger.SUPPRESSED_WARNING);
    }

    @Test
    void propertyOnAndDebugLevelEnableFlowDebug() {
        Logger logger = mock(Logger.class);
        when(logger.isDebugEnabled()).thenReturn(true);

        FlowDebugLogger flowDebug = new FlowDebugLogger(properties(true), logger);

        assertThat(flowDebug.isEnabled()).isTrue();
        verify(logger, never()).warn(FlowDebugLogger.SUPPRESSED_WARNING);
    }

    @Test
    void propertyOnWithInfoLevelWarnsOnceAndSuppressesEvents() {
        Logger logger = mock(Logger.class);
        when(logger.isDebugEnabled()).thenReturn(false);

        FlowDebugLogger flowDebug = new FlowDebugLogger(properties(true), logger);

        assertThat(flowDebug.isEnabled()).isFalse();
        verify(logger).warn(FlowDebugLogger.SUPPRESSED_WARNING);
    }

    @Test
    void aiAnalysisAcceptsOnlyNonNegativeNumericCountsAndSafeIdentifiers() {
        Logger logger = mock(Logger.class);
        FlowDebugLogger flowDebug = new FlowDebugLogger(properties(false), logger);

        flowDebug.logAiAnalysis("AI_COMPLETE", "CONTRACT", "CONTRACT_TEXT_ANALYSIS",
                "resource-1", "SUCCESS", "qwen-test", 9L, 3, 2);
        assertThatThrownBy(() -> flowDebug.logAiAnalysis("AI_COMPLETE", "CONTRACT",
                "CONTRACT_TEXT_ANALYSIS", "resource-1", "SUCCESS", "qwen-test", 9L, -1, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> flowDebug.logAiAnalysis("AI_COMPLETE", "CONTRACT",
                "CONTRACT_TEXT_ANALYSIS", "resource-1", "SUCCESS", "qwen-test", 9L, 1, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> flowDebug.logAiAnalysis("AI_COMPLETE", "CONTRACT",
                "CONTRACT_TEXT_ANALYSIS", "unsafe source text", "SUCCESS", "qwen-test", 9L, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ObservabilityProperties properties(boolean enabled) {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setFlowDebugEnabled(enabled);
        return properties;
    }
}
