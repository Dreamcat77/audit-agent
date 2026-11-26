package com.fuquan.audit.contract.api.project;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.contract.application.project.factai.ContractFactExtractionReadService;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/files/{fileId}/contract-fact-extractions")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','AUDITOR','LEGAL','PROCUREMENT_USER','BUDGET_USER','CONTRACT_USER')")
public class ContractFactExtractionController {
    private final ContractFactExtractionReadService reads;
    public ContractFactExtractionController(ContractFactExtractionReadService reads) { this.reads = reads; }

    @GetMapping("/latest")
    public ApiResponse<ContractFactExtractionReadService.Projection> latest(
            @PathVariable UUID projectId, @PathVariable UUID fileId) {
        return ApiResponse.success(reads.latest(projectId, fileId));
    }
}
