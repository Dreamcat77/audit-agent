package com.fuquan.audit.review.application.port;

import com.fuquan.audit.review.domain.AuditTask;
import java.util.List;

/** Read-only paging seam for durable audit-task projections. */
public interface AuditTaskReadQueryPort {

    Page findLatest(String sourceType, String reviewType, int page, int size);

    List<AuditTask> findAllLatest(String sourceType, String reviewType);

    /** Opt-in history; unlike findLatest, retains every durable task for the same source. */
    Page findAllPage(String sourceType, String reviewType, int page, int size);

    List<AuditTask> findAll(String sourceType, String reviewType);

    record Page(List<AuditTask> items, int page, int size, long total) {
        public Page {
            items = List.copyOf(items);
            if (page < 1 || size < 1 || total < 0) {
                throw new IllegalArgumentException("invalid audit task page");
            }
        }
    }
}
