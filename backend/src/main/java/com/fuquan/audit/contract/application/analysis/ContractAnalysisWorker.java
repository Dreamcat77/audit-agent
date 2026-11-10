package com.fuquan.audit.contract.application.analysis;

import com.fuquan.audit.security.application.port.UserAccountRoleQueryPort;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @ConditionalOnProperty(name="audit.contract.analysis.enabled",havingValue="true")
public class ContractAnalysisWorker {
    private final ContractAnalysisRepository repository;private final ContractAnalysisProcessor processor;
    private final ContractAnalysisTransactions tx;private final UserAccountRoleQueryPort users;private final Clock clock;
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new SynchronousQueue<>(),r->new Thread(r,"contract-analysis-worker"),new ThreadPoolExecutor.AbortPolicy());
    public ContractAnalysisWorker(ContractAnalysisRepository repository,ContractAnalysisProcessor processor,ContractAnalysisTransactions tx,UserAccountRoleQueryPort users,Clock clock){this.repository=repository;this.processor=processor;this.tx=tx;this.users=users;this.clock=clock;}
    @Scheduled(fixedDelayString="${audit.contract.analysis.scan-delay-ms:2000}")
    public void scan(){
        try{for(var id:repository.pending(clock.instant(),20)){
            try{executor.execute(()->{
                try{var c=repository.find(id).orElseThrow();var actor=users.findActiveById(c.owner());
                    if(actor.isEmpty()||Collections.disjoint(actor.get().roles(),Set.of("SYSTEM_ADMIN","PROCUREMENT_MANAGER","AUDITOR","LEGAL","PROCUREMENT_USER","BUDGET_USER","CONTRACT_USER"))){tx.blockOwner(c);return;}
                    processor.process(id,actor.get().toCurrentUser());
                }catch(RuntimeException failure){log("EXECUTION",failure);}
            });}catch(RejectedExecutionException full){break;}
        }}catch(RuntimeException failure){log("DISCOVERY",failure);}
    }
    private void log(String stage,RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("CONTRACT_ANALYSIS_WORKER stage={} exceptionType={}",stage,failure.getClass().getSimpleName());}
    @jakarta.annotation.PreDestroy public void close(){executor.shutdownNow();}
}
