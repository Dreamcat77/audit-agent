package com.fuquan.audit.budget.application;

import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.*;
import java.math.BigDecimal;
import java.util.*;

/** Immutable source facts and reference results; never approval master data. */
public final class BudgetUploadedFacts {
    private BudgetUploadedFacts() {}
    public record Snapshot(List<Row> rows,List<ExtractionIssue> issues) {
        public Snapshot {rows=List.copyOf(rows);issues=issues==null?List.of():List.copyOf(issues);}
        public Snapshot(List<Row> rows){this(rows,List.of());}
    }
    public record Evidence(UUID taskId,String fileName,String field,String value,String locator,String excerpt) {}
    public record Line(String project,String projectCode,String subject,String subjectCode,String year,String department,
            BigDecimal declaredAmount,BigDecimal approvalAmount,String status,String opinion,List<Evidence> sources) {}
    public record Assessment(String code,String level,String title,String explanation,String basis,List<Evidence> sources) {
        public Assessment {sources=List.copyOf(sources);}
    }
    public record FileResult(String status,String coverage,String summary,List<Line> lines,List<Assessment> assessments) {
        public FileResult {assessments=assessments==null?List.of():List.copyOf(assessments);}
        public FileResult(String status,String coverage,String summary,List<Line> lines) {this(status,coverage,summary,lines,List.of());}
        public FileResult(String status,String summary,List<Line> lines) {this(status,null,summary,lines,List.of());}
    }
    public record Input(UUID taskId,String fileName,String status,Snapshot snapshot) {}
}
