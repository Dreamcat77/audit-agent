package com.fuquan.audit.contract.application.reviewrun.executor;

import com.fuquan.audit.contract.application.clause.ContractClauseMaterial;
import com.fuquan.audit.contract.application.reviewrun.FrozenReviewMaterial;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import java.util.List;
import org.springframework.stereotype.Component;

/** Converts only verified frozen run materials into the existing clause analyzer input. */
@Component
public final class FrozenClauseMaterialAdapter {
    public List<ContractClauseMaterial> adapt(List<FrozenReviewMaterial> frozenMaterials) {
        return frozenMaterials.stream().map(material -> new ContractClauseMaterial(
                material.fileId(), safeLabel(material),
                ProjectFileCategory.valueOf(material.effectiveRole().name()),
                material.canonicalText())).toList();
    }

    private static String safeLabel(FrozenReviewMaterial material) {
        if (material.originalFilename() != null && !material.originalFilename().isBlank()) {
            return material.originalFilename();
        }
        return "frozen-material-" + material.ordinal() + "-" + material.effectiveRole().name();
    }
}
