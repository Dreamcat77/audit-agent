package com.fuquan.audit.procurement.autocase;

import java.util.*;
import static com.fuquan.audit.procurement.autocase.AuditCase.*;

/** Case IDs are never substituted for formal Project IDs. Evidence is server-owned. */
public interface CaseReviewAiPort {
    Result analyze(Request request);
    record Request(UUID caseId,String module,String inputDigest,String version,List<ReviewScope> documents,
            List<SourceEvidence> evidence,List<InputFact> facts,List<ReviewCheck> checks,List<RuleSignal> ruleSignals,
            List<UploadedDocument> uploadedDocuments) {}
    record UploadedDocument(UUID documentId,String fileName,String role,String status,boolean readable) {}
    record InputFact(UUID documentId,String row,String type,String value,List<String> evidenceIds,String subjectScope,String amountScope) {
        public InputFact(UUID documentId,String row,String type,String value,List<String> evidenceIds){this(documentId,row,type,value,evidenceIds,null,null);}
    }
    record RuleSignal(String code,String title,String evidenceId,String explanation) {}
    record Candidate(String code,String level,String title,String explanation,String suggestion,List<String> evidenceIds,String basis) {
        public Candidate(String code,String level,String title,String explanation,String suggestion,List<String> evidenceIds){
            this(code,level,title,explanation,suggestion,evidenceIds,"CLAUSE_ISSUE");
        }
    }
    record Observation(String code,String explanation,List<String> evidenceIds) {}
    record Result(String model,String version,boolean complete,List<Candidate> findings,List<Observation> observations) {}
}
