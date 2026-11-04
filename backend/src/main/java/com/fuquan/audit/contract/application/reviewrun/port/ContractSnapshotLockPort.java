package com.fuquan.audit.contract.application.reviewrun.port;

import com.fuquan.audit.contract.domain.Contract;
import java.util.Optional;
import java.util.UUID;

/** Phase 2A-only authority lock; leaves the established F02 ContractRepository read port narrow. */
public interface ContractSnapshotLockPort {
    Optional<Contract> findByIdForUpdate(UUID id);
}
