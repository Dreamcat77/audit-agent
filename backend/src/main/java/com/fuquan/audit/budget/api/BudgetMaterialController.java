package com.fuquan.audit.budget.api;

import com.fuquan.audit.budget.application.BudgetMaterialService;
import com.fuquan.audit.budget.domain.BudgetMaterialJob;
import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/budget/material-uploads")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','AUDITOR','FINANCE_REVIEWER')")
public class BudgetMaterialController {
    private final BudgetMaterialService service;private final AuthContext auth;
    private final com.fuquan.audit.budget.application.BudgetMaterialBatchService batches;
    public BudgetMaterialController(BudgetMaterialService service,AuthContext auth,com.fuquan.audit.budget.application.BudgetMaterialBatchService batches) {this.service=service;this.auth=auth;this.batches=batches;}
    public record BatchRequest(UUID batchId,java.util.List<com.fuquan.audit.budget.domain.BudgetMaterialBatch.Member> members,String budgetYear,String departmentCode) {}
    @PostMapping("/batches") public ApiResponse<java.util.Map<String,String>> register(@RequestBody BatchRequest request) {
        var batch=batches.register(request.batchId(),request.members(),request.budgetYear(),request.departmentCode(),user());
        return ApiResponse.success(java.util.Map.of("batchId",batch.id().toString(),"status",batch.status()));
    }
    @GetMapping("/batches")
    public ApiResponse<com.fuquan.audit.budget.application.BudgetMaterialBatchService.BatchHistoryPage> history(
            @RequestParam(required=false) String fileName,
            @RequestParam(required=false) @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate createdFrom,
            @RequestParam(required=false) @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate createdTo,
            @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size) {
        int safePage=Math.max(page,1);
        int safeSize=Math.min(Math.max(size,1),100);
        var filter=com.fuquan.audit.budget.application.port.BudgetHistoryFilter.of(fileName,createdFrom,createdTo);
        return ApiResponse.success(batches.history(user(),filter,safePage,safeSize));
    }
    @PostMapping(consumes="multipart/form-data")
    public ApiResponse<Result> upload(@RequestPart("file") MultipartFile file,@RequestHeader("Idempotency-Key") UUID key,
            @RequestParam(required=false) String budgetYear,@RequestParam(required=false) String departmentCode,@RequestParam(required=false) UUID batchId) throws IOException {
        try(var input=file.getInputStream()) {
            return ApiResponse.success(result(service.upload(file.getOriginalFilename(),file.getContentType(),file.getSize(),input,budgetYear,departmentCode,key.toString(),user(),batchId)));
        }
    }
    @GetMapping("/operations/{key}") public ApiResponse<Result> operation(@PathVariable UUID key) {return ApiResponse.success(service.operation(user(),key.toString()).map(this::result).orElse(null));}
    @GetMapping("/{id}") public ApiResponse<Result> get(@PathVariable UUID id) {return ApiResponse.success(result(service.owned(id,user())));}
    @GetMapping("/batches/{id}")
    public ApiResponse<com.fuquan.audit.budget.application.BudgetMaterialBatchService.BatchView> batch(@PathVariable UUID id) {
        return ApiResponse.success(batches.view(id, user()));
    }
    private CurrentUser user() {return auth.currentUser().orElseThrow(()->new BusinessException(BusinessErrorCode.UNAUTHENTICATED));}
    private Result result(BudgetMaterialJob job) {
        var task=service.task(job.id());
        return new Result(job.id(),task.fileName(),job.status(),java.util.Set.of("MATERIAL_CALL_TIMEOUT","MATERIAL_TIME_LIMIT","MATERIAL_QUEUE_TIMEOUT").contains(java.util.Objects.toString(task.errorDetail(),""))?task.errorDetail():job.status().equals("FAILED")?"BUDGET_PARSE_FAILED":job.status().equals("UNKNOWN")?"BUDGET_OUTCOME_UNKNOWN":null,job.updatedAt(),BudgetController.BudgetReviewTaskResponse.from(task));
    }
    public record Result(UUID id,String fileName,String status,String errorCode,Instant updatedAt,BudgetController.BudgetReviewTaskResponse task) {}
}
