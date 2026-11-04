package com.fuquan.audit.procurement.application.port;

import com.fuquan.audit.procurement.domain.ProcurementSourceProject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Procurement-specific material sources; never contract Project Intake records. */
public interface ProcurementSourceProjectPort {

    List<ProcurementSourceProject> findEligible(String actorId, String keyword, int limit);

    Optional<ProcurementSourceProject> findEligibleById(String actorId, UUID projectId);
}
