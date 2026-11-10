package com.fuquan.audit.contract.application.analysis;
import com.fuquan.audit.common.material.visual.MaterialVisualEvidence;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

/** A disagreement between original-pixel reads is not a proven contract defect or a pass. */
@Component
public class ContractVisualQuality {
    private final MaterialVisualEvidence visual;
    public ContractVisualQuality(MaterialVisualEvidence visual){this.visual=visual;}
    public boolean uncertain(Source source,String text,List<Evidence> evidence){
        return evidence.stream().filter(e->e.fileId().equals(source.fileId())&&e.fileSha256().equals(source.sha256())&&e.textSha256().equals(source.textSha256()))
                .anyMatch(e->visual.uncertain(source.fileId().toString(),source.sha256(),text,e.start(),e.end()));
    }
    public Output project(Output output,Source source,String text){
        if(output==null)return null;
        var facts=output.facts().stream().filter(f->!uncertain(source,text,f.evidence())).toList();
        var checks=output.checks().stream().map(c->uncertain(source,text,c.evidence())?
                new Check(c.code(),"NOT_EVALUATED","识别文字存在差异，本项暂无法核对",c.evidence()):c).toList();
        var findings=output.findings().stream().map(f->uncertain(source,text,f.evidence())?
                new Finding(f.code(),"INFO","相关内容暂无法核对","识别文字存在差异，请以原文件对应位置为准。","查看原文件核对。",f.evidence(),f.origin()):f).toList();
        var clauses=output.clauses().stream().filter(c->!uncertain(source,text,c.evidence())).toList();
        return new Output(output.role(),output.profile(),output.complete(),facts,clauses,checks,findings,output.rejectedCitations(),output.model(),output.version());
    }
}
