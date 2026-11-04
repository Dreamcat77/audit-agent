package com.fuquan.audit.procurement.application.port;

import com.fuquan.audit.procurement.domain.ProcurementProject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 采购项目查询 Port，不向应用层暴露 DM8/MyBatis 类型。 */
public interface ProcurementProjectQueryPort {

    Page findPage(String status, String keyword, int offset, int limit);

    Optional<ProcurementProject> findById(UUID id);

    record Page(List<ProcurementProject> items, long total) {
        public Page {
            items = List.copyOf(items);
        }
    }
}
