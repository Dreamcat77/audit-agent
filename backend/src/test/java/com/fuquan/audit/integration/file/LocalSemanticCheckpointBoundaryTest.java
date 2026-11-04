package com.fuquan.audit.integration.file;

import static com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalSemanticCheckpointBoundaryTest {
    @TempDir Path directory;
    final ObjectMapper json = new ObjectMapper();
    final Instant now = Instant.now();
    final UUID run=UUID.randomUUID(), step=UUID.randomUUID(), attempt=UUID.randomUUID(), id=UUID.randomUUID();
    final String hash="a".repeat(64);
    Plan plan(int reserved) {
        return new Plan(id,run,step,attempt,attempt,new PlanSpec(hash,hash,"{}",now.plusSeconds(300),900000,11,2),
                "worker-test",1,now.plusSeconds(60),PlanStatus.ACTIVE,3,reserved,0,0,1000,0,null,4,now,now);
    }
    Unit unit(int ordinal, UnitStatus state) {
        return new Unit(UUID.randomUUID(),id,(""+ordinal).repeat(64),1,ordinal,Purpose.ANALYZE,attempt,hash,
                List.of(),state,"worker-test",1,now.plusSeconds(60),100,10,
                state==UnitStatus.VERIFIED?"{}":null,state==UnitStatus.VERIFIED?digest("{}"):null,null,
                state==UnitStatus.PLANNED?null:new CallMetadata(UUID.randomUUID(),"OPENAI_COMPATIBLE","unit-model",hash),
                state==UnitStatus.PLANNED?0:ordinal,0,now,now);
    }
    @Test void threeCommittedAnalysesCreateOnlySafeProofAndBlockUntilInterrupted() throws Exception {
        Path proof=directory.resolve("boundary.json");
        var boundary=new LocalSemanticCheckpointBoundary(proof,json,Clock.systemUTC(),Duration.ofSeconds(5));
        var executor=Executors.newSingleThreadExecutor();
        try {
            var future=executor.submit(() -> boundary.afterCommit(plan(0),List.of(unit(1,UnitStatus.VERIFIED),
                    unit(2,UnitStatus.VERIFIED),unit(3,UnitStatus.VERIFIED),unit(4,UnitStatus.PLANNED))));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(!Files.exists(proof) && !future.isDone() && System.nanoTime()<deadline) Thread.sleep(5);
            assertThat(proof).exists();
            assertThat(future.isDone()).isFalse();
            var recorded=json.readTree(Files.readString(proof));
            assertThat(recorded.path("runId").asText()).isEqualTo(run.toString());
            assertThat(recorded.path("planId").asText()).isEqualTo(id.toString());
            assertThat(recorded.path("attemptId").asText()).isEqualTo(attempt.toString());
            assertThat(recorded.path("verifiedResults").size()).isEqualTo(3);
            assertThat(recorded.path("reservedCalls").asInt()).isZero();
            assertThat(recorded.has("resultJson")).isFalse();
            future.cancel(true);
        } finally {executor.shutdownNow();assertThat(executor.awaitTermination(2,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void pendingUnknownSentOrReservedNeverAnnounceAQuiescentBoundary() {
        Path proof=directory.resolve("never.json");
        var boundary=new LocalSemanticCheckpointBoundary(proof,json,Clock.systemUTC(),Duration.ofMillis(20));
        for(var state:List.of(UnitStatus.PLANNED,UnitStatus.SENT,UnitStatus.UNKNOWN))
            boundary.afterCommit(plan(0),List.of(unit(1,UnitStatus.VERIFIED),unit(2,UnitStatus.VERIFIED),unit(3,state)));
        boundary.afterCommit(plan(1),List.of(unit(1,UnitStatus.VERIFIED),unit(2,UnitStatus.VERIFIED),unit(3,UnitStatus.VERIFIED)));
        assertThat(proof).doesNotExist();
    }
    @Test void expiryStopsInsteadOfSilentlySendingTheNextRequestAndCannotOverwriteProof() throws Exception {
        Path proof=directory.resolve("once.json");
        var boundary=new LocalSemanticCheckpointBoundary(proof,json,Clock.systemUTC(),Duration.ofMillis(20));
        var units=List.of(unit(1,UnitStatus.VERIFIED),unit(2,UnitStatus.VERIFIED),unit(3,UnitStatus.VERIFIED));
        assertThatThrownBy(() -> boundary.afterCommit(plan(0),units)).isInstanceOf(CancellationException.class);
        var bytes=Files.readAllBytes(proof);
        boundary.afterCommit(plan(0),units);
        assertThat(Files.readAllBytes(proof)).isEqualTo(bytes);
        var second=new LocalSemanticCheckpointBoundary(proof,json,Clock.systemUTC(),Duration.ofMillis(20));
        assertThatThrownBy(() -> second.afterCommit(plan(0),units)).isInstanceOf(IllegalStateException.class);
        assertThat(Files.readAllBytes(proof)).isEqualTo(bytes);
    }
    @Test void preparationCannotMoveTheAbsolutePauseDeadline() throws Exception {
        var observed=new java.util.concurrent.atomic.AtomicInteger();
        Clock slowPreparation=new Clock(){
            public ZoneId getZone(){return ZoneOffset.UTC;}
            public Clock withZone(ZoneId zone){return this;}
            public Instant instant(){return observed.getAndIncrement()==0?now:now.plusSeconds(100);}
        };
        Path proof=directory.resolve("absolute.json");
        var boundary=new LocalSemanticCheckpointBoundary(proof,json,slowPreparation,Duration.ofMillis(20));
        assertThatThrownBy(() -> boundary.afterCommit(plan(0),List.of(unit(1,UnitStatus.VERIFIED),
                unit(2,UnitStatus.VERIFIED),unit(3,UnitStatus.VERIFIED)))).isInstanceOf(CancellationException.class);
        var recorded=json.readTree(Files.readString(proof));
        assertThat(Instant.parse(recorded.path("expiresAtUtc").asText())).isEqualTo(now.plusMillis(20));
    }
}
