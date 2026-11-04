package com.fuquan.audit.procurement.application;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.procurement.application.port.ProcurementProjectQueryPort;
import com.fuquan.audit.procurement.domain.ProcurementProject;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 采购项目列表与详情查询用例。 */
@Service
public class ProcurementProjectQueryService {

    private final ProcurementProjectQueryPort queryPort;

    public ProcurementProjectQueryService(ProcurementProjectQueryPort queryPort) {
        this.queryPort = queryPort;
    }

    public ProcurementProjectQueryPort.Page findPage(
            String status, String keyword, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return queryPort.findPage(status, keyword, (safePage - 1) * safeSize, safeSize);
    }

    public ProcurementProject requireById(UUID id) {
        return queryPort.findById(id)
                .orElseThrow(() -> new BusinessException(
                        BusinessErrorCode.NOT_FOUND, "采购项目不存在: " + id));
    }
}
