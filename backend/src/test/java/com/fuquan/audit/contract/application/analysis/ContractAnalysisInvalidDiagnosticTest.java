package com.fuquan.audit.contract.application.analysis;

import org.junit.jupiter.api.Test;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import static org.assertj.core.api.Assertions.*;

class ContractAnalysisInvalidDiagnosticTest {
    @Test void diagnosticRetainsProgramLocationWithoutExceptionOrMaterialContents(){
        var logger=(Logger)LoggerFactory.getLogger(ContractAnalysisProcessor.class);
        var appender=new ListAppender<ILoggingEvent>();appender.start();logger.addAppender(appender);
        try {
            var error=new IllegalArgumentException("PRIVATE_MATERIAL_SENTINEL",new NumberFormatException("PRIVATE_NUMBER_SENTINEL"));
            ContractAnalysisProcessor.logInvalid(error,"PRIVATE_KIND_SENTINEL");
            assertThat(appender.list).hasSize(1);
            var event=appender.list.get(0);
            assertThat(event.getFormattedMessage()).contains("kind=OTHER","rootCauseClass=NumberFormatException","ContractAnalysisInvalidDiagnosticTest");
            assertThat(event.getFormattedMessage()).doesNotContain("SENTINEL");
            assertThat(event.getThrowableProxy()).isNull();
        } finally {logger.detachAppender(appender);appender.stop();}
    }
}
