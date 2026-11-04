package com.fuquan.audit.procurement.application;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.material.application.MaterialIntakeService;
import com.fuquan.audit.security.domain.CurrentUser;
import org.springframework.stereotype.Service;

/** F10/F11: an explicit save-and-review command uses the project's durable, uploader-owned intake links. */
@Service
public class ProcurementIntakeReviewService {
    private final ProcurementProjectQueryService projects;
    private final MaterialIntakeService intakes;
    private final ProcurementMaterialBatchService batches;

    public ProcurementIntakeReviewService(ProcurementProjectQueryService projects, MaterialIntakeService intakes,
            ProcurementMaterialBatchService batches) {
        this.projects = projects; this.intakes = intakes; this.batches = batches;
    }

    public ProcurementMaterialBatchService.Result analyze(java.util.UUID projectId, CurrentUser user) {
        var project = projects.requireById(projectId);
        var ids = project.sourceMaterialIds();
        if (ids.isEmpty() || ids.size() > 100 || !ids.contains(project.sourceProjectId())
                || new java.util.HashSet<>(ids).size() != ids.size())
            throw new BusinessException(BusinessErrorCode.CONFLICT, "此项目没有完整的已上传材料清单，请在材料页核对");
        // Resolve and authorize the WHOLE scope before creating review material records.
        var records = ids.stream().map(id -> intakes.findOwned(id, user)).toList();
        if (records.stream().anyMatch(r -> !"PARSED".equals(r.status())))
            throw new BusinessException(BusinessErrorCode.CONFLICT, "部分材料尚未完成提取，请等待提取完成后继续");
        var sourceIds = batches.reuseIntakes(projectId, records, user.userId());
        return batches.analyzeSaved(projectId, sourceIds, user.userId(), true);
    }
}
