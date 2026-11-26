package com.fuquan.audit.contract.application.analysis;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

/** Deterministic frozen ranges; global calls are bounded and omissions remain visible in coverage. */
@Component
public class ContractAnalysisPlan {
    public static final int CHUNK=2400,OVERLAP=160,MAX_CALLS=256;
    public List<Step> prepare(ContractAnalysisCase value,Map<UUID,String> texts){
        var work=new ArrayList<>(value.steps());refineOutputLimits(work,value.sources(),texts,callBudget(value,texts));int calls=(int)work.stream().filter(s->Set.of("FILE","FRAGMENT","FOCUSED","CROSS_FILE").contains(s.kind())).count();
        for(var source:value.sources())if(source.status()==SourceState.READY&&!has(work,"recall/"+source.fileId()))work.add(step("recall/"+source.fileId(),source,"RECALL",0,texts.get(source.fileId()).length()));
        // Breadth-first allocation prevents a single long file from consuming all model slots.
        int budget=callBudget(value,texts);
        for(int chunk=0;calls<budget&&chunk<MAX_CALLS;chunk++){
            boolean more=false;
            for(var source:value.sources())if(source.status()==SourceState.READY){
                String text=texts.get(source.fileId());int proposedStart=chunk*(CHUNK-OVERLAP);int start=proposedStart;if(start>=text.length())continue;more=true;
                // Preserve every previously frozen range, including jobs admitted by older code.
                if(work.stream().anyMatch(s->s.fileId()!=null&&s.fileId().equals(source.fileId())
                    &&Set.of("FILE","FRAGMENT").contains(s.kind())&&s.start()<=proposedStart&&s.end()>=Math.min(proposedStart+CHUNK,text.length())))continue;
                int end=fragmentEnd(text,start);if(end<text.length()&&Character.isHighSurrogate(text.charAt(end-1)))end--;
                if(start>0&&Character.isLowSurrogate(text.charAt(start)))start--;
                String id="analyze-v2/"+source.fileId()+"/"+proposedStart;if(has(work,id))continue;
                if(calls>=budget)break;
                work.add(step(id,source,text.length()<=CHUNK?"FILE":"FRAGMENT",start,end));calls++;
            }
            if(!more)break;
        }
        return List.copyOf(work);
    }
    static int fragmentEnd(String text,int start){
        int end=Math.min(start+CHUNK,text.length());
        if(end==text.length())return end;
        // Move only within the existing overlap: no source gap and no oversized call.
        // Keep dates/amounts and sentences together where a nearby boundary exists.
        for(int i=end-1;i>=Math.max(start+1,end-OVERLAP+1);i--)
            if("\n\r\u2028\u2029。；;！？".indexOf(text.charAt(i))>=0)return i+1;
        return end;
    }
    /** Budget grows with the frozen inputs, with a hard deployment-wide ceiling. */
    public int callBudget(ContractAnalysisCase value,Map<UUID,String> texts){
        long required=texts.values().stream().mapToLong(t->Math.max(1,(t.length()+CHUNK-OVERLAP-1L)/(CHUNK-OVERLAP))).sum();
        return (int)Math.min(MAX_CALLS,Math.max(32,required+Math.max(16,required/2)+value.expectedUploads()*2L));
    }
    private void refineOutputLimits(List<Step> work,List<Source> sources,Map<UUID,String> texts,int budget){
        int calls=(int)work.stream().filter(s->Set.of("FILE","FRAGMENT","FOCUSED","CROSS_FILE").contains(s.kind())).count();
        for(var parent:List.copyOf(work)){
            if(!"MODEL_OUTPUT_LIMIT".equals(parent.reasonCode())||parent.end()-parent.start()<700
                    ||!Set.of("FILE","FRAGMENT").contains(parent.kind())||has(work,"split/"+parent.id()+"/L")||calls+2>budget)continue;
            var source=sources.stream().filter(s->s.fileId().equals(parent.fileId())).findFirst().orElseThrow();
            int middle=parent.start()+(parent.end()-parent.start())/2;
            String text=texts.get(source.fileId());int leftEnd=middle+80,rightStart=middle-80;
            if(leftEnd<text.length()&&Character.isLowSurrogate(text.charAt(leftEnd)))leftEnd++;
            if(rightStart>0&&Character.isLowSurrogate(text.charAt(rightStart)))rightStart--;
            work.add(step("split/"+parent.id()+"/L",source,"FRAGMENT",parent.start(),leftEnd));
            work.add(step("split/"+parent.id()+"/R",source,"FRAGMENT",rightStart,parent.end()));calls+=2;
        }
    }
    public static boolean recoveredOutputLimit(Step parent,List<Step> work){
        if(!"MODEL_OUTPUT_LIMIT".equals(parent.reasonCode()))return false;
        int end=parent.start();
        for(var s:work.stream().filter(s->Objects.equals(s.fileId(),parent.fileId())&&s!=parent
            &&s.start()>=parent.start()&&s.end()<=parent.end()&&reviewed(s))
            .sorted(Comparator.comparingInt(Step::start)).toList()){
            if(s.start()>end)return false;end=Math.max(end,s.end());
        }
        return end>=parent.end();
    }
    public static boolean has(List<Step> steps,String id){return steps.stream().anyMatch(s->s.id().equals(id));}
    /** A valid empty response reviewed its range; rejected/incomplete responses did not. */
    public static boolean reviewed(Step s){return Set.of("FILE","FRAGMENT").contains(s.kind())
        &&Set.of(StepState.COMPLETED,StepState.DATA_INSUFFICIENT).contains(s.status())&&s.output()!=null&&s.output().complete();}
    public static Step step(String id,Source s,String kind,int start,int end){return new Step(id,s.fileId(),kind,start,end,sha256(s.fileId()+":"+s.parseResultId()+":"+s.parseVersion()+":"+s.textSha256()+":"+kind+":"+start+":"+end),StepState.QUEUED,null,null,null,null);}
    public boolean fullCoverage(Source source,List<Step> work,int length){
        var ranges=work.stream().filter(s->source.fileId().equals(s.fileId())&&reviewed(s))
            .sorted(Comparator.comparingInt(Step::start)).toList();int end=0;for(var r:ranges){if(r.start()>end)return false;end=Math.max(end,r.end());}return end>=length;
    }
}
