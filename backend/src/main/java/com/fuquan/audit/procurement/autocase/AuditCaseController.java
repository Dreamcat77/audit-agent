package com.fuquan.audit.procurement.autocase;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api/v1/procurement/cases")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','AUDITOR','PROCUREMENT_USER','BUDGET_USER','CONTRACT_USER')")
@ConditionalOnProperty(name="audit.procurement.auto-case.enabled",havingValue="true")
public class AuditCaseController {
    private final AuditCaseService service;private final AuthContext auth;
    private CaseFindingAcknowledgments acknowledgments;
    @org.springframework.beans.factory.annotation.Autowired
    public void setAcknowledgments(CaseFindingAcknowledgments value){this.acknowledgments=value;}
    public AuditCaseController(AuditCaseService service,AuthContext auth){this.service=service;this.auth=auth;}
    @ExceptionHandler(org.springframework.dao.ConcurrencyFailureException.class)
    public ResponseEntity<ApiResponse<Void>> changingSnapshot() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.failure(409,"案件状态正在更新，请重试读取；原案件和上传操作已保留"));
    }
    @PostMapping(consumes="multipart/form-data")
    public ApiResponse<View> upload(@RequestHeader("Idempotency-Key") UUID key,@RequestPart("files") List<MultipartFile> files) throws IOException {
        return ApiResponse.success(view(service.upload(key,files.stream().map(f->new AuditCaseService.Upload(f.getOriginalFilename(),f::getInputStream)).toList(),user())));
    }
    @GetMapping public ApiResponse<List<View>> recent(){return ApiResponse.success(service.recent(user()).stream().map(AuditCaseController::view).toList());}
    @GetMapping("/history") public ApiResponse<AuditCaseService.HistoryPage> history(@RequestParam(required=false) UUID before,@RequestParam(defaultValue="20") int size,
            @RequestParam(required=false) String keyword,@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to){
        return ApiResponse.success(service.history(before,size,keyword,from,to,user()));
    }
    @GetMapping("/operations/{key}") public ApiResponse<View> operation(@PathVariable UUID key){return ApiResponse.success(service.operation(key,user()).map(AuditCaseController::view).orElse(null));}
    @GetMapping("/{id}") public ApiResponse<View> get(@PathVariable UUID id){return ApiResponse.success(view(service.owned(id,user())));}
    @PostMapping("/{id}/cancel") public ApiResponse<View> cancel(@PathVariable UUID id){return ApiResponse.success(view(service.cancel(id,user())));}
    @GetMapping("/{id}/acknowledgments") public ApiResponse<List<String>> acknowledgments(@PathVariable UUID id){return ApiResponse.success(acknowledgments.list(id,user()));}
    @PostMapping("/{id}/acknowledgments/{key}") public ApiResponse<Void> confirm(@PathVariable UUID id,@PathVariable String key){acknowledgments.confirm(id,key,user());return ApiResponse.success(null);}
    @GetMapping(value="/{id}/report.html",produces="text/html;charset=UTF-8")
    public ResponseEntity<String> report(@PathVariable UUID id){return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"procurement-report.html\"")
            .header(HttpHeaders.CACHE_CONTROL,"no-store").body(CaseReportHtml.render(service.owned(id,user())));}
    @GetMapping("/{id}/files/{fileId}/evidence")
    public ApiResponse<EvidenceView> evidence(@PathVariable UUID id,@PathVariable UUID fileId){
        var doc=service.owned(id,user()).documents().stream().filter(d->d.id().equals(fileId)).findFirst().orElseThrow(()->new BusinessException(BusinessErrorCode.NOT_FOUND));
        return ApiResponse.success(new EvidenceView(doc.name(),doc.sha256(),doc.segments(),doc.facts()));
    }
    @GetMapping("/{id}/files/{fileId}/original")
    public ResponseEntity<InputStreamResource> original(@PathVariable UUID id,@PathVariable UUID fileId){
        var actor=user();
        var document=service.owned(id,actor).documents().stream().filter(d->d.id().equals(fileId)).findFirst()
                .orElseThrow(()->new BusinessException(BusinessErrorCode.NOT_FOUND));
        var content=service.open(id,fileId,actor);
        String name=document.name(),extension=name.substring(name.lastIndexOf('.')+1).toLowerCase(java.util.Locale.ROOT);
        MediaType media=switch(extension){case "pdf"->MediaType.APPLICATION_PDF;case "png"->MediaType.IMAGE_PNG;
            case "jpg","jpeg"->MediaType.IMAGE_JPEG;default->MediaType.APPLICATION_OCTET_STREAM;};
        var disposition=media.equals(MediaType.APPLICATION_OCTET_STREAM)?org.springframework.http.ContentDisposition.attachment():org.springframework.http.ContentDisposition.inline();
        return ResponseEntity.ok().contentType(media).contentLength(content.size())
                .header(HttpHeaders.CONTENT_DISPOSITION,disposition.filename(name,java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options","nosniff")
                .header(HttpHeaders.CACHE_CONTROL,"no-store").body(new InputStreamResource(content.inputStream()));
    }
    private CurrentUser user(){return auth.currentUser().orElseThrow(()->new BusinessException(BusinessErrorCode.UNAUTHENTICATED));}
    record DocumentView(UUID id,String name,String status,String parseMethod,String errorCode,int factCount,int segmentCount,int textChars,int extractionCalls,int reviewInputChars) {}
    record View(UUID id,String caseNumber,String projectName,String status,Instant createdAt,List<DocumentView> documents,AuditCase.Report report,Map<String,String> findingIds,List<CaseAvailableResults.Detail> materialDetails) {}
    record EvidenceView(String fileName,String sha256,List<com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Segment> segments,List<AuditCase.Fact> facts) {}
    static View view(AuditCase c){
        var ids=new LinkedHashMap<String,String>();
        if(c.report()!=null)c.report().reviews().values().stream().flatMap(r->r.findings().stream()).forEach(f->ids.put(f.title()+"\n"+f.explanation(),CaseFindingAcknowledgments.identity(f)));
        var coverage=CaseDocumentCoverage.of(c.documents(),c.report());
        return new View(c.id(),"AC-"+c.id(),CaseFactPolicy.projectName(c),c.cancelled()?"CANCELLED":c.status(),c.createdAt(),c.documents().stream().map(d->new DocumentView(d.id(),d.name(),d.status(),d.parseMethod(),d.errorCode(),coverage.get(d.id()).factCount(),coverage.get(d.id()).segmentCount(),coverage.get(d.id()).textChars(),coverage.get(d.id()).extractionCalls(),coverage.get(d.id()).reviewInputChars())).toList(),CaseReviewAssurance.project(c.report()),ids,CaseAvailableResults.details(c));
    }
}
