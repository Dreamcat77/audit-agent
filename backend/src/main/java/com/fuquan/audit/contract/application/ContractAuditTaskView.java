package com.fuquan.audit.contract.application;

import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.review.domain.AuditTask;
import java.util.Objects;

/** One latest direct-F02 task composed from durable task, contract, project and progress facts. */
public record ContractAuditTaskView(
        AuditTask task,
        Contract contract,
        Project project,
        F02ReviewProgressSummary reviewProgress) {

    public ContractAuditTaskView {
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(contract, "contract must not be null");
        Objects.requireNonNull(reviewProgress, "reviewProgress must not be null");
    }
}
