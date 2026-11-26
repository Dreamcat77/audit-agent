package com.fuquan.audit.contract.application.analysis;

import java.util.*;

/** Independent contract protocol. Java retains evidence authority and exact money arithmetic. */
public interface ContractAnalysisAiPort {
    record Document(UUID id,String text,String name,String roleHint,String projectName,String header,String section) {
        public Document(UUID id,String text){this(id,text,null,null,null,null,null);}
    }
    record Request(UUID caseId,String inputDigest,String scope,List<Document> documents,Map<UUID,String> reviewParties) {
        public Request(UUID caseId,String inputDigest,String scope,List<Document> documents){
            this(caseId,inputDigest,scope,documents,Map.of());
        }
    }
    record Citation(UUID document,String text) {}
    record Fact(String type,String value,List<Citation> citations,String subjectScope) {
        public Fact(String type,String value,List<Citation> citations){this(type,value,citations,null);}
    }
    record Clause(String topic,String summary,List<Citation> citations,String subjectScope) {
        public Clause(String topic,String summary,List<Citation> citations){this(topic,summary,citations,null);}
    }
    record Finding(String code,String level,String title,String explanation,String suggestion,List<Citation> citations,String basis,String subjectScope) {
        public Finding(String code,String level,String title,String explanation,String suggestion,List<Citation> citations,String basis) {
            this(code,level,title,explanation,suggestion,citations,basis,null);
        }
        public Finding(String code,String level,String title,String explanation,String suggestion,List<Citation> citations) {
            this(code,level,title,explanation,suggestion,citations,"CLAUSE_ISSUE",null);
        }
    }
    record Result(String model,String version,String role,String profile,boolean complete,List<Fact> facts,List<Clause> clauses,List<Finding> findings,int rejectedItems) {
        public Result(String model,String version,String role,String profile,boolean complete,List<Fact> facts,List<Clause> clauses,List<Finding> findings) {
            this(model,version,role,profile,complete,facts,clauses,findings,0);
        }
    }
    Result analyze(Request request);
}
