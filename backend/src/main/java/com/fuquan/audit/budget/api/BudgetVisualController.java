package com.fuquan.audit.budget.api;
import com.fuquan.audit.budget.application.*;
import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.common.material.visual.*;
import com.fuquan.audit.security.application.AuthContext;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController @RequestMapping("/api/v1/budget/material-uploads")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','AUDITOR','FINANCE_REVIEWER','PROCUREMENT_USER','BUDGET_USER','CONTRACT_USER')")
public class BudgetVisualController {
    private final BudgetMaterialService files;private final BudgetMaterialBatchService batches;private final MaterialVisualEvidence visual;private final MaterialPageRenderer renderer;private final AuthContext auth;
    public BudgetVisualController(BudgetMaterialService files,BudgetMaterialBatchService batches,MaterialVisualEvidence visual,MaterialPageRenderer renderer,AuthContext auth){this.files=files;this.batches=batches;this.visual=visual;this.renderer=renderer;this.auth=auth;}
    private com.fuquan.audit.security.domain.CurrentUser actor(){return auth.currentUser().orElseThrow(()->new BusinessException(BusinessErrorCode.UNAUTHENTICATED));}
    @GetMapping("/{id}/visual")
    public ApiResponse<MaterialVisualEvidence.Selection> locate(@PathVariable UUID id,@RequestParam int start,@RequestParam int end,@RequestParam(required=false) Integer page){
        var job=files.owned(id,actor());var snapshot=batches.snapshot(id);
        String text=snapshot==null?"":BudgetSourceText.text(snapshot);
        return ApiResponse.success(visual.locate(id.toString(),job.sha256(),text,start,end,page));
    }
    @GetMapping("/{id}/pages/{page}/image")
    public ResponseEntity<byte[]> image(@PathVariable UUID id,@PathVariable int page)throws IOException {
        var user=actor();var job=files.owned(id,user);var task=files.task(id);
        try(var in=files.openOriginal(id,user)){return VisualPageResponse.image(renderer.render(in,task.fileSize(),job.sha256(),page));}
    }
    @GetMapping("/{id}/original")
    public ResponseEntity<byte[]> original(@PathVariable UUID id)throws IOException {
        var user=actor();var job=files.owned(id,user);var task=files.task(id);
        if(task.fileSize()>60L*1024*1024)throw new BusinessException(BusinessErrorCode.PAYLOAD_TOO_LARGE);
        try(var in=files.openOriginal(id,user)){
            byte[] bytes=in.readNBytes(60*1024*1024+1);
            if(bytes.length!=task.fileSize())throw new BusinessException(BusinessErrorCode.CONFLICT);
            try {
                String sha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
                if(!sha.equalsIgnoreCase(job.sha256()))throw new BusinessException(BusinessErrorCode.CONFLICT);
            }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
            return ResponseEntity.ok().contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                    .header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff")
                    .header("Content-Disposition","attachment; filename=\"original-document\"").body(bytes);
        }
    }
    @GetMapping("/{id}/office-html")
    public ResponseEntity<ApiResponse<String>> office(@PathVariable UUID id)throws IOException {
        var user=actor();var job=files.owned(id,user);var task=files.task(id);
        try(var in=files.openOriginal(id,user)){
            return ResponseEntity.ok().header("Cache-Control","no-store").body(ApiResponse.success(renderer.legacyWord(in,task.fileSize(),job.sha256())));
        }
    }
}
