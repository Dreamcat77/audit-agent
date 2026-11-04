package com.fuquan.audit.contract.application;

import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.contract.application.port.ContractQueryPort;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 合同目录分页查询应用服务。 */
@Service
public class ContractQueryService {
    private final ContractQueryPort queryPort;
    private final FlowDebugLogger flowDebug;

    public ContractQueryService(ContractQueryPort queryPort, FlowDebugLogger flowDebug) {
        this.queryPort = queryPort;
        this.flowDebug = flowDebug;
    }

    @Transactional(readOnly = true)
    public ContractPage list(ContractListQuery query) {
        long started = System.nanoTime();
        long total = -1L;
        int resultCount = -1;
        try {
            total = queryPort.count(query);
            List<ContractSummary> items = total == 0 ? List.of() : queryPort.findPage(query);
            resultCount = items.size();
            return ContractPage.of(items, query, total);
        } finally {
            flowDebug.log(
                    "CONTRACT_LIST_QUERY",
                    "CONTRACT",
                    "APPLICATION",
                    "listContracts",
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

    private long elapsedMillis(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }
}
