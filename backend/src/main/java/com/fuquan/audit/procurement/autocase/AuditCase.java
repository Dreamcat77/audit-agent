package com.fuquan.audit.procurement.autocase;

import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort;
import com.fuquan.audit.procurement.domain.ProcurementQuoteCheck;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** An upload workspace, independent of formal Project, ReviewSession and AuditTask. */
public record AuditCase(UUID id, String owner, UUID operationKey, String fingerprint, String status,
        long version, String leaseToken, Instant leaseUntil, int attempts, Instant createdAt,
        List<Document> documents, Report report) {
    public AuditCase { documents=List.copyOf(documents); }
    public AuditCase update(String state, List<Document> files, Report result) {
        return new AuditCase(id,owner,operationKey,fingerprint,state,version+1,leaseToken,leaseUntil,attempts,createdAt,files,result);
    }
    public AuditCase claim(String token, Instant until) {
        return new AuditCase(id,owner,operationKey,fingerprint,"RUNNING",version+1,token,until,attempts+1,createdAt,documents,report);
    }
    public record Document(UUID id,String name,long size,String sha256,String storageKey,String status,
            List<MaterialFieldAiPort.Segment> segments,List<Fact> facts,String parseMethod,String errorCode,int calls,
            com.fuquan.audit.common.material.application.port.DocumentTextPort.PdfCheckpoint readCheckpoint) {
        public Document(UUID id,String name,long size,String sha256,String storageKey,String status,
                List<MaterialFieldAiPort.Segment> segments,List<Fact> facts,String parseMethod,String errorCode,int calls) {
            this(id,name,size,sha256,storageKey,status,segments,facts,parseMethod,errorCode,calls,null);
        }
        public Document { segments=List.copyOf(segments);facts=List.copyOf(facts); }
        public Document state(String state,String error) {return new Document(id,name,size,sha256,storageKey,state,segments,facts,parseMethod,error,calls,readCheckpoint);}
        public Document reading(com.fuquan.audit.common.material.application.port.DocumentTextPort.PdfCheckpoint checkpoint) {
            if(!sha256.equals(checkpoint.sourceSha256()))throw new IllegalArgumentException("read checkpoint source mismatch");
            return new Document(id,name,size,sha256,storageKey,"PARSING",segments,facts,parseMethod,null,calls,checkpoint);
        }
        public Document parsed(List<MaterialFieldAiPort.Segment> text,String method) {
            return new Document(id,name,size,sha256,storageKey,"PARSED",text,List.of(),method,null,0);
        }
        public Document extracted(List<Fact> values,int count) {
            return new Document(id,name,size,sha256,storageKey,"EXTRACTED",segments,values,parseMethod,null,count);
        }
        public Document extractedWindow(List<Fact> values,int nextWindow) {
            return new Document(id,name,size,sha256,storageKey,"PARSED",segments,values,
                    parseMethod.contains("FIELD_WINDOWS_V1")?parseMethod:parseMethod+"|FIELD_WINDOWS_V1",null,nextWindow);
        }
    }
    public record Fact(String row,String type,String value,String dataType,String locator,String excerpt,
            String verification,String model,String extractionVersion,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String subjectScope,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String amountScope) {
        public Fact(String row,String type,String value,String dataType,String locator,String excerpt,String verification,String model,String extractionVersion){
            this(row,type,value,dataType,locator,excerpt,verification,model,extractionVersion,null,null);
        }
    }
    public record Line(String item,BigDecimal quantity,BigDecimal unitPrice,BigDecimal amount,String locator) {}
    public record Offer(String supplier,BigDecimal amount,UUID documentId,List<Line> lines,String lineValidation) {
        public Offer {lines=List.copyOf(lines);}
    }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(value={"quoteComparisons","ceilingComparisons"},allowGetters=true)
    public record Report(String quoteStatus,BigDecimal budget,BigDecimal awardAmount,List<Offer> offers,
            ProcurementQuoteCheck calculation,List<String> notices,Map<String,String> modules,Map<String,ModuleReview> reviews,String projectName,BigDecimal ceilingAmount) {
        public Report { offers=List.copyOf(offers);notices=List.copyOf(notices);modules=Map.copyOf(modules);
            reviews=reviews==null?Map.of():Map.copyOf(reviews); }
        public Report(String quoteStatus,BigDecimal budget,BigDecimal awardAmount,List<Offer> offers,
                ProcurementQuoteCheck calculation,List<String> notices,Map<String,String> modules,Map<String,ModuleReview> reviews,String projectName) {
            this(quoteStatus,budget,awardAmount,offers,calculation,notices,modules,reviews,projectName,null);
        }
        public Report(String quoteStatus,BigDecimal budget,BigDecimal awardAmount,List<Offer> offers,
                ProcurementQuoteCheck calculation,List<String> notices,Map<String,String> modules,Map<String,ModuleReview> reviews) {
            this(quoteStatus,budget,awardAmount,offers,calculation,notices,modules,reviews,null);
        }
        public Report(String quoteStatus,BigDecimal budget,BigDecimal awardAmount,List<Offer> offers,
                ProcurementQuoteCheck calculation,List<String> notices,Map<String,String> modules) {
            this(quoteStatus,budget,awardAmount,offers,calculation,notices,modules,Map.of(),null);
        }
        public Report review(String module,ModuleReview result) {
            var states=new TreeMap<>(modules);states.put(module,result.status());
            var details=new TreeMap<>(reviews);details.put(module,result);
            return new Report(quoteStatus,budget,awardAmount,offers,calculation,notices,states,details,projectName,ceilingAmount);
        }
        public List<String> blockedSuppliers() {
            var review=reviews.get("BLACKLIST");
            if(review==null)return List.of();
            return offers.stream().map(Offer::supplier).filter(name->review.checks().stream()
                    .anyMatch(c->c.status().equals("BLOCKED")&&c.explanation().startsWith(name+"命中"))).distinct().toList();
        }
        public record QuoteComparison(UUID documentId,String status,BigDecimal difference) {}
        @com.fasterxml.jackson.annotation.JsonProperty("quoteComparisons")
        public List<QuoteComparison> quoteComparisons() {
            return offers.stream().map(o->new QuoteComparison(o.documentId(),budget==null?"NOT_EVALUATED":o.amount().compareTo(budget)>0?"OVER_BUDGET":"WITHIN_BUDGET",
                    budget==null?null:o.amount().subtract(budget))).toList();
        }
        @com.fasterxml.jackson.annotation.JsonProperty("ceilingComparisons")
        public List<QuoteComparison> ceilingComparisons() {
            return offers.stream().map(o->new QuoteComparison(o.documentId(),ceilingAmount==null?"NOT_EVALUATED":o.amount().compareTo(ceilingAmount)>0?"OVER_CEILING":"WITHIN_CEILING",
                    ceilingAmount==null?null:o.amount().subtract(ceilingAmount))).toList();
        }
    }
    public record SourceEvidence(String id,UUID documentId,String fileName,String sha256,String locator,String excerpt) {}
    public record ReviewFinding(String code,String level,String title,String explanation,String suggestion,
            List<SourceEvidence> evidence,String origin) {
        public ReviewFinding {evidence=List.copyOf(evidence);}
    }
    public record ReviewCheck(String code,String status,String explanation,List<SourceEvidence> evidence) {
        public ReviewCheck {evidence=List.copyOf(evidence);}
    }
    public record ReviewScope(UUID documentId,String fileName,String role,int sourceChars,int suppliedChars) {}
    public record ModuleReview(String status,String inputDigest,String version,String model,Instant startedAt,Instant completedAt,
            List<ReviewScope> scope,List<String> coverageGaps,List<ReviewCheck> checks,List<ReviewFinding> findings,
            int rejectedCitations,String errorCode) {
        public ModuleReview {scope=List.copyOf(scope);coverageGaps=List.copyOf(coverageGaps);checks=List.copyOf(checks);findings=List.copyOf(findings);}
    }
}
