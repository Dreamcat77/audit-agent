package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

class ContractFactPersistenceDiagnosticsTest {

    @Test
    void logsOnlyAllowlistedPersistenceCoordinates() {
        Logger logger = (Logger) LoggerFactory.getLogger("contract-fact-persistence-diagnostic-test");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var failure = new DataIntegrityViolationException("raw-document-sentinel",
                    new SQLException("raw-sql-sentinel", "22000", -6169));

            ContractFactPersistenceDiagnostics.log(logger, "CANDIDATE_INSERT", 2, failure);

            String output = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertThat(output)
                    .contains("stage=CANDIDATE_INSERT", "itemIndex=2", "sqlState=22000",
                            "vendorCode=-6169")
                    .doesNotContain("raw-document-sentinel", "raw-sql-sentinel");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
