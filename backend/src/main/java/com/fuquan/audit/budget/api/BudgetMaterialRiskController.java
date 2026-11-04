package com.fuquan.audit.budget.api;
import com.fuquan.audit.budget.application.*;
import com.fuquan.audit.common.api.ApiResponse;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/budget/material-risk")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','AUDITOR','FINANCE_REVIEWER')")
public class BudgetMaterialRiskController {
    private final BudgetMaterialRiskService service;
    public BudgetMaterialRiskController(BudgetMaterialRiskService service){this.service=service;}
    @GetMapping("/candidates") public ApiResponse<BudgetMaterialRisk.CandidatePage> candidates(@RequestParam(defaultValue="1") int page,@RequestParam(required=false) List<UUID> taskIds){return ApiResponse.success(service.candidatePage(page,taskIds));}
    @GetMapping("/browse") public ApiResponse<BudgetMaterialRisk.BrowsePage> browse(@RequestParam(required=false) String cursor,@RequestParam(required=false) String keyword){return ApiResponse.success(service.browse(cursor,keyword));}
    @GetMapping("/history") public ApiResponse<BudgetMaterialRisk.HistoryPage> historySearch(@RequestParam(defaultValue="1") int page,@RequestParam(required=false) String keyword,@RequestParam(required=false) @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate date){return ApiResponse.success(service.searchHistory(page,keyword,date));}
    public record Request(UUID id,List<BudgetMaterialRisk.Selection> selections) {}
    @PostMapping("/analyses") public ApiResponse<BudgetMaterialRisk.Result> analyze(@RequestBody Request request){
        try{return ApiResponse.success(service.analyze(request.id(),request.selections()));}
        catch(org.springframework.dao.DuplicateKeyException concurrent){
            // The failed insert transaction has rolled back before this authenticated replay.
            try{return ApiResponse.success(service.analyze(request.id(),request.selections()));}
            catch(org.springframework.dao.DuplicateKeyException occupied){throw new com.fuquan.audit.common.exception.BusinessException(com.fuquan.audit.common.exception.BusinessErrorCode.CONFLICT,"本次分析标识已被使用，请重新选择分析范围");}
        }
    }
    @GetMapping("/analyses") public ApiResponse<List<BudgetMaterialRisk.Result>> history(@RequestParam(defaultValue="1") int page){return ApiResponse.success(service.history(page));}
    @GetMapping("/analyses/{id}") public ApiResponse<BudgetMaterialRisk.Result> get(@PathVariable UUID id){return ApiResponse.success(service.get(id));}
}
