package com.fuquan.audit.budget.application;

import static com.fuquan.audit.budget.application.BudgetUploadedFacts.*;
import java.util.*;

/** Frozen budget text and exact offsets; never OCRs or invokes a model on a GET. */
public final class BudgetSourceText {
    private BudgetSourceText() {}
    public static String text(Snapshot snapshot){return String.join("\n",snapshot.segments().stream().map(s->s.text()).toList());}
    public static Evidence locate(Evidence source,Snapshot snapshot) {
        int offset=0;
        for(var segment:snapshot.segments()) {
            if(source.locator().equals(segment.locator())||source.locator().startsWith(segment.locator()+"/")) {
                int low=0,high=segment.text().length();String suffix=source.locator().substring(segment.locator().length());
                var line=java.util.regex.Pattern.compile("/line:([0-9]+)").matcher(suffix);
                var record=java.util.regex.Pattern.compile("/record:([0-9]+):([0-9]+)").matcher(suffix);
                if(record.find()){low=Integer.parseInt(record.group(1));high=Integer.parseInt(record.group(2));}
                else if(line.find()) {
                    int count=Integer.parseInt(line.group(1));
                    for(int n=0;n<count;n++){int at=segment.text().indexOf('\n',low);if(at<0)return source;low=at+1;}
                    int end=segment.text().indexOf('\n',low);if(end>=0)high=end;
                }
                if(low<0||high>segment.text().length()||low>high)return source;
                String quote=source.excerpt();
                // Spreadsheet labels plus units may be synthetic; locate only the recorded physical cell.
                var column=java.util.regex.Pattern.compile("/col:([0-9]+)").matcher(suffix);
                if(segment.locator().startsWith("excel/")&&column.find()) {
                    int cell=Integer.parseInt(column.group(1));var values=segment.text().split("\\|",-1);
                    if(cell<1||cell>values.length)return source;
                    low=0;for(int n=0;n<cell-1;n++)low+=values[n].length()+1;
                    String raw=values[cell-1];int left=raw.indexOf(raw.strip());quote=raw.strip();
                    if(quote.isBlank())return source;low+=left;high=low+quote.length();
                }
                if(quote==null||quote.isBlank())return source;
                String area=segment.text().substring(low,high);int at=area.indexOf(quote);
                if(at<0||area.indexOf(quote,at+1)>=0)return source;
                var page=java.util.regex.Pattern.compile("(?:pdf/page:|pdf/page/)([0-9]+)").matcher(segment.locator());
                return new Evidence(source.taskId(),source.fileName(),source.field(),source.value(),source.locator(),quote,
                        offset+low+at,offset+low+at+quote.length(),page.find()?Integer.valueOf(page.group(1)):null);
            }
            offset+=segment.text().length()+1;
        }
        return source;
    }
}
