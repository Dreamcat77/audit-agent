package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

class ReviewPersistenceDiagnosticsTest {

    @Test
    void logsOnlyAllowlistedPersistenceCoordinatesAndNeverTheSqlMessage() {
        Logger logger = (Logger) LoggerFactory.getLogger("review-persistence-diagnostic-test");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var failure = new DataIntegrityViolationException("raw-contract-sentinel",
                    new SQLException("raw-sql-sentinel", "23000", -6602));

            ReviewPersistenceDiagnostics.log(logger, "FINDING_INSERT", 3, failure);

            String output = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertThat(output)
                    .contains("stage=FINDING_INSERT", "itemIndex=3", "sqlState=23000", "vendorCode=-6602")
                    .doesNotContain("raw-contract-sentinel", "raw-sql-sentinel");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
