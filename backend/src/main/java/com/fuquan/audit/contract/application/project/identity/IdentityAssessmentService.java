package com.fuquan.audit.contract.application.project.identity;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Clock;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class IdentityAssessmentService {
    private static final Pattern SAFE_KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private final ProjectFileRepository files;
    private final IdentityAssessmentTransactionCoordinator transactions;
    private final AuthContext authContext;
    private final Clock clock;
    public IdentityAssessmentService(ProjectFileRepository files, IdentityAssessmentTransactionCoordinator transactions,
            AuthContext authContext, Clock clock) {
        this.files = files;
        this.transactions = transactions;
        this.authContext = authContext;
        this.clock = clock;
    }
    public IdentityAssessmentResult assess(IdentityAssessmentCommand command) {
        return assess(command, actor());
    }

    public IdentityAssessmentResult assess(IdentityAssessmentCommand command, CurrentUser actor) {
        if (command.idempotencyKey() == null || !SAFE_KEY.matcher(command.idempotencyKey()).matches())
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "invalid Idempotency-Key");
        return files.withFileAuthorityFence(command.projectId(), command.fileId(), () -> {
            try {
                return transactions.assess(command, actor, UUID.randomUUID(), clock.instant());
            } catch (BusinessException known) {
                throw known;
            } catch (RuntimeException uncertain) {
                try { return transactions.reconcile(command, actor, uncertain, clock.instant()); }
                catch (RuntimeException failure) { uncertain.addSuppressed(failure); throw uncertain; }
            }
        });
    }
    public IdentityAssessmentResult get(UUID projectId, UUID fileId, UUID assessmentId) {
        actor();
        files.findByProjectIdAndId(projectId, fileId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "project file not found"));
        return transactions.reopen(projectId, fileId, assessmentId, false);
    }
    private CurrentUser actor() {
        return authContext.currentUser().orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
    }
}
