package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.port.ContractCandidateConfirmationRepository;
import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.domain.project.ContractCandidateConfirmation;
import com.fuquan.audit.security.domain.CurrentUser;
import com.fuquan.audit.security.application.AuthContext;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ContractCandidateConfirmationService {
    private final AuthContext auth;
    private final ContractCandidateConfirmationTransactionCoordinator transactions;
    private final ContractCandidateConfirmationRepository confirmations;
    private final ContractCandidateRepository candidates;
    private final ContractCandidateConfirmationPayloadCanonicalizer canonicalizer;

    public ContractCandidateConfirmationService(AuthContext auth,
            ContractCandidateConfirmationTransactionCoordinator transactions,
            ContractCandidateConfirmationRepository confirmations, ContractCandidateRepository candidates,
            ContractCandidateConfirmationPayloadCanonicalizer canonicalizer) {
        this.auth = auth; this.transactions = transactions; this.confirmations = confirmations;
        this.candidates = candidates; this.canonicalizer = canonicalizer;
    }

    public ContractCandidateConfirmationResult confirm(ConfirmContractCandidateCommand command) {
        var actor = auth.currentUser().orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
        return transactions.confirm(command, actor);
    }

    public ContractCandidateConfirmationResult confirmAutomatically(ConfirmContractCandidateCommand command,
            CurrentUser actor) {
        if (command.idempotencyKey() == null || !command.idempotencyKey().startsWith("project-intake-auto-candidate:")) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "invalid automatic confirmation identity");
        }
        return transactions.confirm(command, actor);
    }

    public ContractCandidateConfirmationResult get(UUID projectId, UUID candidateId, UUID confirmationId) {
        requireCandidate(projectId, candidateId);
        ContractCandidateConfirmation value = confirmations.findByProjectAndCandidateAndId(
                projectId, candidateId, confirmationId).orElseThrow(() ->
                        new BusinessException(BusinessErrorCode.NOT_FOUND, "candidate confirmation not found"));
        return read(value);
    }

    public ContractCandidateConfirmationResult latest(UUID projectId, UUID candidateId) {
        requireCandidate(projectId, candidateId);
        ContractCandidateConfirmation value = confirmations.findLatestByProjectAndCandidate(
                projectId, candidateId).orElseThrow(() ->
                        new BusinessException(BusinessErrorCode.NOT_FOUND, "candidate confirmation not found"));
        return read(value);
    }

    private void requireCandidate(UUID projectId, UUID candidateId) {
        candidates.findByProjectIdAndId(projectId, candidateId).orElseThrow(() ->
                new BusinessException(BusinessErrorCode.NOT_FOUND, "contract candidate not found"));
    }

    private ContractCandidateConfirmationResult read(ContractCandidateConfirmation value) {
        return new ContractCandidateConfirmationResult(value, canonicalizer.decodeAndVerify(value), false);
    }
}
