package com.fuquan.audit.contract.infra.memory;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.domain.Contract;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
public class LocalContractStore {
    private final Map<UUID, Contract> contracts = new HashMap<>();

    public LocalContractStore() {
        LocalContractFixtures.contracts().forEach(contract -> contracts.put(contract.id(), contract));
    }

    synchronized Optional<Contract> findById(UUID id) {
        return Optional.ofNullable(contracts.get(id));
    }

    synchronized void insert(Contract contract) {
        boolean duplicateNo = contracts.values().stream()
                .anyMatch(existing -> existing.contractNo().equals(contract.contractNo()));
        if (contracts.containsKey(contract.id()) || duplicateNo) {
            throw new BusinessException(BusinessErrorCode.CONFLICT, "contract number conflict");
        }
        contracts.put(contract.id(), contract);
    }
}
