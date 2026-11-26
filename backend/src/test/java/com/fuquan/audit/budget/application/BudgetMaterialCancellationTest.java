package com.fuquan.audit.budget.application;

import static org.assertj.core.api.Assertions.assertThat;
import com.fuquan.audit.budget.domain.BudgetMaterialJob;
import com.fuquan.audit.budget.infra.memory.InMemoryBudgetMaterialJobRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BudgetMaterialCancellationTest {
    @Test void cancellationInvalidatesLeaseAndCannotBeOverwrittenByWorkerCompletion() {
        var jobs=new InMemoryBudgetMaterialJobRepository();var now=Instant.now();var id=UUID.randomUUID();
        jobs.insert(new BudgetMaterialJob(id,"owner",UUID.randomUUID().toString(),"b".repeat(64),"QUEUED",null,null,now));
        assertThat(jobs.claim(id,"worker-lease",now,now.plusSeconds(60))).isTrue();
        assertThat(jobs.cancel(id,now.plusSeconds(1))).isTrue();
        assertThat(jobs.complete(id,"worker-lease","PARSED",now.plusSeconds(2))).isFalse();
        assertThat(jobs.find(id).orElseThrow()).satisfies(job->{
            assertThat(job.status()).isEqualTo("FAILED");
            assertThat(job.leaseToken()).isNull();
        });
        assertThat(jobs.cancel(id,now.plusSeconds(3))).isFalse();
    }
}
