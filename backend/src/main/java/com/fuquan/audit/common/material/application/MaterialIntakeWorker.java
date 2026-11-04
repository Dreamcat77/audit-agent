package com.fuquan.audit.common.material.application;

import com.fuquan.audit.common.material.application.port.MaterialIntakeRepository;
import com.fuquan.audit.common.material.domain.MaterialIntake;
import com.fuquan.audit.common.observability.PipelineDiagnostics;
import com.fuquan.audit.security.application.port.UserAccountRoleQueryPort;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

/** DM8-backed work discovery; executor memory and Redis never own the queue. */
@Component
@ConditionalOnProperty(name = "audit.procurement.material.worker-enabled", havingValue = "true")
public class MaterialIntakeWorker {
    private final MaterialIntakeRepository repository;
    private final MaterialIntakeService service;
    private final UserAccountRoleQueryPort users;
    private final Clock clock;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService heartbeat;
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();
    private final int parallelism;
    public MaterialIntakeWorker(MaterialIntakeRepository repository, MaterialIntakeService service,
            UserAccountRoleQueryPort users, Clock clock,
            @Value("${audit.procurement.material.max-concurrent:2}") int parallelism) {
        if (parallelism < 1 || parallelism > 8) throw new IllegalArgumentException("invalid material worker capacity");
        this.repository=repository; this.service=service; this.users=users; this.clock=clock; this.parallelism=parallelism;
        this.workers=new ThreadPoolExecutor(parallelism, parallelism, 0L, TimeUnit.SECONDS,
                new SynchronousQueue<>(), runnable -> new Thread(runnable, "procurement-material-worker"),
                new ThreadPoolExecutor.AbortPolicy());
        this.heartbeat=Executors.newScheduledThreadPool(1, runnable -> new Thread(runnable, "procurement-material-heartbeat"));
    }
    @Scheduled(fixedDelayString = "${audit.procurement.material.scan-delay-ms:1500}")
    public void scan() {
        if (workers.isShutdown() || active.size() >= parallelism) return;
        try {
            for (var candidate : repository.pending(clock.instant(), parallelism * 2)) {
                if (active.size() >= parallelism) break;
                if (!active.add(candidate.id())) continue;
                try { workers.execute(() -> execute(candidate)); }
                catch (RejectedExecutionException full) { active.remove(candidate.id()); }
            }
        } catch (RuntimeException error) {
            try (var span=PipelineDiagnostics.start("PROCUREMENT", "BACKGROUND_DISCOVERY", null, Map.of())) { span.fail(error); }
        }
    }
    private void execute(MaterialIntake candidate) {
        Map<String,String> prior = MDC.getCopyOfContextMap();
        MDC.put("requestId", "material-" + candidate.id());
        final ScheduledFuture<?>[] renewal = {null};
        try (var span=PipelineDiagnostics.start("PROCUREMENT", "BACKGROUND_EXECUTION", candidate.id(), Map.of())) {
            var actor = users.findActiveById(candidate.actorId());
            if (actor.isEmpty() || Collections.disjoint(actor.get().roles(),
                    Set.of("SYSTEM_ADMIN", "PROCUREMENT_MANAGER", "PROCUREMENT_APPLICANT", "AUDITOR"))) {
                service.rejectPendingOwner(candidate.id());
                span.outcome("FAILED", Map.of("reason", "MATERIAL_OWNER_UNAVAILABLE"));
                return;
            }
            service.processPending(candidate.id(), actor.get().toCurrentUser(), record -> {
                renewal[0] = heartbeat.scheduleWithFixedDelay(() -> {
                    try (var beat=PipelineDiagnostics.start("PROCUREMENT", "LEASE_HEARTBEAT", record.id(), Map.of())) {
                        try { beat.outcome(service.renew(record) ? "RENEWED" : "LEASE_LOST", Map.of()); }
                        catch (RuntimeException error) { beat.fail(error); }
                    }
                }, 15, 15, TimeUnit.SECONDS);
            });
            span.success(Map.of());
        } catch (RuntimeException error) {
            try (var span=PipelineDiagnostics.start("PROCUREMENT", "BACKGROUND_ERROR", candidate.id(), Map.of())) { span.fail(error); }
        } finally {
            if (renewal[0] != null) renewal[0].cancel(false);
            active.remove(candidate.id());
            if (prior == null) MDC.clear(); else MDC.setContextMap(prior);
        }
    }
    @Scheduled(fixedDelayString="${audit.material.cleanup-delay-ms:30000}")
    public void cleanup(){service.cleanupTimedOut();}
    @PreDestroy public void close() {
        workers.shutdownNow(); heartbeat.shutdownNow();
        // Interrupted PROCESSING rows remain durable and are recovered after lease expiry.
    }
}
