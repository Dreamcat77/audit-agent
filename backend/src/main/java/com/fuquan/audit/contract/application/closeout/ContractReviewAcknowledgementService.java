package com.fuquan.audit.contract.application.closeout;

import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.contract.application.closeout.port.ContractReviewCloseoutRepository;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.domain.closeout.ContractReviewFindingAcknowledgement;
import com.fuquan.audit.security.application.AuthContext;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class ContractReviewAcknowledgementService {
    private final AuthContext auth;
    private final ContractReviewCloseoutRepository repository;
    private final AcknowledgementTransactionCoordinator transactions;
    public ContractReviewAcknowledgementService(AuthContext auth, ContractReviewCloseoutRepository repository,
            AcknowledgementTransactionCoordinator transactions) {
        this.auth = auth; this.repository = repository; this.transactions = transactions;
    }
    public ContractReviewFindingAcknowledgement acknowledge(UUID project, UUID contract, UUID run, UUID finding,
            long version, String note, String key, ProjectRequestMetadata metadata) {
        String actor = auth.currentUser().orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED)).userId();
        String normalized;
        try { normalized = ContractReviewFindingAcknowledgement.normalizeNote(note); }
        catch (IllegalArgumentException badNote) { throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT); }
        try { return transactions.create(project, contract, run, finding, version, normalized, key, actor, metadata); }
        catch (DuplicateKeyException race) {
            // The first transaction has rolled back. A new transaction may see the durable winner.
            return transactions.create(project, contract, run, finding, version, normalized, key, actor, metadata);
        }
    }
    public ContractReviewFindingAcknowledgement get(UUID project, UUID contract, UUID run, UUID finding, UUID ack) {
        var fact = transactions.verifyFinding(project, contract, run, finding);
        var result = repository.findAcknowledgement(ack).filter(a -> owns(a, project, contract, run, finding))
                .orElseThrow(CloseoutOwnership::notFound);
        AcknowledgementTransactionCoordinator.verifyFact(result, project, contract, run, fact);
        return result;
    }
    public List<ContractReviewFindingAcknowledgement> list(UUID project, UUID contract, UUID run, UUID finding) {
        var fact = transactions.verifyFinding(project, contract, run, finding);
        return repository.findAcknowledgementByFinding(finding).map(a -> {
            AcknowledgementTransactionCoordinator.verifyFact(a, project, contract, run, fact);
            return List.of(a);
        }).orElse(List.of());
    }
    private static boolean owns(ContractReviewFindingAcknowledgement a, UUID p, UUID c, UUID r, UUID f) {
        return a.projectId().equals(p) && a.contractId().equals(c) && a.reviewRunId().equals(r) && a.findingId().equals(f);
    }
}
