package com.fuquan.audit.budget.application.ai;

import java.util.*;

/** Budget-owned semantic protocol. Arithmetic and exact source locations stay in Java. */
public interface BudgetBatchReviewPort {
    record Source(String id,UUID taskId,String fileName,String locator,String text) {}
    record Request(UUID batchId,String inputDigest,String scope,List<Source> evidence,List<String> checks) {}
    record Item(String code,String level,String basis,String title,String explanation,String suggestion,List<String> evidenceIds,Map<String,String> excerpts) {}
    record Result(boolean complete,List<Item> items) {}
    Result analyze(Request request);
}
