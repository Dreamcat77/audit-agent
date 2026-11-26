package com.fuquan.audit.contract.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import com.fuquan.audit.contract.infra.memory.analysis.InMemoryContractAnalysisRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContractAnalysisCancellationTest {
    @Test void cancellationFencesAnActiveWorkerEvenAfterItsLeaseExpires() {
        var repository=new InMemoryContractAnalysisRepository();var now=Instant.parse("2026-11-12T00:00:00Z");
        UUID project=UUID.randomUUID();var queued=new ContractAnalysisCase(ContractAnalysisCase.identity("owner",project),project,"owner",
                ContractAnalysisCase.sha256("input"),ContractAnalysisCase.State.QUEUED,0,null,null,0,now,now.plusSeconds(1800),List.of(),List.of(),null,1);
        repository.insert(queued);
        var running=queued.claim("worker-lease",now);
        assertThat(repository.replace(queued,running,now)).isTrue();
        var cancelled=running.release(ContractAnalysisCase.State.FAILED,running.sources(),running.steps(),"USER_CANCELLED");
        assertThat(repository.replace(running,cancelled,now.plusSeconds(500))).isTrue();
        var stale=running.checkpoint(running.sources(),running.steps());
        assertThat(repository.replace(running,stale,now.plusSeconds(501))).isFalse();
        assertThat(repository.find(queued.id()).orElseThrow().reasonCode()).isEqualTo("USER_CANCELLED");
    }
}
