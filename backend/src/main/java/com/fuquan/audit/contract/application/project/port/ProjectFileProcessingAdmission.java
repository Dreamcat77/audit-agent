package com.fuquan.audit.contract.application.project.port;

import com.fuquan.audit.security.domain.CurrentUser;
import java.util.UUID;

/** Optional local/mock scheduling seam; durable formal workers rediscover jobs from DM8. */
public interface ProjectFileProcessingAdmission {
    void accepted(UUID jobId, CurrentUser actor);
}
