package com.fuquan.audit.contract.infra.memory;

import com.fuquan.audit.contract.application.project.port.ContractCreationPort;
import com.fuquan.audit.contract.domain.Contract;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryContractCreationAdapter implements ContractCreationPort {
    private final LocalContractStore store;

    public InMemoryContractCreationAdapter(LocalContractStore store) {
        this.store = store;
    }

    @Override
    public void insertCompleteSnapshot(Contract contract) {
        store.insert(contract);
    }
}
