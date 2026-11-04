package com.fuquan.audit.contract.application.reviewrun.semantic;

import static com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes.*;
import java.util.List;

/** Optional local maintenance observation AFTER commit, never a scheduler or result store. */
@FunctionalInterface
public interface SemanticCheckpointBoundary {
    SemanticCheckpointBoundary NONE = (plan, units) -> { };
    void afterCommit(Plan plan, List<Unit> committedUnits);
}
