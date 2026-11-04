package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import java.util.List;

/** Verified run input; never a view over mutable latest ProjectFile/Contract state. */
public record FrozenReviewInput(ContractReviewRun run, Contract frozenContract,
        List<FrozenReviewMaterial> materials) {
    public FrozenReviewInput { materials = List.copyOf(materials); }
}
