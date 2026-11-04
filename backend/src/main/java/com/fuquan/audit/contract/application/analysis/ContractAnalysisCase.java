package com.fuquan.audit.contract.application.analysis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** An independent, owner-scoped analysis. Project is only the existing intake container. */
public record ContractAnalysisCase(UUID id, UUID projectId, String owner, String inputDigest,
        State status, long version, String leaseToken, Instant leaseUntil, int attempts,
        Instant createdAt, Instant deadline, List<Source> sources, List<Step> steps, String reasonCode, int expectedUploads) {
    public enum State { QUEUED, RUNNING, COMPLETED, PARTIAL, DATA_INSUFFICIENT, FAILED, UNKNOWN }
    public enum StepState { QUEUED, RUNNING, COMPLETED, PARTIAL, DATA_INSUFFICIENT, FAILED, UNKNOWN }
    public enum SourceState { WAITING, READY, FAILED, UNKNOWN }
    public ContractAnalysisCase {
        Objects.requireNonNull(id); Objects.requireNonNull(projectId); Objects.requireNonNull(createdAt);
        Objects.requireNonNull(deadline); Objects.requireNonNull(status);
        if (owner == null || owner.isBlank() || inputDigest == null || !inputDigest.matches("[a-f0-9]{64}")
                || version < 0 || attempts < 0 || !deadline.isAfter(createdAt)) throw new IllegalArgumentException("invalid case identity");
        sources = List.copyOf(sources); steps = List.copyOf(steps);
        if (expectedUploads<1 || expectedUploads>200 || sources.size() > 200 || sources.stream().map(Source::fileId).distinct().count() != sources.size()
                || steps.stream().map(Step::id).distinct().count() != steps.size()) throw new IllegalArgumentException("invalid case manifest");
        if ((leaseToken == null) != (leaseUntil == null) || (leaseToken != null && leaseToken.isBlank()))
            throw new IllegalArgumentException("lease identity incomplete");
        if ((status == State.RUNNING) != (leaseToken != null)) throw new IllegalArgumentException("running cases require a lease");
    }
    public ContractAnalysisCase(UUID id,UUID projectId,String owner,String inputDigest,State status,long version,String leaseToken,Instant leaseUntil,int attempts,
        Instant createdAt,Instant deadline,List<Source> sources,List<Step> steps,String reasonCode){
        this(id,projectId,owner,inputDigest,status,version,leaseToken,leaseUntil,attempts,createdAt,deadline,sources,steps,reasonCode,sources.size());
    }
    public static UUID identity(String owner, UUID projectId) {
        return UUID.nameUUIDFromBytes(("contract-analysis-v1/" + owner + "/" + projectId).getBytes(StandardCharsets.UTF_8));
    }
    public static void verifyTransition(ContractAnalysisCase previous,ContractAnalysisCase next){
        if(previous.terminal()||!previous.id.equals(next.id)||!previous.projectId.equals(next.projectId)||!previous.owner.equals(next.owner)
            ||!previous.inputDigest.equals(next.inputDigest)||!previous.createdAt.equals(next.createdAt)||!previous.deadline.equals(next.deadline)
            ||previous.expectedUploads!=next.expectedUploads||next.version!=previous.version+1)throw new IllegalArgumentException("CASE_IDENTITY_OR_TERMINAL_MUTATION");
        if(!previous.sources.isEmpty()){
            if(previous.sources.size()!=next.sources.size())throw new IllegalArgumentException("SEALED_MANIFEST_MUTATION");
            for(int i=0;i<previous.sources.size();i++){
                var p=previous.sources.get(i);var n=next.sources.get(i);
                if(!p.fileId.equals(n.fileId)||!p.sha256.equals(n.sha256)||p.size!=n.size||!p.name.equals(n.name)
                    ||p.parseResultId!=null&&(!p.parseResultId.equals(n.parseResultId)||!Objects.equals(p.parseVersion,n.parseVersion)||!Objects.equals(p.textSha256,n.textSha256)))
                    throw new IllegalArgumentException("FROZEN_SOURCE_MUTATION");
            }
        }
        for(var p:previous.steps){var n=next.steps.stream().filter(s->s.id.equals(p.id)).findFirst().orElseThrow(()->new IllegalArgumentException("STEP_REMOVAL"));
            if(!Objects.equals(p.fileId,n.fileId)||!p.kind.equals(n.kind)||p.start!=n.start||p.end!=n.end||!p.inputDigest.equals(n.inputDigest)
                ||p.status!=StepState.QUEUED&&p.status!=StepState.RUNNING&&!p.equals(n))throw new IllegalArgumentException("FROZEN_STEP_MUTATION");
        }
    }
    public boolean terminal() { return status != State.QUEUED && status != State.RUNNING; }
    public boolean claimable(Instant now) { return status == State.QUEUED || status == State.RUNNING && !leaseUntil.isAfter(now); }
    public ContractAnalysisCase claim(String token, Instant now) {
        if (!claimable(now)) throw new IllegalStateException("CASE_NOT_CLAIMABLE");
        return new ContractAnalysisCase(id,projectId,owner,inputDigest,State.RUNNING,version+1,token,
                now.plusSeconds(240),attempts+1,createdAt,deadline,sources,steps,reasonCode,expectedUploads);
    }
    public ContractAnalysisCase checkpoint(List<Source> files, List<Step> work) {
        return new ContractAnalysisCase(id,projectId,owner,inputDigest,status,version+1,leaseToken,leaseUntil,
                attempts,createdAt,deadline,files,work,reasonCode,expectedUploads);
    }
    public ContractAnalysisCase release(State state, List<Source> files, List<Step> work, String reason) {
        if (state == State.RUNNING) throw new IllegalArgumentException("release must clear running state");
        return new ContractAnalysisCase(id,projectId,owner,inputDigest,state,version+1,null,null,attempts,
                createdAt,deadline,files,work,reason,expectedUploads);
    }
    public record Source(UUID fileId, String name, String sha256, long size, SourceState status,
            UUID parseResultId, Integer parseVersion, String textSha256, String reasonCode) {
        public Source {
            Objects.requireNonNull(fileId); Objects.requireNonNull(status);
            if (name == null || name.isBlank() || name.length()>255 || sha256 == null || !sha256.matches("[a-f0-9]{64}") || size<0)
                throw new IllegalArgumentException("invalid source identity");
            if (status == SourceState.READY && (parseResultId==null || parseVersion==null || parseVersion<1
                    || textSha256==null || !textSha256.matches("[a-f0-9]{64}"))) throw new IllegalArgumentException("missing immutable parse identity");
        }
    }
    public record Evidence(UUID fileId, String fileSha256, UUID parseResultId, int parseVersion,
            String textSha256, int start, int end, String excerpt) {
        public Evidence {
            Objects.requireNonNull(fileId); Objects.requireNonNull(parseResultId);
            if (fileSha256==null || !fileSha256.matches("[a-f0-9]{64}") || textSha256==null || !textSha256.matches("[a-f0-9]{64}")
                    || parseVersion<1 || start<0 || end<=start || excerpt==null || excerpt.length()!=end-start || excerpt.length()>4000)
                throw new IllegalArgumentException("invalid evidence tuple");
        }
    }
    public record Fact(String type, String value, String method, List<Evidence> evidence) {
        public Fact { evidence=List.copyOf(evidence); if(type==null||value==null||value.isBlank()||evidence.isEmpty())throw new IllegalArgumentException("ungrounded fact"); }
    }
    public record Clause(String topic, String summary, List<Evidence> evidence) {
        public Clause { evidence=List.copyOf(evidence); if(topic==null||summary==null||summary.isBlank()||evidence.isEmpty())throw new IllegalArgumentException("ungrounded clause"); }
    }
    public record Finding(String code, String level, String title, String explanation, String suggestion,
            List<Evidence> evidence, String origin) {
        public Finding { evidence=List.copyOf(evidence); if(code==null||title==null||explanation==null||evidence.isEmpty())throw new IllegalArgumentException("ungrounded finding"); }
    }
    public record Check(String code, String status, String explanation, List<Evidence> evidence) {
        public Check { evidence=List.copyOf(evidence); if(!Set.of("PASS","FAIL","NOT_EVALUATED").contains(status))throw new IllegalArgumentException("invalid check state"); }
    }
    /** No full document text here; only validated evidence snippets and derived analysis. */
    public record Output(String role, String profile, boolean complete, List<Fact> facts,
            List<Clause> clauses, List<Check> checks, List<Finding> findings, int rejectedCitations,
            String model, String version) {
        public Output { facts=List.copyOf(facts); clauses=List.copyOf(clauses); checks=List.copyOf(checks); findings=List.copyOf(findings); }
        public boolean useful() { return !facts.isEmpty() || !clauses.isEmpty() || checks.stream().anyMatch(c->!c.status().equals("NOT_EVALUATED")) || !findings.isEmpty(); }
    }
    public record Step(String id, UUID fileId, String kind, int start, int end, String inputDigest,
            StepState status, Instant startedAt, Instant completedAt, Output output, String reasonCode) {
        public Step {
            if(id==null||kind==null||start<0||end<start||inputDigest==null||!inputDigest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("invalid step identity");
            Objects.requireNonNull(status);
            if(status==StepState.RUNNING && startedAt==null)throw new IllegalArgumentException("running step needs start time");
            if((status==StepState.COMPLETED || status==StepState.PARTIAL) && output==null)throw new IllegalArgumentException("published step needs output");
        }
        public Step running(Instant now) { if(status!=StepState.QUEUED)throw new IllegalStateException("STEP_NOT_QUEUED");return new Step(id,fileId,kind,start,end,inputDigest,StepState.RUNNING,now,null,null,null); }
        public Step finish(StepState state, Output value, String reason, Instant now) {return new Step(id,fileId,kind,start,end,inputDigest,state,startedAt,now,value,reason);}
    }
    public static String sha256(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
