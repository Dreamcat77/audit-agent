package com.fuquan.audit.budget.application.port;

import com.fuquan.audit.budget.domain.ProcurementDemand;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 采购需求数据访问 Port。
 */
public interface ProcurementDemandRepository {

    Optional<ProcurementDemand> findById(UUID id);

    /** Test seam; production adapters override this with one batch query. */
    default List<ProcurementDemand> findAllByIds(List<UUID> ids) {
        return ids.stream().map(this::findById).flatMap(Optional::stream).toList();
    }
}
