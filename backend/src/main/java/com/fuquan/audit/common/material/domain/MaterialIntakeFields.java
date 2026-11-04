package com.fuquan.audit.common.material.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Suggestions backed by procurement material evidence, never final approval facts. */
public record MaterialIntakeFields(String projectName, String projectNo, String departmentName,
        String procurementType, BigDecimal budgetAmount, String subjectCode, String subjectName,
        String procurementMethod, String requirementDescription, String applicantName,
        Map<String, List<Evidence>> evidence, List<String> conflicts, String aiModel, String promptVersion) {
    public record Evidence(String value, String locator, String excerpt) {}
}
