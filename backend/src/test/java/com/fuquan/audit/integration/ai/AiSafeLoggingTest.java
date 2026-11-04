package com.fuquan.audit.integration.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.observability.ObservabilityProperties;
import com.fuquan.audit.contract.api.ContractTextAnalysisController;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisResult;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisResult.Hint;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisUseCase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class AiSafeLoggingTest {

    @Test
    void semanticAnalysisLogsSafeProvenanceButNotReturnedText() {
        String sourceSentinel = "AI-SOURCE-SENTINEL-MUST-NOT-BE-LOGGED";
        UUID projectId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        ContractTextAnalysisUseCase useCase = mock(ContractTextAnalysisUseCase.class);
        when(useCase.analyze(projectId, List.of(fileId))).thenReturn(new ContractTextAnalysisResult(
                true,
                List.of(sourceSentinel),
                List.of(new Hint(ContractTextAnalysisResult.SemanticCategory.OTHER_TEXT,
                        ContractTextAnalysisResult.Attention.REVIEW,
                        "提示", sourceSentinel, List.of(fileId))),
                "qwen-safe-test",
                9L));
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setFlowDebugEnabled(true);
        Logger logger = (Logger) LoggerFactory.getLogger(FlowDebugLogger.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            new ContractTextAnalysisController(useCase, new FlowDebugLogger(properties))
                    .analyze(projectId,
                            new ContractTextAnalysisController.ContractTextAnalysisRequestBody(List.of(fileId)));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            appender.stop();
        }

        String logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(logs).contains("CONTRACT_TEXT_ANALYSIS", projectId.toString(),
                "qwen-safe-test", "durationMs=9", "selectedFileCount=1", "checkCount=1");
        assertThat(logs).doesNotContain(sourceSentinel);
    }
}
