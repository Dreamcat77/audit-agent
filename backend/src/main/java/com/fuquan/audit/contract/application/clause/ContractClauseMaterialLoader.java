package com.fuquan.audit.contract.application.clause;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.port.ContractRepository;
import com.fuquan.audit.contract.application.project.SelectedProjectMaterialReadService;
import com.fuquan.audit.contract.application.project.port.ProjectContractQueryPort;
import com.fuquan.audit.contract.domain.Contract;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
final class ContractClauseMaterialLoader {
    private final ContractRepository contracts;
    private final ProjectContractQueryPort associations;
    private final SelectedProjectMaterialReadService selectedMaterials;

    ContractClauseMaterialLoader(ContractRepository contracts,
                                 ProjectContractQueryPort associations,
                                 SelectedProjectMaterialReadService selectedMaterials) {
        this.contracts = contracts;
        this.associations = associations;
        this.selectedMaterials = selectedMaterials;
    }

    Source load(UUID projectId, UUID contractId, List<UUID> selectedFileIds) {
        Contract contract = contracts.findById(contractId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "contract not found"));
        boolean owned = associations.findByContractId(contractId)
                .filter(association -> association.projectId().equals(projectId)).isPresent();
        if (!owned) {
            throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                    "contract does not belong to project");
        }
        List<ContractClauseMaterial> materials = selectedMaterials.read(projectId, selectedFileIds).stream()
                .map(selected -> new ContractClauseMaterial(selected.file().id(),
                        selected.file().originalFilename() == null ? "项目材料" : selected.file().originalFilename(),
                        selected.file().category(), selected.text()))
                .toList();
        return new Source(contract, materials);
    }

    record Source(Contract contract, List<ContractClauseMaterial> materials) {
    }
}
