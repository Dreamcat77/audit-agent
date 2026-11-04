package com.fuquan.audit.contract.domain.project;

import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectContractAdapter;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectContractTest {

    @Test
    void oneProjectOwnsManyContractsWhileContractAndCandidateStayUnique() {
        InMemoryProjectContractAdapter adapter = new InMemoryProjectContractAdapter();
        UUID projectId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-26T00:00:00Z");
        ProjectContract first = new ProjectContract(projectId, UUID.randomUUID(), UUID.randomUUID(), "actor", now);
        ProjectContract second = new ProjectContract(projectId, UUID.randomUUID(), UUID.randomUUID(), "actor", now.plusSeconds(1));

        adapter.insert(first);
        adapter.insert(second);

        assertThat(adapter.findByProjectId(projectId)).containsExactly(first, second);
        assertThat(adapter.findByContractId(first.contractId())).contains(first);
        assertThat(adapter.findByCandidateId(second.candidateId())).contains(second);
        assertThatThrownBy(() -> adapter.insert(new ProjectContract(UUID.randomUUID(), first.contractId(),
                UUID.randomUUID(), "other", now.plusSeconds(2))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> adapter.insert(new ProjectContract(projectId, UUID.randomUUID(),
                first.candidateId(), "other", now.plusSeconds(2))))
                .isInstanceOf(BusinessException.class);
    }
}
