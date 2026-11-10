package com.fuquan.audit.integration.ai.fastapi;

import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort;
import com.fuquan.audit.common.material.application.port.MaterialUnitAiPort;
import com.fuquan.audit.common.material.application.port.MaterialExtractionTerminated;
import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.common.observability.PipelineDiagnostics;
import com.fuquan.audit.common.web.RequestIdFilter;
import org.slf4j.MDC;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@Profile({"local-dev & local-ai", "dev29", "customer"})
public class FastApiMaterialFieldAdapter implements MaterialFieldAiPort, MaterialUnitAiPort {
    private final RestClient client;
    // Budget and procurement share one fair admission queue, before any call claim.
    private final Semaphore materialCapacity = new Semaphore(1,true);
    private final ThreadLocal<Boolean> admitted=ThreadLocal.withInitial(()->false);
    @Override public Admission admit() {
        if(admitted.get())throw new IllegalStateException("nested material admission");
        long start=System.nanoTime();
        try {
            if(!materialCapacity.tryAcquire(1800,TimeUnit.SECONDS))throw new MaterialExtractionTerminated("MATERIAL_QUEUE_TIMEOUT");
            admitted.set(true);
            org.slf4j.LoggerFactory.getLogger(getClass()).info("MATERIAL_MODEL_ADMITTED queueMillis={} concurrency=1",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
            return ()->{admitted.remove();materialCapacity.release();};
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new ExternalServiceException(ExternalServiceType.AI,e);}
    }
    public FastApiMaterialFieldAdapter(@Qualifier("aiRestClient") RestClient client) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(5));
        factory.setReadTimeout(java.time.Duration.ofSeconds(965));
        this.client = client.mutate().requestFactory(factory).build();
    }
    @Override public Result extract(String kind, String id, String sha, List<Segment> segments) {
        try(var slot=admit()){return extractLegacy(kind,id,sha,segments,null);}
    }
    @Override public Result extractWithContext(String kind,String id,String sha,List<Segment> segments,Context context) {
        try(var slot=admit()){return extractLegacy(kind,id,sha,segments,context);}
    }
    private Result extractLegacy(String kind, String id, String sha, List<Segment> segments,Context context) {
        segments = List.copyOf(segments);
        try (var span = PipelineDiagnostics.start("BUDGET".equals(kind)?"BUDGET":"PROCUREMENT", "AI_EXTRACTION", PipelineDiagnostics.resourceId(id),
                Map.of("inputChars", segments.stream().mapToLong(s -> s.text().length()).sum(),
                        "segmentCount", segments.size(), "connectTimeoutMs", 5000, "readTimeoutMs", 965000))) {
          try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(965);
            Envelope envelope = null;
            // Capture identity and source once so transport recovery cannot change them.
            var body = new LinkedHashMap<String,Object>();
            body.put("businessType",kind);body.put("materialId",id);body.put("sha256",sha);body.put("segments",segments);
            if(context!=null)body.put("context",context);
            for (int attempt = 1; attempt <= 2; attempt++) {
                var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
                factory.setConnectTimeout(java.time.Duration.ofSeconds(5));
                factory.setReadTimeout(java.time.Duration.ofMillis(Math.max(1,
                        TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))));
                var request = client.mutate().requestFactory(factory).build().post()
                        .uri("/ai/v1/materials/extract-fields").header("Connection", "close");
                String requestId = MDC.get(RequestIdFilter.MDC_KEY);
                if (requestId != null && requestId.matches("[A-Za-z0-9._-]{1,128}"))
                    request.header(RequestIdFilter.HEADER_NAME, requestId);
                try {
                    envelope = request.body(body).retrieve().body(Envelope.class);
                    break;
                } catch (RestClientException failure) {
                    boolean socketFailure = false;
                    for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
                        if (cause instanceof java.net.SocketException) socketFailure = true;
                    }
                    if ("PROCUREMENT_AUTO".equals(kind) || attempt == 2 || !socketFailure || Thread.currentThread().isInterrupted()
                            || System.nanoTime() >= deadline) throw failure;
                    // This endpoint extracts candidates without committing business facts.
                    // Retry the same immutable source once; never retry HTTP/schema errors.
                    try (var retry = PipelineDiagnostics.start("BUDGET".equals(kind)?"BUDGET":"PROCUREMENT",
                            "AI_CONNECTION_RECOVERY", PipelineDiagnostics.resourceId(id), Map.of("attempt", 2))) {
                        retry.outcome("RETRY_SAME_SOURCE", Map.of("reason", "SOCKET_FAILURE"));
                    }
                }
            }
            if (envelope == null || envelope.code() != 200 || envelope.data() == null)
                throw new IllegalStateException("invalid material extraction response");
            var result = envelope.data();
            span.success(Map.of("rowCount", result.rows().size(), "factCount", result.rows().stream().mapToInt(r -> r.facts().size()).sum()));
            return result;
          } catch (RuntimeException e) { span.fail(e); throw new ExternalServiceException(ExternalServiceType.AI, e); }
        }
    }
    private record Envelope(int code, Result data) {}

    @Override public String policy() {
        var factory=new org.springframework.http.client.SimpleClientHttpRequestFactory();
        // This read-only preflight can queue behind native OCR work on the shared
        // service. Keep a finite wait without retrying a model operation or
        // reusing a cached policy that could hide policy drift.
        factory.setConnectTimeout(java.time.Duration.ofSeconds(5));factory.setReadTimeout(java.time.Duration.ofSeconds(120));
        var value=client.mutate().requestFactory(factory).build().get().uri("/ai/v1/materials/policy").retrieve().body(Policy.class);
        if(value==null||value.policyId()==null||!value.policyId().matches("[a-f0-9]{64}"))throw new IllegalStateException("invalid material policy response");
        return value.policyId();
    }
    @Override public Reply extractUnit(String kind,String id,String sha,List<Segment> segments,String policy,int callLimit,long timeoutMillis) {
        Admission slot=admitted.get()?null:admit();
        long started=System.nanoTime();
        try {
            long remaining=timeoutMillis-TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
            if(remaining<1)throw new MaterialExtractionTerminated("MATERIAL_TIME_LIMIT");
            var factory=new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(java.time.Duration.ofMillis(Math.min(5000,remaining)));
            factory.setReadTimeout(java.time.Duration.ofMillis(remaining));
            var request=client.mutate().requestFactory(factory).build().post().uri("/ai/v1/materials/extract-fields").header("Connection","close");
            String requestId=MDC.get(RequestIdFilter.MDC_KEY);
            if(requestId!=null&&requestId.matches("[A-Za-z0-9._-]{1,128}"))request.header(RequestIdFilter.HEADER_NAME,requestId);
            // No transport retry: the DM8 ledger must resolve the outcome first.
            var envelope=request.body(Map.of("businessType",kind,"materialId",id,"sha256",sha,"segments",List.copyOf(segments),
                "policyId",policy,"callLimit",callLimit,"timeLimitMillis",remaining)).retrieve().body(UnitEnvelope.class);
            if(envelope==null||envelope.code()!=200||envelope.data()==null||envelope.data().callCount()==null)
                throw new IllegalStateException("invalid acknowledged extraction unit");
            var data=envelope.data();
            return new Reply(new Result(data.rows(),data.model(),data.promptVersion(),data.issues()),data.callCount());
        } catch(org.springframework.web.client.RestClientResponseException rejected) {
            // Only the service's narrow terminal codes end local processing. Never log response bodies.
            try {
                var error=new com.fasterxml.jackson.databind.ObjectMapper().readTree(rejected.getResponseBodyAsByteArray());
                String code=error.path("code").asText();
                if(Set.of("MATERIAL_AI_CALL_TIMEOUT","MATERIAL_AI_TIME_LIMIT").contains(code))
                    throw new MaterialExtractionTerminated(code.equals("MATERIAL_AI_TIME_LIMIT")?"MATERIAL_TIME_LIMIT":"MATERIAL_CALL_TIMEOUT");
            } catch(java.io.IOException invalid) { /* Unknown service reply remains fail closed. */ }
            throw rejected;
        } catch(org.springframework.web.client.ResourceAccessException transport) {
            for(Throwable cause=transport;cause!=null;cause=cause.getCause())
                if(cause instanceof java.net.SocketTimeoutException)throw new MaterialExtractionTerminated("MATERIAL_CALL_TIMEOUT");
            throw transport;
        } finally {if(slot!=null)slot.close();}
    }
    private record Policy(String policyId) {}
    private record UnitData(List<Row> rows,String model,String promptVersion,Integer callCount,List<ExtractionIssue> issues) {}
    private record UnitEnvelope(int code,UnitData data) {}
}
