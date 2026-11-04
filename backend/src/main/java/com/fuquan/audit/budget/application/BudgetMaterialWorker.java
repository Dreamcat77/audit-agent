package com.fuquan.audit.budget.application;

import com.fuquan.audit.budget.application.port.BudgetMaterialJobRepository;
import com.fuquan.audit.common.observability.PipelineDiagnostics;
import com.fuquan.audit.security.application.port.UserAccountRoleQueryPort;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

/** One worker limits simultaneous budget model load. DM8 owns pending work. */
@Component
@ConditionalOnProperty(name = "audit.budget.material.worker-enabled", havingValue = "true")
public class BudgetMaterialWorker {
    private final BudgetMaterialJobRepository jobs;
    private final BudgetMaterialService service;
    private final UserAccountRoleQueryPort users;
    private final java.util.concurrent.atomic.AtomicBoolean busy=new java.util.concurrent.atomic.AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"budget-material-worker"));
    private final ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"budget-material-heartbeat"));
    public BudgetMaterialWorker(BudgetMaterialJobRepository jobs,BudgetMaterialService service,UserAccountRoleQueryPort users) {this.jobs=jobs;this.service=service;this.users=users;}
    @Scheduled(fixedDelayString="${audit.budget.material.scan-delay-ms:1500}")
    public void scan() {
        if(worker.isShutdown()||!busy.compareAndSet(false,true)) return;
        try {
            var pending=jobs.pending(Instant.now(),1);
            if(pending.isEmpty()){
                worker.execute(()->{try{service.processPendingBatch();}catch(RuntimeException e){log(null,"BATCH_DISCOVERY",e);}finally{busy.set(false);}});
                return;
            }
            var job=pending.get(0);
            worker.execute(()->{
                ScheduledFuture<?>[] beat={null};var prior=MDC.getCopyOfContextMap();MDC.put("requestId","budget-material-"+job.id());
                try {
                    var user=users.findActiveById(job.actorId());
                    if(user.isEmpty()||Collections.disjoint(user.get().roles(),Set.of("SYSTEM_ADMIN","PROCUREMENT_MANAGER","AUDITOR","FINANCE_REVIEWER"))) {service.rejectOwner(job.id());return;}
                    service.process(job.id(),user.get().toCurrentUser(),token->{
                        beat[0]=heartbeat.scheduleWithFixedDelay(()->{
                            try {jobs.renew(job.id(),token,Instant.now().plusSeconds(1200));}
                            catch(RuntimeException e){log(job.id(),"LEASE_HEARTBEAT",e);}
                        },15,15,TimeUnit.SECONDS);
                    });
                } catch(RuntimeException e){log(job.id(),"BACKGROUND_ERROR",e);}
                finally {if(beat[0]!=null)beat[0].cancel(false);if(prior==null)MDC.clear();else MDC.setContextMap(prior);busy.set(false);}
            });
        } catch(RuntimeException e){busy.set(false);log(null,"BACKGROUND_DISCOVERY",e);}
    }
    @Scheduled(fixedDelayString="${audit.material.cleanup-delay-ms:30000}")
    public void cleanup(){service.cleanupTimedOut();}
    private static void log(UUID id,String stage,RuntimeException e) {try(var span=PipelineDiagnostics.start("BUDGET",stage,id,Map.of())){span.fail(e);}}
    @PreDestroy public void close() {worker.shutdownNow();heartbeat.shutdownNow();}
}
