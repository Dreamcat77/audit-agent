package com.fuquan.audit.integration.material;
import com.fuquan.audit.common.material.visual.*;
import com.fuquan.audit.common.material.visual.VisualIndex.*;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Segment;
import java.util.*;

final class VisualIndexBuilder {
    static VisualIndex build(String id,String sha,List<Segment> segments,List<Block> blocks){
        var result=new ArrayList<Anchor>();int offset=0;
        var starts=new HashMap<String,Integer>();
        for(var s:segments){starts.put(s.locator(),offset);offset+=s.text().length()+1;}
        for(var block:blocks){
            var parts=segments.stream().filter(s->s.locator().replaceFirst("/part:\\d+$","").equals(block.locator())).toList();
            String text=parts.stream().map(Segment::text).collect(java.util.stream.Collectors.joining());
            if(text.length()!=block.textLength()||!MaterialVisualEvidence.digest(text).equals(block.textSha256()))continue;
            for(var r:block.regions()){
                int start=map(parts,starts,r.start(),false),end=map(parts,starts,r.end(),true);
                if(start>=0&&end>start)result.add(new Anchor(block.pageNumber(),block.method(),start,end,r.polygon(),r.confidence(),r.verification()));
            }
        }
        String canonical=segments.stream().map(Segment::text).collect(java.util.stream.Collectors.joining("\n"));
        return new VisualIndex(id,sha,MaterialVisualEvidence.digest(canonical),result);
    }
    private static int map(List<Segment> parts,Map<String,Integer> starts,int position,boolean end){
        int at=0;
        for(var p:parts){if(position<at+p.text().length()||end&&position==at+p.text().length())return starts.get(p.locator())+position-at;at+=p.text().length();}
        return -1;
    }
}
