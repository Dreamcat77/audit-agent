package com.fuquan.audit.budget.application;

import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.*;
import java.math.BigDecimal;
import java.util.*;

/** Immutable source facts and reference results; never approval master data. */
public final class BudgetUploadedFacts {
    private BudgetUploadedFacts() {}
    public record Snapshot(List<Row> rows,List<ExtractionIssue> issues,List<Segment> segments,String sha256,String method) {
        public Snapshot {rows=List.copyOf(rows);issues=issues==null?List.of():List.copyOf(issues);segments=segments==null?List.of():List.copyOf(segments);}
        public Snapshot(List<Row> rows,List<ExtractionIssue> issues){this(rows,issues,List.of(),null,null);}
        public Snapshot(List<Row> rows){this(rows,List.of());}
    }
    public record Evidence(UUID taskId,String fileName,String field,String value,String locator,String excerpt,Integer start,Integer end,Integer pageNumber) {
        public Evidence(UUID taskId,String fileName,String field,String value,String locator,String excerpt){this(taskId,fileName,field,value,locator,excerpt,null,null,null);}
    }
    public record Line(String project,String projectCode,String subject,String subjectCode,String year,String department,
            BigDecimal declaredAmount,BigDecimal approvalAmount,String status,String opinion,List<Evidence> sources) {}
    public record Assessment(String code,String level,String title,String explanation,String basis,List<Evidence> sources) {
        public Assessment {sources=List.copyOf(sources);}
    }
    public record Total(String role,String scope,String year,BigDecimal amount,List<Evidence> sources) {
        public Total {sources=List.copyOf(sources);}
    }
    public record FileResult(String status,String coverage,String summary,List<Line> lines,List<Assessment> assessments,
            String projectName,List<Total> totals,String analysisCoverage) {
        public FileResult {assessments=assessments==null?List.of():List.copyOf(assessments);totals=totals==null?List.of():List.copyOf(totals);}
        public FileResult(String status,String coverage,String summary,List<Line> lines,List<Assessment> assessments) {this(status,coverage,summary,lines,assessments,null,List.of(),null);}
        public FileResult(String status,String coverage,String summary,List<Line> lines) {this(status,coverage,summary,lines,List.of());}
        public FileResult(String status,String summary,List<Line> lines) {this(status,null,summary,lines,List.of());}
    }
    public record Input(UUID taskId,String fileName,String status,Snapshot snapshot) {}
}
