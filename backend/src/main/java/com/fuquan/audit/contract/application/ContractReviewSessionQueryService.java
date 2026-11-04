package com.fuquan.audit.contract.application;

import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.contract.application.port.ContractReviewSessionQueryPort;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 全局合同内审任务查询应用服务。 */
@Service
public class ContractReviewSessionQueryService {
    private final ContractReviewSessionQueryPort queryPort;
    private final FlowDebugLogger flowDebug;

    public ContractReviewSessionQueryService(
            ContractReviewSessionQueryPort queryPort,
            FlowDebugLogger flowDebug) {
        this.queryPort = queryPort;
        this.flowDebug = flowDebug;
    }

    @Transactional(readOnly = true)
    public ReviewSessionTaskPage list(ReviewSessionTaskQuery query) {
        long started = System.nanoTime();
        long total = -1L;
        int resultCount = -1;
        try {
            total = queryPort.count(query);
            List<ReviewSessionTaskSummary> items = total == 0 ? List.of() : queryPort.findPage(query);
            resultCount = items.size();
            return ReviewSessionTaskPage.of(items, query, total);
        } finally {
            flowDebug.log(
                    "REVIEW_SESSION_TASK_LIST_QUERY",
                    "CONTRACT",
                    "APPLICATION",
                    "listReviewSessionTasks",
                    Map.of(
                            "page", query.page(),
                            "size", query.size(),
                            "statusPresent", query.status() != null,
                            "keywordPresent", query.keyword() != null,
                            "resultCount", resultCount,
                            "total", total,
                            "durationMs", elapsedMillis(started)));
        }
    }

    @Transactional(readOnly = true)
    public Optional<String> creatorDisplayName(UUID sessionId) {
        return queryPort.findCreatorDisplayName(sessionId);
    }

    private long elapsedMillis(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }
}
