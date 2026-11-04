package com.fuquan.audit.integration.file;

import com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointBoundary;
import static com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit local-dev acceptance aid. Disabled in ordinary daily operation. */
public final class LocalSemanticCheckpointBoundary implements SemanticCheckpointBoundary {
    private final Path proof;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration maximumWait;
    private final AtomicBoolean entered = new AtomicBoolean();
    public LocalSemanticCheckpointBoundary(Path proof, ObjectMapper json, Clock clock, Duration maximumWait) {
        this.proof=Objects.requireNonNull(proof).toAbsolutePath().normalize();
        this.json=Objects.requireNonNull(json);this.clock=Objects.requireNonNull(clock);
        if(maximumWait.isNegative() || maximumWait.isZero() || maximumWait.compareTo(Duration.ofSeconds(90))>0)
            throw new IllegalArgumentException("local semantic boundary wait must be bounded");
        this.maximumWait=maximumWait;
    }
    @Override public void afterCommit(Plan plan, List<Unit> units) {
        if(entered.get() || plan.status()!=PlanStatus.ACTIVE || plan.reservedCalls()!=0 || plan.reservedMs()!=0
                || units.stream().anyMatch(u -> !u.planId().equals(plan.planId())
                    || u.status()==UnitStatus.SENT || u.status()==UnitStatus.UNKNOWN)) return;
        var latest=new LinkedHashMap<String,Unit>();
        var analysisKeys=new HashSet<String>();
        for(var unit:units){
            if(unit.executionNo()==1 && unit.purpose()==Purpose.ANALYZE) analysisKeys.add(unit.logicalKey());
            latest.merge(unit.logicalKey(),unit,(a,b) -> a.executionNo()>b.executionNo()?a:b);
        }
        var verified=latest.values().stream().filter(u -> analysisKeys.contains(u.logicalKey())
                && u.status()==UnitStatus.VERIFIED).sorted(Comparator.comparingInt(Unit::ordinal)).toList();
        if(verified.size()!=3 || verified.get(0).ordinal()!=1 || verified.get(1).ordinal()!=2
                || verified.get(2).ordinal()!=3 || !entered.compareAndSet(false,true)) return;
        Instant enteredAt=clock.instant();
        Instant stopAt=enteredAt.plus(maximumWait);
        if(stopAt.isAfter(plan.spec().deadlineAt().minusSeconds(2))) stopAt=plan.spec().deadlineAt().minusSeconds(2);
        long wait=Duration.between(enteredAt,stopAt).toMillis();
        long monotonicStop=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.max(0,wait));
        if(wait<=0 || Thread.currentThread().isInterrupted()) throw new CancellationException("local semantic boundary interrupted");
        try {
            if(!Files.isDirectory(proof.getParent()) || !proof.getParent().toRealPath().equals(proof.getParent()))
                throw new IllegalStateException("local semantic boundary directory is unproven");
            var value=new LinkedHashMap<String,Object>();
            value.put("kind","LOCAL_SEMANTIC_COMMITTED_BOUNDARY");
            value.put("runId",plan.runId());value.put("stepId",plan.stepId());
            value.put("attemptId",plan.currentAttemptId());value.put("planId",plan.planId());
            value.put("inputFingerprint",plan.spec().inputFingerprint());value.put("policyFingerprint",plan.spec().policyFingerprint());
            value.put("owner",plan.owner());value.put("fence",plan.fence());value.put("leaseUntil",plan.leaseUntil().toString());
            value.put("deadlineAt",plan.spec().deadlineAt().toString());value.put("usedCalls",plan.usedCalls());
            value.put("reservedCalls",plan.reservedCalls());value.put("usedMs",plan.usedMs());value.put("reservedMs",plan.reservedMs());
            value.put("observedAtUtc",enteredAt.toString());value.put("expiresAtUtc",stopAt.toString());
            value.put("verifiedResults",verified.stream().map(u -> Map.of("logicalKey",u.logicalKey(),
                    "ordinal",u.ordinal(),"resultSha256",u.resultSha256(),"executionId",u.executionId())).toList());
            byte[] bytes=json.writeValueAsBytes(value);
            if(bytes.length>8192) throw new IllegalStateException("local semantic boundary proof exceeds bound");
            try(var channel=FileChannel.open(proof,StandardOpenOption.WRITE,StandardOpenOption.CREATE_NEW)){
                var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining()) channel.write(buffer);channel.force(true);
            }
            // Never automatically release a stale proof and dispatch another request.
            long remaining=Math.min(Duration.between(clock.instant(),stopAt).toMillis(),
                    TimeUnit.NANOSECONDS.toMillis(monotonicStop-System.nanoTime()));
            if(remaining>0) new CountDownLatch(1).await(remaining,TimeUnit.MILLISECONDS);
        } catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        catch(java.io.IOException unavailable){throw new IllegalStateException("local semantic boundary proof is unavailable");}
        throw new CancellationException("local semantic boundary stopped before next outbound request");
    }
}
