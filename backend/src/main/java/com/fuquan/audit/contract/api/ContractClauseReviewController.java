package com.fuquan.audit.contract.api;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.clause.BreachClauseReview;
import com.fuquan.audit.contract.application.clause.BreachClauseReviewUseCase;
import com.fuquan.audit.contract.application.clause.ContractTermClauseReview;
import com.fuquan.audit.contract.application.clause.ContractTermClauseReviewUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/contracts/{contractId}/clause-reviews")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','AUDITOR','LEGAL','PROCUREMENT_USER','BUDGET_USER','CONTRACT_USER')")
public class ContractClauseReviewController {
    private final BreachClauseReviewUseCase breachReview;
    private final ContractTermClauseReviewUseCase termReview;

    public ContractClauseReviewController(BreachClauseReviewUseCase breachReview,
                                          ContractTermClauseReviewUseCase termReview) {
        this.breachReview = breachReview;
        this.termReview = termReview;
    }

    @PostMapping("/breach")
    public ApiResponse<BreachClauseReview> breach(@PathVariable UUID projectId,
                                                   @PathVariable UUID contractId,
                                                   @Valid @RequestBody ClauseReviewRequest body) {
        return ApiResponse.success(breachReview.review(projectId, contractId, body.fileIds()));
    }

    @PostMapping("/term-termination")
    public ApiResponse<ContractTermClauseReview> termTermination(@PathVariable UUID projectId,
                                                                  @PathVariable UUID contractId,
                                                                  @Valid @RequestBody ClauseReviewRequest body) {
        return ApiResponse.success(termReview.review(projectId, contractId, body.fileIds()));
    }

    public record ClauseReviewRequest(@NotEmpty @Size(max = 50) List<@NotNull UUID> fileIds) {
        public ClauseReviewRequest {
            if (fileIds != null) {
                Set<UUID> unique = new HashSet<>();
                for (UUID fileId : fileIds) {
                    if (fileId != null && !unique.add(fileId)) {
                        throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT,
                                "fileIds must contain unique entries");
                    }
                }
                if (fileIds.stream().noneMatch(java.util.Objects::isNull)) fileIds = List.copyOf(fileIds);
            }
        }
    }
}
