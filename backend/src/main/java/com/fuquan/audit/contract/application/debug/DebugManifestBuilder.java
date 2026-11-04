package com.fuquan.audit.contract.application.debug;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fuquan.audit.contract.application.reviewrun.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

/** Positive projection: neither a domain serializer nor a free-form log/exception exporter. */
@Component
public class DebugManifestBuilder {
    public static final String POLICY = "internal-metadata-v1";
    public static final int MAX_BYTES = 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final Set<String> IDS = Set.of("runId","stepId","attemptId","fileId","parseResultId","roleDecisionId","identityDecisionId");
    private static final Set<String> NUMBERS = Set.of("versionNo","parseVersion","attemptNo","materialCount");
    private static final Set<String> HASHES = Set.of("sha256","inputSnapshotIdentity","canonicalTextSha256");
    private static final Set<String> STATUSES = Set.of("PENDING","RUNNING","COMPLETED","PARTIALLY_COMPLETED","FAILED","UNKNOWN");
    private final DebugHashPolicy hashes;
    public DebugManifestBuilder() { this(DebugHashPolicy.internal()); }
    public DebugManifestBuilder(DebugHashPolicy hashes) { this.hashes = Objects.requireNonNull(hashes); }

    public String build(UUID id, DebugScope scope, Instant at, String actorId, ContractReviewRunCreateResult source) {
        var run = source.run();
        if (!run.runId().equals(scope.runId()) || !run.projectId().equals(scope.projectId())
                || !run.contractId().equals(scope.contractId())) throw new IllegalArgumentException("debug scope mismatch");
        List<Map<String,Object>> entries = new ArrayList<>();
        Map<String,Object> metadata = new TreeMap<>();
        metadata.put("runId",run.runId().toString()); metadata.put("status",run.status().name());
        metadata.put("versionNo",run.versionNo()); metadata.put("materialCount",source.materials().size());
        hash(metadata,"inputSnapshotIdentity",run.inputSnapshotIdentity());
        entries.add(entry("RUN",metadata));
        source.materials().stream().sorted(Comparator.comparing(m -> m.fileId().toString())).forEach(m -> {
            Map<String,Object> item = new TreeMap<>(); item.put("fileId",m.fileId().toString());
            item.put("parseResultId",m.parseResultId().toString()); item.put("parseVersion",m.parseVersion());
            hash(item,"sha256",m.fileSha256()); hash(item,"canonicalTextSha256",m.canonicalTextSha256());
            if (m.roleDecisionId()!=null) item.put("roleDecisionId",m.roleDecisionId().toString());
            item.put("identityDecisionId",m.identityDecisionId().toString()); entries.add(entry("MATERIAL",item));
        });
        source.steps().stream().filter(s -> scope.stepId()==null || s.stepId().equals(scope.stepId()))
                .sorted(Comparator.comparing(s -> s.stepId().toString())).forEach(s ->
                    entries.add(entry("STEP",Map.of("stepId",s.stepId().toString(),"status",s.status().name(),"versionNo",s.versionNo()))));
        source.attempts().stream().filter(a -> (scope.stepId()==null || a.stepId().equals(scope.stepId()))
                && (scope.attemptId()==null || a.attemptId().equals(scope.attemptId())))
                .sorted(Comparator.comparing(a -> a.attemptId().toString())).forEach(a ->
                    entries.add(entry("ATTEMPT",Map.of("attemptId",a.attemptId().toString(),"stepId",a.stepId().toString(),
                            "attemptNo",a.attemptNo(),"status",a.status().name(),"versionNo",a.versionNo()))));
        Map<String,Object> manifest = new TreeMap<>();
        manifest.put("bundleId",id.toString()); manifest.put("bundleSchemaVersion",1);
        manifest.put("sanitizationPolicyVersion",POLICY); manifest.put("contentLevel","METADATA_ONLY");
        manifest.put("scope",scope.metadata()); manifest.put("generatedAt",at.toString());
        manifest.put("generatedBy",ContractSnapshotCanonicalizer.sha256("debug-actor:"+actorId));
        manifest.put("applicationBuild", "contract-closeout-debug-schema-1");
        manifest.put("entries",entries); manifest.put("verificationLabels",List.of());
        manifest.put("exclusions",List.of("ORIGINALS","DOCUMENT_TEXT","MODEL_PAYLOADS","CREDENTIALS","PII","PATHS","STACKS"));
        manifest.put("artifact",Map.of("mediaType","application/json","digestScope","MANIFEST_WITHOUT_ARTIFACT",
                "sha256",ContractSnapshotCanonicalizer.sha256(canonical(manifest))));
        String json = canonical(manifest);
        if (json.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES) throw new IllegalArgumentException("debug manifest exceeds bound");
        return json;
    }
    private void hash(Map<String,Object> target,String name,String hash) {
        hashes.exportHash(hash).ifPresent(value -> target.put(name,value));
    }
    private static Map<String,Object> entry(String type,Map<String,Object> metadata) {
        validateMetadata(metadata);
        return Map.of("type",type,"schemaVersion",1,"safeMetadata",metadata,
                "entrySha256",ContractSnapshotCanonicalizer.sha256(canonical(metadata)));
    }
    public static void validateMetadata(Map<String,?> metadata) {
        for(var item:metadata.entrySet()) {
            String key=item.getKey(); Object value=item.getValue(); boolean valid;
            if(IDS.contains(key)) valid=value instanceof String s && s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
            else if(NUMBERS.contains(key)) valid=(value instanceof Long || value instanceof Integer) && ((Number)value).longValue()>=0;
            else if(HASHES.contains(key)) valid=value instanceof String s && s.matches("[0-9a-f]{64}");
            else valid=key.equals("status") && STATUSES.contains(value);
            if(!valid) throw new IllegalArgumentException("debug metadata rejected");
        }
    }
    public static String canonical(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch(JsonProcessingException e) { throw new IllegalArgumentException("invalid debug manifest"); }
    }
}
