package com.fuquan.audit.contract.application.analysis;

import java.util.*;

/** Independent contract protocol. Java retains evidence authority and exact money arithmetic. */
public interface ContractAnalysisAiPort {
    record Document(UUID id,String text) {}
    record Request(UUID caseId,String inputDigest,String scope,List<Document> documents,Map<UUID,String> reviewParties) {
        public Request(UUID caseId,String inputDigest,String scope,List<Document> documents){
            this(caseId,inputDigest,scope,documents,Map.of());
        }
    }
    record Citation(UUID document,String text) {}
    record Fact(String type,String value,List<Citation> citations) {}
    record Clause(String topic,String summary,List<Citation> citations) {}
    record Finding(String code,String level,String title,String explanation,String suggestion,List<Citation> citations,String basis) {
        public Finding(String code,String level,String title,String explanation,String suggestion,List<Citation> citations) {
            this(code,level,title,explanation,suggestion,citations,"CLAUSE_ISSUE");
        }
    }
    record Result(String model,String version,String role,String profile,boolean complete,List<Fact> facts,List<Clause> clauses,List<Finding> findings,int rejectedItems) {
        public Result(String model,String version,String role,String profile,boolean complete,List<Fact> facts,List<Clause> clauses,List<Finding> findings) {
            this(model,version,role,profile,complete,facts,clauses,findings,0);
        }
    }
    Result analyze(Request request);
}
