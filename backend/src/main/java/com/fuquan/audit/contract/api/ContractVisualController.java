package com.fuquan.audit.contract.api;
import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.common.material.visual.*;
import com.fuquan.audit.contract.application.analysis.*;
import com.fuquan.audit.contract.application.project.ProjectFileContentReadService;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.io.IOException;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@RestController @RequestMapping("/api/v1/contract-analysis-cases")
@ConditionalOnProperty(name="audit.contract.analysis.enabled",havingValue="true")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','AUDITOR','LEGAL','PROCUREMENT_USER','BUDGET_USER','CONTRACT_USER')")
public class ContractVisualController {
    private final ContractAnalysisService cases;private final ContractAnalysisSources sources;private final ProjectFileContentReadService content;
    private final MaterialVisualEvidence visual;private final MaterialPageRenderer renderer;private final AuthContext auth;
    public ContractVisualController(ContractAnalysisService cases,ContractAnalysisSources sources,ProjectFileContentReadService content,MaterialVisualEvidence visual,MaterialPageRenderer renderer,AuthContext auth){this.cases=cases;this.sources=sources;this.content=content;this.visual=visual;this.renderer=renderer;this.auth=auth;}
    private CurrentUser actor(){return auth.currentUser().orElseThrow(()->new BusinessException(BusinessErrorCode.UNAUTHENTICATED));}
    private ContractAnalysisCase.Source source(ContractAnalysisCase c,UUID id){return c.sources().stream().filter(s->s.fileId().equals(id)).findFirst().orElseThrow(()->new BusinessException(BusinessErrorCode.NOT_FOUND));}
    @GetMapping("/{id}/sources/{fileId}/visual")
    public ApiResponse<MaterialVisualEvidence.Selection> locate(@PathVariable UUID id,@PathVariable UUID fileId,@RequestParam int start,@RequestParam int end,@RequestParam(required=false) Integer page){
        var c=cases.owned(id,actor());var s=source(c,fileId);var p=sources.load(c.projectId(),s);
        return ApiResponse.success(visual.locate(fileId.toString(),s.sha256(),p.canonicalText(),start,end,page));
    }
    @GetMapping("/{id}/sources/{fileId}/pages/{page}/image")
    public ResponseEntity<byte[]> image(@PathVariable UUID id,@PathVariable UUID fileId,@PathVariable int page)throws IOException {
        var c=cases.owned(id,actor());var s=source(c,fileId);sources.load(c.projectId(),s);
        var original=content.openVerified(sources.file(c.projectId(),s));try(var in=original.inputStream()){
            return VisualPageResponse.image(renderer.render(in,original.size(),s.sha256(),page));
        }
    }
    @GetMapping("/{id}/sources/{fileId}/office-html")
    public ResponseEntity<ApiResponse<String>> office(@PathVariable UUID id,@PathVariable UUID fileId)throws IOException {
        var c=cases.owned(id,actor());var s=source(c,fileId);sources.load(c.projectId(),s);
        var original=content.openVerified(sources.file(c.projectId(),s));try(var in=original.inputStream()){
            return ResponseEntity.ok().header("Cache-Control","no-store").body(ApiResponse.success(renderer.legacyWord(in,original.size(),s.sha256())));
        }
    }
}
