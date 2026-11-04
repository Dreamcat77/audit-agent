package com.fuquan.audit.contract.application.reviewrun.port;

import com.fuquan.audit.contract.domain.project.ProjectContract;
import java.util.Optional;
import java.util.UUID;

/** Locks a ProjectContract association when creating a frozen run, independent of Phase1 queries. */
public interface ProjectContractSnapshotLockPort {
    Optional<ProjectContract> findByContractIdForUpdate(UUID contractId);
}
