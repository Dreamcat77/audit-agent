package com.fuquan.audit.contract.application.closeout;

import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.contract.application.reviewrun.ReviewStepOutputReadService;
import com.fuquan.audit.contract.domain.reviewrun.*;
import com.fuquan.audit.security.application.AuthContext;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class CloseoutOwnership {
    private final ReviewStepOutputReadService outputs;
    private final AuthContext auth;
    public CloseoutOwnership(ReviewStepOutputReadService outputs, AuthContext auth) {
        this.outputs = outputs; this.auth = auth;
    }
    public ReviewStepOutputReadService.RunOutput run(UUID project, UUID contract, UUID run) {
        var output = outputs.getRun(project, contract, run);
        String actor = auth.currentUser().orElseThrow(() ->
                new BusinessException(BusinessErrorCode.UNAUTHENTICATED)).userId();
        requireActor(output.manifest().run().requestedBy(), actor);
        return output;
    }
    public ContractReviewFinding finding(UUID project, UUID contract, UUID run, UUID finding) {
        return run(project, contract, run).steps().stream()
                .filter(s -> s.step().stepType() != ContractReviewStepType.F02_DETERMINISTIC
                        && s.step().stepType() != ContractReviewStepType.MATERIAL_VALIDATION)
                .flatMap(s -> s.findings().stream()).filter(f -> f.findingId().equals(finding))
                .findFirst().orElseThrow(CloseoutOwnership::notFound);
    }
    public static BusinessException notFound() { return new BusinessException(BusinessErrorCode.NOT_FOUND); }
    public static void requireActor(String requestedBy, String actor) {
        if (!requestedBy.equals(actor)) throw notFound();
    }
    public static BusinessException conflict() { return new BusinessException(BusinessErrorCode.CONFLICT, "closeout state cannot be verified"); }
}
