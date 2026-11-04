package com.fuquan.audit.contract.infra.memory.closeout;

import com.fuquan.audit.contract.application.closeout.port.ContractReviewCloseoutRepository;
import com.fuquan.audit.contract.domain.closeout.ContractReviewFindingAcknowledgement;
import com.fuquan.audit.contract.domain.closeout.ContractReviewReport;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/** Local-only atomic fake. Real rollback/locking requires the DM8 adapter. */
@Repository
@Profile("local")
public class InMemoryContractReviewCloseoutRepository implements ContractReviewCloseoutRepository {
    private final ConcurrentHashMap<UUID, ContractReviewFindingAcknowledgement> acknowledgements = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ContractReviewReport> reports = new ConcurrentHashMap<>();
    @Override public void insertAcknowledgement(ContractReviewFindingAcknowledgement value) {
        if (acknowledgements.putIfAbsent(value.findingId(), value) != null)
            throw new DuplicateKeyException("finding already acknowledged");
    }
    @Override public void rollbackUncommittedAcknowledgement(ContractReviewFindingAcknowledgement value) {
        acknowledgements.remove(value.findingId(), value);
    }
    @Override public Optional<ContractReviewFindingAcknowledgement> findAcknowledgement(UUID id) {
        return acknowledgements.values().stream().filter(a -> a.acknowledgementId().equals(id)).findFirst();
    }
    @Override public Optional<ContractReviewFindingAcknowledgement> findAcknowledgementByFinding(UUID id) {
        return Optional.ofNullable(acknowledgements.get(id));
    }
    @Override public List<ContractReviewFindingAcknowledgement> findAcknowledgementsByRun(UUID id) {
        return acknowledgements.values().stream().filter(a -> a.reviewRunId().equals(id))
                .sorted(Comparator.comparing(a -> a.findingId().toString())).toList();
    }
    @Override public void insertReport(ContractReviewReport value) {
        if (reports.putIfAbsent(key(value), value) != null) throw new DuplicateKeyException("report version exists");
    }
    @Override public Optional<ContractReviewReport> findReport(UUID id) {
        return reports.values().stream().filter(r -> r.reportId().equals(id)).findFirst();
    }
    @Override public List<ContractReviewReport> findReportsByRun(UUID id) {
        return reports.values().stream().filter(r -> r.reviewRunId().equals(id))
                .sorted(Comparator.comparingInt(ContractReviewReport::reportVersion)).toList();
    }
    @Override public int nextReportVersion(UUID id) {
        return findReportsByRun(id).stream().mapToInt(ContractReviewReport::reportVersion).max().orElse(0) + 1;
    }
    @Override public boolean compareAndSetReport(ContractReviewReport expected, ContractReviewReport next) {
        expected.requireMutableTransitionTo(next);
        return reports.replace(key(expected), expected, next);
    }
    @Override public void rollbackUncommittedReport(ContractReviewReport before, ContractReviewReport unpublished) {
        if (before == null) reports.remove(key(unpublished), unpublished);
        else reports.replace(key(unpublished), unpublished, before);
    }
    private static String key(ContractReviewReport r) { return r.reviewRunId() + ":" + r.reportVersion(); }
}
