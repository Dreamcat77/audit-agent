package com.fuquan.audit.procurement.application;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.procurement.application.port.ProcurementSourceProjectPort;
import com.fuquan.audit.procurement.domain.ProcurementSourceProject;
import com.fuquan.audit.security.application.AuthContext;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 查询并校验可进入采购审查的上游项目。 */
@Service
public class ProcurementSourceProjectService {

    private final ProcurementSourceProjectPort projects;
    private final AuthContext auth;

    public ProcurementSourceProjectService(ProcurementSourceProjectPort projects, AuthContext auth) {
        this.projects = projects;
        this.auth = auth;
    }

    @Transactional(readOnly = true)
    public List<ProcurementSourceProject> findEligible(String keyword, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        return projects.findEligible(actorId(), keyword == null ? null : keyword.trim(), safeLimit);
    }

    @Transactional(readOnly = true)
    public ProcurementSourceProject requireEligible(UUID projectId) {
        return projects.findEligibleById(actorId(), projectId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                        "所选采购材料尚未解析完成，请上传采购材料或继续处理已有材料"));
    }
    private String actorId() {
        return auth.currentUser().orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED)).userId();
    }
}
