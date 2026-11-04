package com.fuquan.audit.procurement.autocase;

import com.fuquan.audit.security.application.port.UserAccountRoleQueryPort;
import java.time.Clock;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The database owns discovery and fences; no Redis queue or process-local result store. */
@Component @ConditionalOnProperty(name="audit.procurement.auto-case.enabled",havingValue="true")
public class AuditCaseWorker {
    private final AuditCaseRepository repository;private final AuditCaseService service;
    private final UserAccountRoleQueryPort users;private final Clock clock;
    private final java.util.concurrent.ThreadPoolExecutor executor=new java.util.concurrent.ThreadPoolExecutor(1,1,0,
            java.util.concurrent.TimeUnit.SECONDS,new java.util.concurrent.SynchronousQueue<>(),r->new Thread(r,"auto-case-worker"),new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    public AuditCaseWorker(AuditCaseRepository repository,AuditCaseService service,UserAccountRoleQueryPort users,Clock clock){this.repository=repository;this.service=service;this.users=users;this.clock=clock;}
    @Scheduled(fixedDelayString="${audit.procurement.auto-case.scan-delay-ms:2000}")
    public void scan(){
        try {
            for(var c:repository.pending(clock.instant())) {
                try {executor.execute(()->{
                    try {
                        var owner=users.findActiveById(c.owner());
                        if(owner.isEmpty()||Collections.disjoint(owner.get().roles(),Set.of("SYSTEM_ADMIN","PROCUREMENT_MANAGER","AUDITOR"))) {service.rejectOwner(c);return;}
                        service.process(c.id(),owner.get().toCurrentUser());
                    }catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("AUTO_CASE_EXECUTION_FAILED exceptionType={}",failure.getClass().getSimpleName());}
                });}catch(java.util.concurrent.RejectedExecutionException full){break;}
            }
        }catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("AUTO_CASE_SCAN_FAILED exceptionType={}",failure.getClass().getSimpleName());}
    }
    @jakarta.annotation.PreDestroy public void close(){executor.shutdownNow();}
}
