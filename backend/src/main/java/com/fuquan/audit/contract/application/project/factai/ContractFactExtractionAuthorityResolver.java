package com.fuquan.audit.contract.application.project.factai;

import java.util.UUID;

public interface ContractFactExtractionAuthorityResolver {
    ContractFactExtractionAuthority resolve(UUID projectId, UUID fileId);
}
