package com.fuquan.audit.common.material.visual;
import com.fuquan.audit.common.exception.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Service;

/** Ownership and frozen source validation are required at each business API before entry. No OCR on GET. */
@Service
public class MaterialVisualEvidence {
    private final VisualIndexStore store;
    public MaterialVisualEvidence(VisualIndexStore store){this.store=store;}
    public record Selection(String status,List<VisualIndex.Anchor> anchors,List<Integer> pages) {}
    @FunctionalInterface public interface QuoteCheck {boolean uncertain(String locator,String quote);}
    /** Build once per frozen document, rather than hashing and joining a large PDF for every fact. */
    public QuoteCheck quotes(String id,String sha,List<com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Segment> segments){
        String text=segments.stream().map(s->s.text()).collect(java.util.stream.Collectors.joining("\n"));
        var uncertain=store.find(id,sha,digest(text)).stream().flatMap(v->v.anchors().stream()).filter(a->a.end()<=text.length()&&a.method().equals("OCR")&&a.verification().equals("UNCERTAIN")).toList();
        if(uncertain.isEmpty())return (locator,quote)->false;
        var starts=new ArrayList<Integer>();int position=0;
        for(var s:segments){starts.add(position);position+=s.text().length()+1;}
        return (locator,quote)->{
            if(quote==null||quote.isBlank()||locator==null)return false;
            for(int i=0;i<segments.size();i++){
                var s=segments.get(i);
                if(!s.locator().equals(locator)&&!locator.startsWith(s.locator()+"/"))continue;
                int at=s.text().indexOf(quote);if(at<0||s.text().indexOf(quote,at+1)>=0)continue;
                int start=starts.get(i)+at,end=start+quote.length();
                if(uncertain.stream().anyMatch(a->a.start()<end&&a.end()>start))return true;
            }return false;
        };
    }
    public boolean uncertain(String id,String sha,String text,int start,int end){
        if(start<0||end<=start||end>text.length())return false;
        return store.find(id,sha,digest(text)).stream().flatMap(v->v.anchors().stream()).filter(a->a.end()<=text.length()).anyMatch(a->a.method().equals("OCR")&&a.start()<end&&a.end()>start&&a.verification().equals("UNCERTAIN"));
    }
    public boolean uncertainQuote(String id,String sha,java.util.List<com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Segment> segments,String locator,String quote){
        return quotes(id,sha,segments).uncertain(locator,quote);
    }
    public Selection locate(String id,String sha,String text,int start,int end,Integer pageHint){
        if(start<0||end<=start||end>text.length()||end-start>20000)throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT);
        var index=store.find(id,sha,digest(text));
        var matching=index.stream().flatMap(v->v.anchors().stream()).filter(a->a.end()<=text.length()&&a.start()<end&&a.end()>start).toList();
        // A nearby box does not prove the whole quote is mapped.
        var ranges=matching.stream().sorted(Comparator.comparingInt(VisualIndex.Anchor::start)).toList();
        int cursor=start;boolean complete=true;
        for(var range:ranges){
            if(range.start()>cursor&&!text.substring(cursor,Math.min(range.start(),end)).isBlank())complete=false;
            cursor=Math.max(cursor,Math.min(range.end(),end));
        }
        if(cursor<end&&!text.substring(cursor,end).isBlank())complete=false;
        if(!complete)matching=List.of();
        var pages=matching.stream().map(VisualIndex.Anchor::pageNumber).distinct().sorted().toList();
        if(pages.isEmpty()&&pageHint!=null&&pageHint>0&&pageHint<=1024)pages=List.of(pageHint);
        return new Selection(!matching.isEmpty()?"LOCATED":pages.isEmpty()?"BROWSE":"PAGE_ONLY",matching,pages);
    }
    public static String digest(String text){return digest(text.getBytes(StandardCharsets.UTF_8));}
    public static String digest(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception e){throw new IllegalStateException(e);}}
}
