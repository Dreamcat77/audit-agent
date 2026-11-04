package com.fuquan.audit.integration.dm8;

import static com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;

class Dm8SemanticCheckpointRepositoryTest {
    static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");

    @Test void rowsRoundTripPrivateClobsHashesUtcAndExecutionIdentity() {
        var mapper = mock(SemanticCheckpointMapper.class);
        var repository = new Dm8SemanticCheckpointRepository(mapper);
        var storedPlan = new AtomicReference<SemanticCheckpointPlanRow>();
        var storedUnit = new AtomicReference<SemanticCheckpointUnitRow>();
        when(mapper.insertPlan(any())).thenAnswer(call -> { storedPlan.set(call.getArgument(0)); return 1; });
        when(mapper.insertUnit(any())).thenAnswer(call -> { storedUnit.set(call.getArgument(0)); return 1; });
        when(mapper.findPlanByStep(any())).thenAnswer(call -> storedPlan.get());
        when(mapper.findUnits(any())).thenAnswer(call -> List.of(storedUnit.get()));
        var run = UUID.randomUUID(); var step = UUID.randomUUID(); var attempt = UUID.randomUUID();
        var plan = new Plan(UUID.randomUUID(), run, step, attempt, attempt,
                new PlanSpec("a".repeat(64), "b".repeat(64), "{\"private\":\"语义\"}", NOW.plusSeconds(10), 10000, 4, 1),
                "worker", 9, NOW.plusSeconds(60), PlanStatus.ACTIVE, 1, 0, 0, 0, 7, 0, null, 3, NOW, NOW);
        String result = "{\"draft\":\"限定私有结果\"}";
        var unit = new Unit(UUID.randomUUID(), plan.planId(), "c".repeat(64), 1, 1, Purpose.ANALYZE,
                attempt, "a".repeat(64), List.of(), UnitStatus.VERIFIED, "worker", 9, NOW.plusSeconds(60),
                100, 7, result, digest(result), null,
                new CallMetadata(UUID.randomUUID(), "synthetic", "synthetic", "d".repeat(64)), 1, 2, NOW, NOW);
        repository.insertPlan(plan); repository.insertUnit(unit);
        assertThat(repository.findPlanByStep(step)).contains(plan);
        assertThat(repository.findUnits(plan.planId())).containsExactly(unit);
        assertThat(storedPlan.get().deadlineAt()).isEqualTo(LocalDateTime.of(2026, 10, 16, 0, 0, 10));
        assertThat(storedUnit.get().resultJson()).isEqualTo(result);
    }

    @Test void absentParentAndZeroAffectedCasNeverPretendOwnershipWasAcquired() {
        var mapper = mock(SemanticCheckpointMapper.class);
        var repository = new Dm8SemanticCheckpointRepository(mapper);
        when(mapper.lockParentAttempt(any())).thenReturn(null);
        assertThatThrownBy(() -> repository.lockParentAttempt(UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        var id = UUID.randomUUID();
        var plan = new Plan(id, id, id, id, id, new PlanSpec("a".repeat(64), "b".repeat(64), "{}",
                NOW.plusSeconds(10), 10000, 2, 1), "worker", 1, NOW.plusSeconds(60), PlanStatus.ACTIVE,
                0, 0, 0, 0, 0, 0, null, 0, NOW, NOW);
        var next = plan.change(id, "worker", 2, plan.leaseUntil(), plan.status(), 0, 0, 0, 0, 0, 0, null, NOW);
        assertThatThrownBy(() -> repository.compareAndSetPlan(plan, next)).isInstanceOf(IllegalStateException.class);
    }

    @Test void generatedDm8StatementsBindClobsAndFenceEveryCasWithoutTouchingBusinessOutput() throws Exception {
        Configuration configuration = new Configuration(); configuration.setMapUnderscoreToCamelCase(true);
        String resource = "mappers/dm8/SemanticCheckpointMapper.xml";
        try (var input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        String ns = SemanticCheckpointMapper.class.getName() + ".";
        BoundSql plan = configuration.getMappedStatement(ns + "updatePlanCas").getBoundSql(Map.of("row", Map.of(), "expected", Map.of()));
        assertThat(plan.getSql()).contains("WHERE PLAN_ID=", "CURRENT_ATTEMPT_ID=", "FENCE=", "VERSION_NO=");
        BoundSql unit = configuration.getMappedStatement(ns + "insertUnit").getBoundSql(Map.of("row", Map.of()));
        assertThat(unit.getParameterMappings()).filteredOn(p -> p.getProperty().equals("row.resultJson"))
                .singleElement().extracting(p -> p.getJdbcType()).isEqualTo(JdbcType.CLOB);
        assertThat(configuration.getMappedStatement(ns + "lockPlanByStep").getBoundSql(Map.of("stepId", "id")).getSql())
                .endsWith("FOR UPDATE");
        assertThat(configuration.getMappedStatement(ns + "lockParentAttempt").getBoundSql(Map.of("attemptId", "id")).getSql())
                .contains("CONTRACT_REVIEW_STEP_ATTEMPT").endsWith("FOR UPDATE");
    }
}
