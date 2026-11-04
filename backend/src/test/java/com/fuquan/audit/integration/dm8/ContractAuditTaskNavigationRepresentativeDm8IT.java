package com.fuquan.audit.integration.dm8;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;

/** Explicit opt-in, read-only verification against the already migrated integration DM8. */
class ContractAuditTaskNavigationRepresentativeDm8IT {
    private static final String EXISTING_RUN = "771b826f-27e1-4747-9de5-8bafd8f14470";

    @Test
    void exactHistoricalTaskNavigationAndHistoryQueriesRunOnRepresentativeDm8() throws Exception {
        assertEquals("true", System.getenv("AUDIT_P1B_DM8_VERIFY"));
        String url = required("AUDIT_DB_URL");
        assertTrue(url.startsWith("jdbc:dm:"));
        Configuration config = new Configuration();
        config.setMapUnderscoreToCamelCase(true);
        config.setLogImpl(NoLoggingImpl.class);
        for (String name : List.of("ContractReviewRunMapper", "IdempotencyMapper", "AuditTaskMapper")) {
            String resource = "mappers/dm8/" + name + ".xml";
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input);
                new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
            }
        }
        var factory = new SqlSessionFactoryBuilder().build(config);
        try (Connection connection = DriverManager.getConnection(url,
                required("AUDIT_DB_USERNAME"), required("AUDIT_DB_PASSWORD"))) {
            connection.setReadOnly(true);
            assertEquals(8, connection.getMetaData().getDatabaseMajorVersion());
            try (SqlSession session = factory.openSession(connection)) {
                var runs = session.getMapper(ContractReviewRunMapper.class);
                var operations = session.getMapper(IdempotencyMapper.class);
                var tasks = session.getMapper(AuditTaskMapper.class);
                var step = runs.findStepsByRunId(EXISTING_RUN).stream()
                        .filter(row -> "F02_DETERMINISTIC".equals(row.stepType()))
                        .findFirst().orElseThrow();
                assertEquals("AUDIT_TASK", step.outputResourceType());
                String taskId = step.outputResourceId();
                assertNotNull(tasks.findById(taskId));
                assertEquals(List.of(step.stepId()), runs.findStepsByOutputResource("AUDIT_TASK", taskId)
                        .stream().map(ContractReviewStepPersistenceRow::stepId).toList());
                assertTrue(operations.findAllByResultResource("AUDIT_TASK", taskId).stream()
                        .anyMatch(row -> "SUCCEEDED".equals(row.status())));
                assertTrue(tasks.countAll("CONTRACT", "F02_DETERMINISTIC") >= 1);
                assertFalse(tasks.findAllPage("CONTRACT", "F02_DETERMINISTIC", 0, 10).isEmpty());
            }
        }
        System.out.println("Representative DM8 read-only historical navigation mapper verification passed");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Required integration variable missing: " + name);
        return value;
    }
}
