package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.contract.application.closeout.port.ContractReviewCloseoutRepository;
import com.fuquan.audit.contract.domain.closeout.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8ContractReviewCloseoutRepository implements ContractReviewCloseoutRepository {
    private final ContractReviewCloseoutMapper mapper;
    public Dm8ContractReviewCloseoutRepository(ContractReviewCloseoutMapper mapper) { this.mapper = mapper; }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void insertAcknowledgement(ContractReviewFindingAcknowledgement value) { one(mapper.insertAcknowledgement(ContractReviewCloseoutMapper.AcknowledgementRow.from(value))); }
    @Override public Optional<ContractReviewFindingAcknowledgement> findAcknowledgement(UUID id) { return Optional.ofNullable(mapper.findAcknowledgement(id.toString())).map(ContractReviewCloseoutMapper.AcknowledgementRow::toDomain); }
    @Override public Optional<ContractReviewFindingAcknowledgement> findAcknowledgementByFinding(UUID id) { return Optional.ofNullable(mapper.findAcknowledgementByFinding(id.toString())).map(ContractReviewCloseoutMapper.AcknowledgementRow::toDomain); }
    @Override public List<ContractReviewFindingAcknowledgement> findAcknowledgementsByRun(UUID id) { return mapper.findAcknowledgementsByRun(id.toString()).stream().map(ContractReviewCloseoutMapper.AcknowledgementRow::toDomain).toList(); }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void insertReport(ContractReviewReport value) { one(mapper.insertReport(ContractReviewCloseoutMapper.ReportRow.from(value))); }
    @Override public Optional<ContractReviewReport> findReport(UUID id) { return Optional.ofNullable(mapper.findReport(id.toString())).map(ContractReviewCloseoutMapper.ReportRow::toDomain); }
    @Override public List<ContractReviewReport> findReportsByRun(UUID id) { return mapper.findReportsByRun(id.toString()).stream().map(ContractReviewCloseoutMapper.ReportRow::toDomain).toList(); }
    @Override public int nextReportVersion(UUID id) { Integer max = mapper.maxReportVersion(id.toString()); return max == null ? 1 : Math.addExact(max, 1); }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public boolean compareAndSetReport(ContractReviewReport expected, ContractReviewReport next) {
        expected.requireMutableTransitionTo(next);
        return mapper.updateReport(ContractReviewCloseoutMapper.ReportRow.from(expected), ContractReviewCloseoutMapper.ReportRow.from(next)) == 1;
    }
    private static void one(int count) { if (count != 1) throw new IllegalStateException("closeout insert did not affect one row"); }
}
