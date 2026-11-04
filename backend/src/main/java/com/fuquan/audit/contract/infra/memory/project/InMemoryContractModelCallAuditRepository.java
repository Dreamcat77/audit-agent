package com.fuquan.audit.contract.infra.memory.project;

import com.fuquan.audit.contract.application.project.factai.ContractModelCallAudit;
import com.fuquan.audit.contract.application.project.factai.ContractModelCallAuditRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryContractModelCallAuditRepository
        implements ContractModelCallAuditRepository {
    private final List<ContractModelCallAudit> calls = new CopyOnWriteArrayList<>();

    @Override public void insertAll(List<ContractModelCallAudit> values) {
        calls.addAll(List.copyOf(values));
    }

    @Override public List<ContractModelCallAudit> findByReviewStepAttempt(UUID attemptId) {
        return calls.stream().filter(value -> attemptId.equals(value.reviewStepAttemptId())).toList();
    }
}
