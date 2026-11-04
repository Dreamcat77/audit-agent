package com.fuquan.audit.procurement.application.port;

import com.fuquan.audit.procurement.domain.ProcurementQuoteCheck;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** F06 询比价分析结果持久化 Port。 */
public interface ProcurementQuoteCheckRepository {

    void save(ProcurementQuoteCheck check);

    Optional<ProcurementQuoteCheck> findByBatchId(String batchId);

    default Optional<ProcurementQuoteCheck> findById(UUID id) { return Optional.empty(); }

    /**
     * Returns only the latest F06 batch for each project, so a re-upload cannot inflate split-purchase totals.
     */
    default List<SupplierQuoteTimeline> findLatestSupplierQuotesBySubject(
            String subjectCode, Instant fiscalYearStart, Instant fiscalYearEnd) {
        return List.of();
    }

    record SupplierQuoteTimeline(UUID projectId, String projectCode, String projectName,
                                 String supplierName, BigDecimal quoteAmount, Instant quoteTime) {
    }
}
