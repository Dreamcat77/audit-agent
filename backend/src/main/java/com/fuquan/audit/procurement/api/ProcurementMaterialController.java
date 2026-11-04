package com.fuquan.audit.procurement.api;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.common.material.application.MaterialIntakeService;
import com.fuquan.audit.common.material.domain.MaterialIntakeFields;
import com.fuquan.audit.common.material.domain.MaterialIntake;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/procurement/materials")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','PROCUREMENT_APPLICANT','AUDITOR')")
public class ProcurementMaterialController {
    private final MaterialIntakeService service;
    private final AuthContext auth;
    public ProcurementMaterialController(MaterialIntakeService service, AuthContext auth) { this.service = service; this.auth = auth; }
    @PostMapping(consumes = "multipart/form-data")
    public ApiResponse<Result> upload(@RequestPart("file") MultipartFile file,
            @RequestHeader("Idempotency-Key") UUID key) throws IOException {
        if (file.isEmpty()) throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "请选择采购材料");
        try (var input = file.getInputStream()) {
            return ApiResponse.success(Result.from(service.uploadAsync(file.getOriginalFilename(), input, key.toString(), user())));
        }
    }
    @GetMapping public ApiResponse<List<Result>> recent() {
        return ApiResponse.success(service.recent(user()).stream().map(Result::from).toList());
    }
    @GetMapping("/operations/{key}") public ApiResponse<Result> operation(@PathVariable UUID key) {
        return ApiResponse.success(service.operation(user(), key.toString()).map(Result::from).orElse(null));
    }
    @GetMapping("/{id}") public ApiResponse<Result> get(@PathVariable UUID id) {
        return ApiResponse.success(Result.from(service.findOwned(id, user())));
    }
    @PostMapping("/{id}/retry") public ApiResponse<Result> retry(@PathVariable UUID id) {
        return ApiResponse.success(Result.from(service.retryAsync(id, user())));
    }
    private CurrentUser user() { return auth.currentUser().orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED)); }
    public record Result(UUID id, String fileName, String status, String parseMethod, MaterialIntakeFields fields,
            String errorCode, Instant createdAt, Instant updatedAt, Instant leaseUntil) {
        public static Result from(MaterialIntake r) {
            return new Result(r.id(), r.fileName(), r.status(), r.parseMethod(), r.fields(), r.errorCode()==null?null:r.errorCode().replace("_CLEANED",""), r.createdAt(), r.updatedAt(), r.leaseUntil());
        }
    }
}
