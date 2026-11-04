package com.fuquan.audit.contract.application.reviewrun.port;

import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunMaterial;
import java.util.List;
import java.util.UUID;

public interface ContractReviewRunMaterialRepository {
    void insert(ContractReviewRunMaterial material);
    List<ContractReviewRunMaterial> findMaterialsByRunId(UUID runId);
    default void rollbackUncommittedRun(UUID runId) { }
}
