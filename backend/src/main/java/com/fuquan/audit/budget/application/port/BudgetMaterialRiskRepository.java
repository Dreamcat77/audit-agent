package com.fuquan.audit.budget.application.port;
import com.fuquan.audit.budget.application.BudgetMaterialRisk.Result;
import com.fuquan.audit.budget.application.BudgetMaterialRisk.Source;
import com.fuquan.audit.budget.application.BudgetMaterialRisk.Position;
import java.util.*;
public interface BudgetMaterialRiskRepository {
    Optional<Result> find(UUID id,String actor);
    void insert(Result result);
    List<Result> history(String actor,int offset,int limit);
    List<Source> sources(String actor,Position position,String keyword,int limit);
    List<Result> searchHistory(String actor,String keyword,java.time.Instant from,java.time.Instant before,int offset,int limit);
    long countHistory(String actor,String keyword,java.time.Instant from,java.time.Instant before);
}
