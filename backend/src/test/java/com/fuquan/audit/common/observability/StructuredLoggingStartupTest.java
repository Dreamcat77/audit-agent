package com.fuquan.audit.common.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "logging.structured.format.console=logstash")
@ActiveProfiles("local")
class StructuredLoggingStartupTest {

    @Test
    void applicationStartsWithBuiltInStructuredLogging() {
    }
}
