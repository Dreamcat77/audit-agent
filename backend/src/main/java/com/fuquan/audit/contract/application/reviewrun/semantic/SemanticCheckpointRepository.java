package com.fuquan.audit.contract.application.reviewrun.semantic;

import static com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes.*;
import java.util.*;

/** DM8 is the sole durable execution ledger; locks live only within short transactions. */
public interface SemanticCheckpointRepository {
    Optional<Plan> findPlanByStep(UUID stepId);
    Optional<Plan> lockPlanByStep(UUID stepId);
    void lockParentAttempt(UUID attemptId);
    List<Unit> findUnits(UUID planId);
    void insertPlan(Plan plan);
    void insertUnit(Unit unit);
    void compareAndSetPlan(Plan expected, Plan next);
    void compareAndSetUnit(Unit expected, Unit next);
}
