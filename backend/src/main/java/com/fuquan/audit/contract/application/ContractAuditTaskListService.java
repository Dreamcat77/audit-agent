package com.fuquan.audit.contract.application;

import com.fuquan.audit.contract.application.port.ContractRepository;
import com.fuquan.audit.contract.application.project.port.ProjectContractQueryPort;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.ProjectContract;
import com.fuquan.audit.review.application.port.AuditTaskReadQueryPort;
import com.fuquan.audit.review.domain.AuditTask;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;
import com.fuquan.audit.review.domain.AuditTaskStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Contract-owned read model for the latest real direct-F02 task per contract. */
@Service
public class ContractAuditTaskListService {

    private static final String CONTRACT_SOURCE_TYPE = "CONTRACT";

    private final AuditTaskReadQueryPort tasks;
    private final ContractRepository contracts;
    private final ProjectContractQueryPort associations;
    private final ProjectRepository projects;
    private final F02ReviewProgressService progress;

    public ContractAuditTaskListService(
            AuditTaskReadQueryPort tasks,
            ContractRepository contracts,
            ProjectContractQueryPort associations,
            ProjectRepository projects,
            F02ReviewProgressService progress) {
        this.tasks = Objects.requireNonNull(tasks, "tasks must not be null");
        this.contracts = Objects.requireNonNull(contracts, "contracts must not be null");
        this.associations = Objects.requireNonNull(associations, "associations must not be null");
        this.projects = Objects.requireNonNull(projects, "projects must not be null");
        this.progress = Objects.requireNonNull(progress, "progress must not be null");
    }

    @Transactional(readOnly = true)
    public ContractAuditTaskPage list(int page, int size) {
        if (page < 1 || size < 1) {
            throw new IllegalArgumentException("page and size must be positive");
        }
        AuditTaskReadQueryPort.Page durable = tasks.findLatest(
                CONTRACT_SOURCE_TYPE, ContractReviewService.REVIEW_TYPE, page, size);
        List<ContractAuditTaskView> items = durable.items().stream().map(this::view).toList();
        long totalPages = durable.total() == 0 ? 0 : (durable.total() + durable.size() - 1) / durable.size();
        return new ContractAuditTaskPage(items, durable.page(), durable.size(), durable.total(), totalPages);
    }

    @Transactional(readOnly = true)
    public ContractAuditTaskPage list(ContractAuditTaskListQuery query) {
        return list(query, false);
    }

    @Transactional(readOnly = true)
    public ContractAuditTaskPage list(ContractAuditTaskListQuery query, boolean includeHistory) {
        Objects.requireNonNull(query, "query must not be null");
        if (!query.filtered() && !includeHistory) return list(query.page(), query.size());
        if (!query.filtered()) {
            AuditTaskReadQueryPort.Page durable = tasks.findAllPage(
                    CONTRACT_SOURCE_TYPE, ContractReviewService.REVIEW_TYPE, query.page(), query.size());
            long totalPages = durable.total() == 0 ? 0 : (durable.total() + durable.size() - 1) / durable.size();
            return new ContractAuditTaskPage(durable.items().stream().map(this::view).toList(),
                    durable.page(), durable.size(), durable.total(), totalPages);
        }
        List<ContractAuditTaskView> filtered = (includeHistory ? tasks.findAll(
                        CONTRACT_SOURCE_TYPE, ContractReviewService.REVIEW_TYPE) : tasks.findAllLatest(
                        CONTRACT_SOURCE_TYPE, ContractReviewService.REVIEW_TYPE)).stream()
                .map(this::view)
                .filter(matches(query))
                .toList();
        int offset = Math.min((query.page() - 1) * query.size(), filtered.size());
        int end = Math.min(offset + query.size(), filtered.size());
        long totalPages = filtered.isEmpty() ? 0 : (filtered.size() + query.size() - 1L) / query.size();
        return new ContractAuditTaskPage(
                filtered.subList(offset, end), query.page(), query.size(), filtered.size(), totalPages);
    }

    @Transactional(readOnly = true)
    public ContractAuditTaskSummary summary() {
        return summary(false);
    }

    @Transactional(readOnly = true)
    public ContractAuditTaskSummary summary(boolean includeHistory) {
        List<ContractAuditTaskView> all = (includeHistory ? tasks.findAll(
                        CONTRACT_SOURCE_TYPE, ContractReviewService.REVIEW_TYPE) : tasks.findAllLatest(
                        CONTRACT_SOURCE_TYPE, ContractReviewService.REVIEW_TYPE)).stream()
                .map(this::view)
                .toList();
        long pending = all.stream().filter(item -> item.task().status() == AuditTaskStatus.PENDING
                || item.task().status() == AuditTaskStatus.PROCESSING).count();
        long completed = all.stream().filter(item -> item.task().status() == AuditTaskStatus.DONE).count();
        long needsReview = all.stream().filter(item -> item.reviewProgress().riskCount() > 0
                || item.reviewProgress().pendingAcknowledgement()
                || item.reviewProgress().materialInsufficient()).count();
        return new ContractAuditTaskSummary(all.size(), pending, completed, needsReview);
    }

    private Predicate<ContractAuditTaskView> matches(ContractAuditTaskListQuery query) {
        return view -> matchesKeyword(view, query.keyword())
                && (query.createdFrom() == null || !view.task().createdAt().isBefore(query.createdFrom()))
                && (query.createdTo() == null || !view.task().createdAt().isAfter(query.createdTo()))
                && matchesRisk(view, query.risk())
                && matchesStatus(view, query.status());
    }

    private static boolean matchesKeyword(ContractAuditTaskView view, String keyword) {
        if (keyword == null) return true;
        String normalized = keyword.toLowerCase(Locale.ROOT);
        return contains(view.project() == null ? null : view.project().name(), normalized)
                || contains(view.project() == null ? null : view.project().projectNo(), normalized)
                || contains(view.contract().contractName(), normalized)
                || contains(view.contract().contractNo(), normalized)
                || contains(view.contract().partyB(), normalized);
    }

    private static boolean contains(String value, String normalizedKeyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedKeyword);
    }

    private static boolean matchesRisk(
            ContractAuditTaskView view, ContractAuditTaskListQuery.RiskFilter risk) {
        if (risk == null) return true;
        return switch (risk) {
            case NORMAL -> view.reviewProgress().riskCount() == 0
                    && !view.reviewProgress().materialInsufficient();
            case REVIEW -> view.reviewProgress().riskCount() > 0
                    && !view.reviewProgress().materialInsufficient();
            case INSUFFICIENT -> view.reviewProgress().materialInsufficient();
        };
    }

    private static boolean matchesStatus(
            ContractAuditTaskView view, ContractAuditTaskListQuery.StatusFilter status) {
        if (status == null) return true;
        return switch (status) {
            case PENDING -> view.task().status() == AuditTaskStatus.PENDING;
            case PROCESSING -> view.task().status() == AuditTaskStatus.PROCESSING;
            case COMPLETED -> view.task().status() == AuditTaskStatus.DONE;
            case FAILED -> view.task().status() == AuditTaskStatus.FAILED;
            case NEEDS_REVIEW -> view.reviewProgress().pendingAcknowledgement()
                    || view.reviewProgress().riskCount() > 0
                    || view.reviewProgress().materialInsufficient();
        };
    }

    private ContractAuditTaskView view(AuditTask task) {
        Contract contract = contracts.findById(task.sourceId())
                .orElseThrow(() -> new IllegalStateException("contract audit task references missing contract"));
        Project project = associations.findByContractId(contract.id())
                .map(ProjectContract::projectId)
                .map(projectId -> projects.findById(projectId)
                        .orElseThrow(() -> new IllegalStateException(
                                "project contract association references missing project")))
                .orElse(null);
        return new ContractAuditTaskView(task, contract, project, progress.summarize(contract.id(), task));
    }
}
