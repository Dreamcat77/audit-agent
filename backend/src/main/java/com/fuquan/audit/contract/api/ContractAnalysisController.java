package com.fuquan.audit.contract.api;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.contract.application.analysis.*;
import com.fuquan.audit.contract.application.project.ProjectApplicationService;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/contract-analysis-cases")
@ConditionalOnProperty(name="audit.contract.analysis.enabled",havingValue="true")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','AUDITOR','LEGAL','PROCUREMENT_USER','BUDGET_USER','CONTRACT_USER')")
public class ContractAnalysisController {
    private final ContractAnalysisService service;private final AuthContext auth;private final ContractAnalysisReport reports;private final ContractAnalysisIntake intake;private final ProjectApplicationService projects;
    public ContractAnalysisController(ContractAnalysisService service,AuthContext auth,ContractAnalysisReport reports,ContractAnalysisIntake intake,ProjectApplicationService projects){this.service=service;this.auth=auth;this.reports=reports;this.intake=intake;this.projects=projects;}
    public record Start(int fileCount) {}
    @PostMapping("/intakes")
    public ApiResponse<ContractAnalysisIntake.Receipt> start(@RequestHeader("Idempotency-Key") String key,@RequestBody Start body,jakarta.servlet.http.HttpServletRequest request){
        return ApiResponse.success(intake.start(key,body.fileCount(),actor(),org.slf4j.MDC.get("requestId"),org.slf4j.MDC.get("traceId"),request.getRemoteAddr()));
    }
    @PostMapping("/from-project/{projectId}")
    public ApiResponse<ContractAnalysisCase> create(@PathVariable UUID projectId){return ApiResponse.success(service.admit(projectId,actor()));}
    @GetMapping("/from-project/{projectId}")
    public ApiResponse<ContractAnalysisCase> forProject(@PathVariable UUID projectId){return ApiResponse.success(service.forProject(projectId,actor()));}
    @GetMapping("/{id}")
    public ApiResponse<ContractAnalysisCase> get(@PathVariable UUID id){return ApiResponse.success(service.owned(id,actor()));}
    @GetMapping
    public ApiResponse<List<ContractAnalysisReport.Summary>> list(@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size,
            @RequestParam(required=false) String projectName,
            @RequestParam(required=false) String createdDate,
            @RequestParam(defaultValue="0") int timezoneOffsetMinutes){
        DateRange range=parseDate(createdDate,timezoneOffsetMinutes);
        return ApiResponse.success(service.recent(actor(),page,size,projectName,range.from(),range.to()).stream()
                .map(value -> reports.summary(value,projects.get(value.projectId()).name())).toList());
    }
    @GetMapping("/{id}/report")
    public ApiResponse<ContractAnalysisReport.Report> report(@PathVariable UUID id){return ApiResponse.success(reports.report(id,actor()));}
    @GetMapping("/{id}/sources/{fileId}/text")
    public ApiResponse<ContractAnalysisReport.Text> text(@PathVariable UUID id,@PathVariable UUID fileId,@RequestParam(defaultValue="0") int start,@RequestParam(defaultValue="20000") int size){return ApiResponse.success(reports.text(id,fileId,start,size,actor()));}
    private CurrentUser actor(){return auth.currentUser().orElseThrow(()->new com.fuquan.audit.common.exception.BusinessException(com.fuquan.audit.common.exception.BusinessErrorCode.UNAUTHENTICATED));}
    private static DateRange parseDate(String value,int timezoneOffsetMinutes){
        if(value==null||value.isBlank())return new DateRange(null,null);
        if(Math.abs(timezoneOffsetMinutes)>14*60)throw new com.fuquan.audit.common.exception.BusinessException(com.fuquan.audit.common.exception.BusinessErrorCode.INVALID_ARGUMENT,"invalid timezone offset");
        try{var date=LocalDate.parse(value);var zone=ZoneOffset.ofTotalSeconds(-timezoneOffsetMinutes*60);return new DateRange(date.atStartOfDay(zone).toInstant(),date.plusDays(1).atStartOfDay(zone).toInstant());}
        catch(DateTimeException invalid){throw new com.fuquan.audit.common.exception.BusinessException(com.fuquan.audit.common.exception.BusinessErrorCode.INVALID_ARGUMENT,"createdDate must be yyyy-MM-dd");}
    }
    private record DateRange(Instant from,Instant to){}
}
