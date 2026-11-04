package com.fuquan.audit.contract.application.project.port;

import com.fuquan.audit.contract.domain.Contract;

public interface ContractCreationPort {
    void insertCompleteSnapshot(Contract contract);
}
