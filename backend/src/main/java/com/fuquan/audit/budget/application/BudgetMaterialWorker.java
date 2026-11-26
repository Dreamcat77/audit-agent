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
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PreDestroy;

/** DM8 owns pending work; bounded workers limit concurrent model load. */
@Component
@ConditionalOnProperty(name = "audit.budget.material.worker-enabled", havingValue = "true")
public class BudgetMaterialWorker {
    private final BudgetMaterialJobRepository jobs;
    private final BudgetMaterialService service;
    private final UserAccountRoleQueryPort users;
    private com.fuquan.audit.common.application.TaskCancellationRegistry cancellationRegistry=new com.fuquan.audit.common.application.TaskCancellationRegistry();
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setCancellationRegistry(com.fuquan.audit.common.application.TaskCancellationRegistry value){this.cancellationRegistry=value;}
    private final Set<UUID> active=ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicBoolean batchBusy=new java.util.concurrent.atomic.AtomicBoolean();
    private final int parallelism;
    private final ThreadPoolExecutor worker;
    private final ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"budget-material-heartbeat"));
    public BudgetMaterialWorker(BudgetMaterialJobRepository jobs,BudgetMaterialService service,UserAccountRoleQueryPort users,
            @Value("${audit.budget.material.max-concurrent:2}") int parallelism) {
        if(parallelism<1||parallelism>8)throw new IllegalArgumentException("invalid budget material worker capacity");
        this.jobs=jobs;this.service=service;this.users=users;this.parallelism=parallelism;
        this.worker=new ThreadPoolExecutor(parallelism,parallelism,0L,TimeUnit.SECONDS,new SynchronousQueue<>(),
            r->new Thread(r,"budget-material-worker"),new ThreadPoolExecutor.AbortPolicy());
    }
    @Scheduled(fixedDelayString="${audit.budget.material.scan-delay-ms:1500}")
    public void scan() {
        if(worker.isShutdown()||active.size()>=parallelism) return;
        try {
            var pending=jobs.pending(Instant.now(),parallelism*2);
            if(pending.isEmpty()&&active.isEmpty()&&batchBusy.compareAndSet(false,true)){
                try {worker.execute(()->{try{service.processPendingBatch();}catch(RuntimeException e){log(null,"BATCH_DISCOVERY",e);}finally{batchBusy.set(false);}});}
                catch(RejectedExecutionException full){batchBusy.set(false);}
                return;
            }
            for(var job:pending) {
              if(active.size()>=parallelism)break;
              if(!active.add(job.id()))continue;
              try {worker.execute(()->{
                ScheduledFuture<?>[] beat={null};var prior=MDC.getCopyOfContextMap();MDC.put("requestId","budget-material-"+job.id());
                try (var registration=cancellationRegistry.register(job.id())) {
                    var user=users.findActiveById(job.actorId());
                    if(user.isEmpty()||Collections.disjoint(user.get().roles(),Set.of("SYSTEM_ADMIN","PROCUREMENT_MANAGER","AUDITOR","FINANCE_REVIEWER","PROCUREMENT_USER","BUDGET_USER","CONTRACT_USER"))) {service.rejectOwner(job.id());return;}
                    service.process(job.id(),user.get().toCurrentUser(),token->{
                        beat[0]=heartbeat.scheduleWithFixedDelay(()->{
                            try {jobs.renew(job.id(),token,Instant.now().plusSeconds(1200));}
                            catch(RuntimeException e){log(job.id(),"LEASE_HEARTBEAT",e);}
                        },15,15,TimeUnit.SECONDS);
                    });
                } catch(RuntimeException e){log(job.id(),"BACKGROUND_ERROR",e);}
                finally {if(beat[0]!=null)beat[0].cancel(false);if(prior==null)MDC.clear();else MDC.setContextMap(prior);active.remove(job.id());Thread.interrupted();}
              });} catch(RejectedExecutionException full){active.remove(job.id());}
            }
        } catch(RuntimeException e){log(null,"BACKGROUND_DISCOVERY",e);}
    }
    @Scheduled(fixedDelayString="${audit.material.cleanup-delay-ms:30000}")
    public void cleanup(){service.cleanupTimedOut();}
    private static void log(UUID id,String stage,RuntimeException e) {try(var span=PipelineDiagnostics.start("BUDGET",stage,id,Map.of())){span.fail(e);}}
    @PreDestroy public void close() {worker.shutdownNow();heartbeat.shutdownNow();}
}
