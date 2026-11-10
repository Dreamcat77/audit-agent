package com.fuquan.audit.procurement.autocase;
import com.fuquan.audit.common.material.visual.MaterialVisualEvidence;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.fuquan.audit.procurement.autocase.AuditCase.*;

@Component
public class CaseVisualQuality {
    private final MaterialVisualEvidence visual;
    public CaseVisualQuality(MaterialVisualEvidence visual){this.visual=visual;}
    public Document filter(Document d){
        var quality=visual.quotes(d.id().toString(),d.sha256(),d.segments());
        var facts=d.facts().stream().filter(f->!quality.uncertain(f.locator(),f.excerpt())).toList();
        return new Document(d.id(),d.name(),d.size(),d.sha256(),d.storageKey(),d.status(),d.segments(),facts,d.parseMethod(),d.errorCode(),d.calls(),d.readCheckpoint());
    }
    private boolean uncertain(Map<String,MaterialVisualEvidence.QuoteCheck> quality,List<SourceEvidence> evidence){
        return evidence.stream().anyMatch(e->{var check=quality.get(e.documentId()+"/"+e.sha256());return check!=null&&check.uncertain(e.locator(),e.excerpt());});
    }
    public ModuleReview project(ModuleReview review,List<Document> docs){
        var quality=new HashMap<String,MaterialVisualEvidence.QuoteCheck>();for(var d:docs)quality.put(d.id()+"/"+d.sha256(),visual.quotes(d.id().toString(),d.sha256(),d.segments()));
        var checks=review.checks().stream().map(c->uncertain(quality,c.evidence())?new ReviewCheck(c.code(),"UNKNOWN","识别文字存在差异，本项暂无法核对",c.evidence()):c).toList();
        var findings=review.findings().stream().map(f->uncertain(quality,f.evidence())?new ReviewFinding(f.code(),"INFO","相关内容暂无法核对","识别文字存在差异，请以原文件对应位置为准。","查看原文件核对。",f.evidence(),f.origin()):f).toList();
        return new ModuleReview(review.status(),review.inputDigest(),review.version(),review.model(),review.startedAt(),review.completedAt(),review.scope(),review.coverageGaps(),checks,findings,review.rejectedCitations(),review.errorCode());
    }
}
