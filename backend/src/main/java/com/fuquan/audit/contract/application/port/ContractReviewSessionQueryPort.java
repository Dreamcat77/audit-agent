package com.fuquan.audit.contract.application.port;

import com.fuquan.audit.contract.application.ReviewSessionTaskQuery;
import com.fuquan.audit.contract.application.ReviewSessionTaskSummary;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** ReviewSession 全局任务轻量读模型端口。 */
public interface ContractReviewSessionQueryPort {
    List<ReviewSessionTaskSummary> findPage(ReviewSessionTaskQuery query);

    long count(ReviewSessionTaskQuery query);

    Optional<String> findCreatorDisplayName(UUID sessionId);
}
