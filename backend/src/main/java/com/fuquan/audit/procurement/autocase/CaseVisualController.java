package com.fuquan.audit.procurement.autocase;
import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.common.material.visual.*;
import com.fuquan.audit.security.application.AuthContext;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@RestController @RequestMapping("/api/v1/procurement/cases")
@ConditionalOnProperty(name="audit.procurement.auto-case.enabled",havingValue="true")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','AUDITOR','PROCUREMENT_USER','BUDGET_USER','CONTRACT_USER')")
public class CaseVisualController {
    private final AuditCaseService cases;private final MaterialVisualEvidence visual;private final MaterialPageRenderer renderer;private final AuthContext auth;
    public CaseVisualController(AuditCaseService cases,MaterialVisualEvidence visual,MaterialPageRenderer renderer,AuthContext auth){this.cases=cases;this.visual=visual;this.renderer=renderer;this.auth=auth;}
    private com.fuquan.audit.security.domain.CurrentUser actor(){return auth.currentUser().orElseThrow(()->new BusinessException(BusinessErrorCode.UNAUTHENTICATED));}
    private AuditCase.Document document(UUID id,UUID fileId){return cases.owned(id,actor()).documents().stream().filter(d->d.id().equals(fileId)).findFirst().orElseThrow(()->new BusinessException(BusinessErrorCode.NOT_FOUND));}
    @GetMapping("/{id}/files/{fileId}/visual")
    public ApiResponse<MaterialVisualEvidence.Selection> locate(@PathVariable UUID id,@PathVariable UUID fileId,@RequestParam int start,@RequestParam int end,@RequestParam(required=false) Integer page){
        var d=document(id,fileId);String text=d.segments().stream().map(s->s.text()).collect(java.util.stream.Collectors.joining("\n"));
        return ApiResponse.success(visual.locate(fileId.toString(),d.sha256(),text,start,end,page));
    }
    @GetMapping("/{id}/files/{fileId}/pages/{page}/image")
    public ResponseEntity<byte[]> image(@PathVariable UUID id,@PathVariable UUID fileId,@PathVariable int page)throws IOException {
        var d=document(id,fileId);var original=cases.open(id,fileId,actor());try(var in=original.inputStream()){
            return VisualPageResponse.image(renderer.render(in,d.size(),d.sha256(),page));
        }
    }
    @GetMapping("/{id}/files/{fileId}/office-html")
    public ResponseEntity<ApiResponse<String>> office(@PathVariable UUID id,@PathVariable UUID fileId)throws IOException {
        var d=document(id,fileId);var original=cases.open(id,fileId,actor());try(var in=original.inputStream()){
            return ResponseEntity.ok().header("Cache-Control","no-store").body(ApiResponse.success(renderer.legacyWord(in,d.size(),d.sha256())));
        }
    }
}
