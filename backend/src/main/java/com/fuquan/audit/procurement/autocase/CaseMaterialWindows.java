package com.fuquan.audit.procurement.autocase;

import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Segment;
import java.util.*;

/** All source paragraphs are visited. Window identity is independent of provider responses. */
final class CaseMaterialWindows {
    static final int DIRECT_LIMIT=96000,WINDOW_CHARS=48000;
    static boolean required(List<Segment> source){return source.stream().mapToLong(s->s.text().length()).sum()>DIRECT_LIMIT;}
    static List<List<Segment>> split(List<Segment> source) {
        var windows=new ArrayList<List<Segment>>();var current=new ArrayList<Segment>();int size=0;
        for(var segment:source) {
            if(segment.text().length()>WINDOW_CHARS)throw new IllegalArgumentException("unbounded source paragraph");
            if(!current.isEmpty()&&size+segment.text().length()>WINDOW_CHARS) {
                windows.add(List.copyOf(current));current.clear();size=0;
            }
            current.add(segment);size+=segment.text().length();
        }
        if(!current.isEmpty())windows.add(List.copyOf(current));return List.copyOf(windows);
    }
    static String identity(UUID document,int index){return UUID.nameUUIDFromBytes((document+"/field-windows-v1/"+index)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();}
}
