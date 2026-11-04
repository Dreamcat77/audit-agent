package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.contract.domain.project.ProjectFileRoleDecision;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityDecision;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class ContractCandidateMaterializationService {
    private final ContractCandidateMaterializationTransactionCoordinator transactions;
    private final ProjectFileRepository files;
    private final Clock clock;

    @Autowired
    public ContractCandidateMaterializationService(
            ContractCandidateMaterializationTransactionCoordinator transactions,
            ProjectFileRepository files, Clock clock) {
        this.transactions = transactions;
        this.files = files;
        this.clock = clock;
    }

    /** Focused unit-test seam: coordinator itself still checks the durable source-row lock. */
    public ContractCandidateMaterializationService(
            ContractCandidateMaterializationTransactionCoordinator transactions, Clock clock) {
        this.transactions = transactions;
        this.files = null;
        this.clock = clock;
    }

    public ContractCandidateMaterializationResult materializeFromDecision(
            ProjectFileRoleDecision decision, CurrentUser actor, ProjectRequestMetadata metadata) {
        throw new BusinessException(BusinessErrorCode.CONFLICT,
                "current Project identity decision is required for candidate materialization");
    }

    public ContractCandidateMaterializationResult materializeFromDecision(
            ProjectFileRoleDecision decision, ProjectFileIdentityDecision identityDecision,
            CurrentUser actor, ProjectRequestMetadata metadata) {
        if (files == null) {
            return transactions.materialize(decision.projectId(), decision.fileId(), decision.decisionId(),
                    identityDecision.decisionId(),
                    actor, metadata, UUID.randomUUID(), clock.instant());
        }
        return files.withFileAuthorityFence(decision.projectId(), decision.fileId(), () ->
                transactions.materialize(decision.projectId(), decision.fileId(), decision.decisionId(),
                        identityDecision.decisionId(),
                        actor, metadata, UUID.randomUUID(), clock.instant()));
    }
}
