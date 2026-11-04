package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewRunRepository;
import com.fuquan.audit.security.application.AuthContext;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContractReviewRunDiscoveryService {
    private final AuthContext auth;
    private final ContractReviewRunRepository runs;
    private final ReviewStepOutputReadService outputs;

    public ContractReviewRunDiscoveryService(
            AuthContext auth,
            ContractReviewRunRepository runs,
            ReviewStepOutputReadService outputs) {
        this.auth = auth;
        this.runs = runs;
        this.outputs = outputs;
    }

    @Transactional(readOnly = true)
    public ReviewStepOutputReadService.RunOutput getLatest(UUID projectId, UUID contractId) {
        var actor = auth.currentUser()
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
        var run = runs.findLatestByProjectIdAndContractIdAndRequestedBy(
                        projectId, contractId, actor.userId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND));
        return outputs.getRun(projectId, contractId, run.runId());
    }
}
