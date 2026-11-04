package com.fuquan.audit.integration.file;

import com.fuquan.audit.contract.application.project.ProjectFileProcessingService;
import com.fuquan.audit.contract.application.project.port.ProjectFileJobRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Explicit local/mock seam; formal profiles always resolve current actor authority from DM8. */
@Component @Profile("local") @EnableScheduling
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="audit.contract.file-processing.local-worker-enabled",havingValue="true",matchIfMissing=true)
public class ProjectFileLocalProcessingWorker implements
        com.fuquan.audit.contract.application.project.port.ProjectFileProcessingAdmission {
    private final ProjectFileJobRepository jobs;
    private final ProjectFileRepository files;
    private final ProjectFileProcessingService processing;
    private final Clock clock;
    private final ConcurrentMap<UUID,CurrentUser> acceptedActors=new ConcurrentHashMap<>();
    private final AtomicBoolean busy=new AtomicBoolean();
    private final ExecutorService executor=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),r->new Thread(r,"contract-local-file-worker"));
    public ProjectFileLocalProcessingWorker(ProjectFileJobRepository jobs,ProjectFileRepository files,
            ProjectFileProcessingService processing,Clock clock) {
        this.jobs=jobs;this.files=files;this.processing=processing;this.clock=clock;
    }
    @Override public void accepted(UUID id,CurrentUser actor) {
        var job=jobs.find(id).orElseThrow();
        if(!job.actorId().equals(actor.userId()))throw new IllegalArgumentException("local processing actor mismatch");
        if(Set.of("QUEUED","PROCESSING").contains(job.status()))acceptedActors.putIfAbsent(id,actor);
    }
    @Scheduled(fixedDelay=100)
    public void scan() {
        if(executor.isShutdown()||!busy.compareAndSet(false,true))return;
        try {
            var next=jobs.pending(clock.instant(),64).stream().filter(job->acceptedActors.containsKey(job.id())).findFirst();
            if(next.isEmpty()){busy.set(false);return;}
            var job=next.get();var actor=acceptedActors.get(job.id());
            executor.execute(()->{
                var prior=SecurityContextHolder.getContext();var priorMdc=MDC.getCopyOfContextMap();
                try {
                    var context=SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(new UsernamePasswordAuthenticationToken(actor,null,
                            actor.roles().stream().map(role->new SimpleGrantedAuthority("ROLE_"+role)).toList()));
                    SecurityContextHolder.setContext(context);MDC.put("requestId",job.requestId());MDC.put("traceId",job.traceId());
                    var completed=processing.process(job.id(),actor,ignored->{});
                    if("SUCCEEDED".equals(completed.status())) {
                        // Only persisted children of this accepted ZIP inherit its actor seam.
                        // Never admit unrelated queued jobs merely because their project/actor matches.
                        for(var child:files.findByProjectId(job.projectId())) {
                            if(!job.fileId().equals(child.parentArchiveFileId()))continue;
                            jobs.byFile(job.projectId(),child.id()).filter(value->value.actorId().equals(actor.userId())
                                    &&value.fileSha256().equals(child.sha256()))
                                    .ifPresent(value->accepted(value.id(),actor));
                        }
                    }
                } catch(RuntimeException failure) {
                    try(var span=com.fuquan.audit.common.observability.PipelineDiagnostics.start("CONTRACT","LOCAL_FILE_BACKGROUND",job.fileId(),Map.of())) {
                        span.fail(failure);
                    }
                } finally {
                    if(jobs.find(job.id()).map(value->Set.of("SUCCEEDED","FAILED","UNKNOWN").contains(value.status())).orElse(true)) {
                        acceptedActors.remove(job.id());
                    }
                    SecurityContextHolder.setContext(prior);if(priorMdc==null)MDC.clear();else MDC.setContextMap(priorMdc);busy.set(false);
                }
            });
        } catch(RuntimeException failure){busy.set(false);throw failure;}
    }
    @PreDestroy public void close(){executor.shutdownNow();acceptedActors.clear();}
}
