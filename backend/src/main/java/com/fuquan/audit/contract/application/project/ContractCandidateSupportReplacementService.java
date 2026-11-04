package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.security.application.AuthContext;
import java.util.Comparator;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class ContractCandidateSupportReplacementService {
    /**
     * AUDIT_OPERATION_LOG.DETAIL has 2000 ASCII characters available. The exact successAudit layout
     * reserves at most 443 outside IDs (both longest modes, two 10-digit counts, two 19-digit versions),
     * plus at most 39 per quoted UUID and separator: floor((2000 - 443) / 39) = 39.
     * Audit boundary tests fence this conservative budget when the format changes.
     */
    public static final int MAX_SUPPORT_FILE_COUNT = 39;
    public static final String SUPPORT_FILE_COUNT_LIMIT_MESSAGE =
            "supportFileIds must contain at most " + MAX_SUPPORT_FILE_COUNT + " distinct file IDs";
    private static final Pattern SAFE_KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private final AuthContext auth;
    private final ContractCandidateSupportReplacementTransactionCoordinator transactions;

    public ContractCandidateSupportReplacementService(AuthContext auth,
            ContractCandidateSupportReplacementTransactionCoordinator transactions) {
        this.auth = auth;
        this.transactions = transactions;
    }

    public ContractCandidateSupportReplacementResult replace(ReplaceContractCandidateSupportAuthorityCommand command) {
        var actor = auth.currentUser().orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
        if (command.idempotencyKey() == null || !SAFE_KEY.matcher(command.idempotencyKey()).matches()
                || command.expectedCandidateVersion() < 0 || command.expectedCandidateVersion() == Long.MAX_VALUE) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "invalid candidate support replacement command");
        }
        var supportIds = command.supportFileIds().stream().distinct().sorted(Comparator.comparing(UUID::toString)).toList();
        if (supportIds.size() > MAX_SUPPORT_FILE_COUNT) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, SUPPORT_FILE_COUNT_LIMIT_MESSAGE);
        }
        var canonical = new ReplaceContractCandidateSupportAuthorityCommand(command.projectId(), command.candidateId(),
                command.expectedCandidateVersion(), supportIds, command.idempotencyKey(), command.metadata());
        return transactions.replace(canonical, actor);
    }
}
