package com.fuquan.audit.integration.file;

import com.fuquan.audit.contract.application.project.ProjectFileProcessingService;
import com.fuquan.audit.contract.application.project.port.ProjectFileJobRepository;
import com.fuquan.audit.security.application.port.UserAccountRoleQueryPort;
import com.fuquan.audit.common.observability.PipelineDiagnostics;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** One admitted file at a time; durable backlog survives navigation and restarts. */
@Component @EnableScheduling @Profile({"local-dev","dev29","customer"})
@ConditionalOnProperty(name="audit.contract.file-processing.worker-enabled",havingValue="true")
public class ProjectFileProcessingWorker {
    private final ProjectFileJobRepository jobs;
    private final ProjectFileProcessingService service;
    private final UserAccountRoleQueryPort users;
    private final Clock clock;
    private final AtomicBoolean busy=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"contract-file-worker"));
    private final ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"contract-file-heartbeat"));
    public ProjectFileProcessingWorker(ProjectFileJobRepository jobs,ProjectFileProcessingService service,
            UserAccountRoleQueryPort users,Clock clock) {this.jobs=jobs;this.service=service;this.users=users;this.clock=clock;}
    @Scheduled(fixedDelayString="${audit.contract.file-processing.scan-delay-ms:1500}")
    public void scan() {
        if(worker.isShutdown()||!busy.compareAndSet(false,true))return;
        try {
            var pending=jobs.pending(clock.instant(),1);
            if(pending.isEmpty()){busy.set(false);return;}
            var job=pending.get(0);
            worker.execute(()->{
                ScheduledFuture<?>[] beat={null};var prior=MDC.getCopyOfContextMap();
                var context=SecurityContextHolder.getContext();
                MDC.put("requestId",job.requestId());MDC.put("traceId",job.traceId());
                try {
                    var account=users.findActiveById(job.actorId());
                    if(account.isEmpty()||Collections.disjoint(account.get().roles(),Set.of("SYSTEM_ADMIN","PROCUREMENT_MANAGER","AUDITOR","LEGAL","PROCUREMENT_USER","BUDGET_USER","CONTRACT_USER"))) {
                        service.blockUnavailableOwner(job.id());
                        try(var span=PipelineDiagnostics.start("CONTRACT","FILE_OWNER_UNAVAILABLE",job.fileId(),Map.of())){
                            span.outcome("BLOCKED",Map.of("reason","FILE_OWNER_UNAVAILABLE"));
                        }
                        return;
                    }
                    var actor=account.get().toCurrentUser();
                    var newContext=SecurityContextHolder.createEmptyContext();
                    newContext.setAuthentication(new UsernamePasswordAuthenticationToken(actor,null,
                            actor.roles().stream().map(r->new SimpleGrantedAuthority("ROLE_"+r)).toList()));
                    SecurityContextHolder.setContext(newContext);
                    service.process(job.id(),actor,token->{
                        beat[0]=heartbeat.scheduleWithFixedDelay(()->{
                            try { jobs.renew(job.id(),token,clock.instant(),clock.instant().plusSeconds(900)); }
                            catch(RuntimeException e){log(job.fileId(),"FILE_LEASE_HEARTBEAT",e);}
                        },15,15,TimeUnit.SECONDS);
                    });
                } catch(RuntimeException e){log(job.fileId(),"FILE_BACKGROUND",e);}
                finally {
                    if(beat[0]!=null)beat[0].cancel(false);
                    SecurityContextHolder.setContext(context);
                    if(prior==null)MDC.clear();else MDC.setContextMap(prior);
                    busy.set(false);
                }
            });
        }catch(RuntimeException e){busy.set(false);log(null,"FILE_QUEUE_DISCOVERY",e);}
    }
    private static void log(UUID file,String stage,RuntimeException e) {
        try(var span=PipelineDiagnostics.start("CONTRACT",stage,file,Map.of())){span.fail(e);}
    }
    @PreDestroy public void close(){worker.shutdownNow();heartbeat.shutdownNow();}
}
