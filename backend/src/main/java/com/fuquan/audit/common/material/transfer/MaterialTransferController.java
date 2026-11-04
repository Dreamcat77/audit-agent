package com.fuquan.audit.common.material.transfer;

import static com.fuquan.audit.common.material.transfer.MaterialTransfer.*;
import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import com.fuquan.audit.budget.application.BudgetMaterialService;
import com.fuquan.audit.budget.api.BudgetMaterialController;
import com.fuquan.audit.budget.api.BudgetController;
import com.fuquan.audit.common.material.application.MaterialIntakeService;
import com.fuquan.audit.procurement.api.ProcurementMaterialController;
import com.fuquan.audit.procurement.api.ProcurementMaterialBatchController;
import com.fuquan.audit.procurement.application.ProcurementMaterialBatchService;
import com.fuquan.audit.procurement.application.ProcurementProjectQueryService;
import java.io.IOException;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api/v1/material-transfers/{kind}/{operation}")
public class MaterialTransferController {
    private final MaterialTransferService transfers;private final AuthContext auth;private final BudgetMaterialService budget;
    private final MaterialIntakeService procurement;private final ProcurementMaterialBatchService project;private final ProcurementProjectQueryService projects;
    public MaterialTransferController(MaterialTransferService transfers,AuthContext auth,BudgetMaterialService budget,MaterialIntakeService procurement,ProcurementMaterialBatchService project,ProcurementProjectQueryService projects){this.transfers=transfers;this.auth=auth;this.budget=budget;this.procurement=procurement;this.project=project;this.projects=projects;}
    @PostMapping public ApiResponse<Progress> create(@PathVariable String kind,@PathVariable UUID operation,@RequestBody Spec spec){var user=user();if(kind.equals("project")&&spec!=null&&spec.projectId()!=null)projects.requireById(spec.projectId());var s=transfers.create(kind,operation,spec,user);return ApiResponse.success(transfers.progress(s));}
    @GetMapping public ApiResponse<Progress> progress(@PathVariable String kind,@PathVariable UUID operation){return ApiResponse.success(transfers.progress(transfers.owned(kind,operation,user())));}
    @PutMapping(value="/parts/{index}",consumes="multipart/form-data") public ApiResponse<Progress> part(@PathVariable String kind,@PathVariable UUID operation,@PathVariable int index,@RequestPart("file") MultipartFile file)throws IOException{try(var input=file.getInputStream()){return ApiResponse.success(transfers.part(kind,operation,index,input,user()));}}
    @PostMapping("/complete") public ApiResponse<Object> complete(@PathVariable String kind,@PathVariable UUID operation)throws IOException {
        var user=user();return ApiResponse.success(transfers.complete(kind,operation,user,(s,input)-> {
            var spec=s.spec();
            if(kind.equals("budget")) {
                var job=budget.upload(spec.fileName(),spec.contentType(),spec.size(),input,spec.budgetYear(),spec.departmentCode(),operation.toString(),user,spec.batchId());
                return new BudgetMaterialController.Result(job.id(),spec.fileName(),job.status(),null,job.updatedAt(),BudgetController.BudgetReviewTaskResponse.from(budget.task(job.id())));
            }
            if(kind.equals("procurement"))return ProcurementMaterialController.Result.from(procurement.uploadAsync(spec.fileName(),input,operation.toString(),user));
            boolean canQuote=user.roles().stream().anyMatch(Set.of("SYSTEM_ADMIN","PROCUREMENT_MANAGER","AUDITOR")::contains);
            var result=project.stage(spec.projectId(),List.of(new ProcurementMaterialBatchService.Upload(spec.fileName(),spec.contentType(),spec.size(),()->input)),user.userId(),canQuote);
            // A stage response may contain a recoverable per-file error. Do not make it a final upload outcome.
            if(result.files().stream().anyMatch(f->f.fileName().equals(spec.fileName())&&f.status().equals("INCOMPLETE")))throw new BusinessException(BusinessErrorCode.CONFLICT,"此材料保存状态待核对，请继续原上传操作");
            return ProcurementMaterialBatchController.Response.from(result);
        }));
    }
    private CurrentUser user(){return auth.currentUser().orElseThrow(()->new BusinessException(BusinessErrorCode.UNAUTHENTICATED));}
}
