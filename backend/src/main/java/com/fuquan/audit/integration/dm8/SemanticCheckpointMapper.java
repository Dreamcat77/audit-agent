package com.fuquan.audit.integration.dm8;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SemanticCheckpointMapper {
    SemanticCheckpointPlanRow findPlanByStep(@Param("stepId") String stepId);
    SemanticCheckpointPlanRow lockPlanByStep(@Param("stepId") String stepId);
    Long lockParentAttempt(@Param("attemptId") String attemptId);
    List<SemanticCheckpointUnitRow> findUnits(@Param("planId") String planId);
    int insertPlan(@Param("row") SemanticCheckpointPlanRow row);
    int insertUnit(@Param("row") SemanticCheckpointUnitRow row);
    int updatePlanCas(@Param("expected") SemanticCheckpointPlanRow expected, @Param("row") SemanticCheckpointPlanRow row);
    int updateUnitCas(@Param("expected") SemanticCheckpointUnitRow expected, @Param("row") SemanticCheckpointUnitRow row);
}
