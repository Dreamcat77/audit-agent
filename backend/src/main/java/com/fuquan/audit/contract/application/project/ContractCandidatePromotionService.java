package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.contract.domain.project.ContractCandidateConfirmation;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

@Service
public class ContractCandidatePromotionService {
    private static final String FINGERPRINT_VERSION = "contract-candidate-promotion-v4";
    private final ContractCandidatePromotionTransactionCoordinator transactions;
    private final AuthContext authContext;
    private final FlowDebugLogger flowDebug;

    public ContractCandidatePromotionService(ContractCandidatePromotionTransactionCoordinator transactions,
            AuthContext authContext, FlowDebugLogger flowDebug) {
        this.transactions = transactions;
        this.authContext = authContext; this.flowDebug = flowDebug;
    }

    public ContractCandidatePromotionResult promote(PromoteContractCandidateCommand command) {
        CurrentUser actor = authContext.currentUser()
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
        return promote(command, actor);
    }

    public ContractCandidatePromotionResult promoteAutomatically(PromoteContractCandidateCommand command,
            CurrentUser actor) {
        if (command.idempotencyKey() == null || !command.idempotencyKey().startsWith("project-intake-auto-promote:")) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "invalid automatic promotion identity");
        }
        return promote(command, actor);
    }

    private ContractCandidatePromotionResult promote(PromoteContractCandidateCommand command,
            CurrentUser actor) {
        flowDebug.log("PROJECT_PROMOTION_START", "CONTRACT", "APPLICATION", "promote",
                java.util.Map.of("projectId", command.projectId(), "candidateId", command.candidateId()));
        var prepared = transactions.prepare(command, actor);
        if (prepared instanceof ContractCandidatePromotionTransactionCoordinator.CompletedPromotion completed) {
            return completed.result();
        }
        return transactions.promote(command, actor,
                (ContractCandidatePromotionTransactionCoordinator.FreshPromotion) prepared);
    }

    static String fingerprint(CurrentUser actor, PromoteContractCandidateCommand command,
            ContractCandidateConfirmation confirmation) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, FINGERPRINT_VERSION);
            add(digest, actor.userId()); add(digest, command.projectId().toString());
            add(digest, command.candidateId().toString());
            add(digest, Long.toString(command.expectedVersion()));
            add(digest, command.confirmationId().toString());
            add(digest, confirmation.payloadFingerprint());
            add(digest, confirmation.sourceFileId().toString());
            add(digest, confirmation.sourceParseResultId().toString());
            add(digest, confirmation.sourceRoleDecisionId().toString());
            add(digest, confirmation.sourceIdentityDecisionId().toString());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required by Java 17", failure);
        }
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24)); digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8)); digest.update((byte) bytes.length);
        digest.update(bytes);
    }
}
