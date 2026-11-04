package com.fuquan.audit.contract.application.project.factai;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

class ContractFactExtractionDiagnosticsTest {

    @Test
    void logsOnlyStructuralFailureCoordinates() {
        Logger logger = (Logger) LoggerFactory.getLogger("contract-fact-diagnostic-test");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var failure = new DataIntegrityViolationException("raw-document-sentinel",
                    new SQLException("raw-sql-sentinel", "23000", -6602));

            ContractFactExtractionDiagnostics.log(logger, failure);

            String output = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertThat(output)
                    .contains("failureType=DataIntegrityViolationException", "rootType=SQLException",
                            "sqlState=23000", "vendorCode=-6602")
                    .doesNotContain("raw-document-sentinel", "raw-sql-sentinel");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
