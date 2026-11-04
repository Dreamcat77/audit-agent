package com.fuquan.audit.contract.api.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.common.web.RequestIdFilter;
import com.fuquan.audit.contract.application.debug.*;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @Validated
@RequestMapping("/api/v1/projects/{projectId}/contracts/{contractId}/review-runs/{runId}/debug-bundles")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','AUDITOR')")
public class SanitizedDebugBundleController {
    private final SanitizedDebugBundleService service;
    public SanitizedDebugBundleController(SanitizedDebugBundleService service) { this.service=service; }
    @PostMapping
    public ResponseEntity<ApiResponse<BundleView>> generate(@PathVariable UUID projectId,@PathVariable UUID contractId,@PathVariable UUID runId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp="^[A-Za-z0-9._:-]{1,128}$") String key,
            @RequestBody JsonNode body,HttpServletRequest request) {
        try {
            if(body==null || !body.isObject()) throw invalid();
            var fields=body.fieldNames();
            while(fields.hasNext()) if(!Set.of("contentLevel","stepId","attemptId").contains(fields.next())) throw invalid();
            DebugContentLevel level=body.has("contentLevel")?DebugContentLevel.valueOf(body.get("contentLevel").textValue()):DebugContentLevel.METADATA_ONLY;
            var scope=new DebugScope(projectId,contractId,runId,optionalId(body,"stepId"),optionalId(body,"attemptId"));
            return ResponseEntity.accepted().body(ApiResponse.success(BundleView.from(service.generate(scope,level,key,metadata(request)))));
        } catch(IllegalArgumentException | NullPointerException malformed) { throw invalid(); }
    }
    @GetMapping
    public ApiResponse<List<BundleView>> list(@PathVariable UUID projectId,@PathVariable UUID contractId,@PathVariable UUID runId,HttpServletRequest request) {
        return ApiResponse.success(service.list(scope(projectId,contractId,runId),metadata(request)).stream().map(BundleView::from).toList());
    }
    @GetMapping("/{bundleId}")
    public ApiResponse<BundleView> get(@PathVariable UUID projectId,@PathVariable UUID contractId,@PathVariable UUID runId,
            @PathVariable UUID bundleId,HttpServletRequest request) {
        return ApiResponse.success(BundleView.from(service.get(scope(projectId,contractId,runId),bundleId,metadata(request))));
    }
    @GetMapping("/{bundleId}/artifact")
    public ResponseEntity<byte[]> download(@PathVariable UUID projectId,@PathVariable UUID contractId,@PathVariable UUID runId,
            @PathVariable UUID bundleId,HttpServletRequest request) {
        byte[] bytes=service.download(scope(projectId,contractId,runId),bundleId,metadata(request));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CACHE_CONTROL,"no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"sanitized-debug.json\"")
                .contentLength(bytes.length).body(bytes);
    }
    public record BundleView(UUID bundleId,DebugScope scope,DebugContentLevel contentLevel,SanitizedDebugBundle.Status status,
            int schemaVersion,String sanitizationPolicyVersion,String artifactSha256,long artifactSize,Instant requestedAt,long versionNo) {
        static BundleView from(SanitizedDebugBundle b) {
            return new BundleView(b.bundleId(),b.scope(),b.contentLevel(),b.status(),1,DebugManifestBuilder.POLICY,
                    b.artifactSha256(),b.artifactSize(),b.requestedAt(),b.versionNo());
        }
    }
    private static UUID optionalId(JsonNode body,String key) {
        if(!body.has(key)) return null;
        if(!body.get(key).isTextual()) throw invalid();
        String value=body.get(key).textValue();UUID parsed=UUID.fromString(value);
        if(!parsed.toString().equalsIgnoreCase(value)) throw invalid();return parsed;
    }
    private static DebugScope scope(UUID p,UUID c,UUID r) { return new DebugScope(p,c,r,null,null); }
    private static BusinessException invalid() { return new BusinessException(BusinessErrorCode.INVALID_ARGUMENT,"invalid debug bundle request"); }
    private static ProjectRequestMetadata metadata(HttpServletRequest request) {
        String id=MDC.get(RequestIdFilter.MDC_KEY),trace=MDC.get("traceId");
        return new ProjectRequestMetadata(id,trace==null?id:trace,request.getRemoteAddr());
    }
}
