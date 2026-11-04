package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.contract.application.port.ContractRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractSnapshotLockPort;
import com.fuquan.audit.contract.domain.AwardNoticeReference;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.ContractLineItem;
import com.fuquan.audit.contract.domain.Money;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8ContractRepository implements ContractRepository, ContractSnapshotLockPort {

    private final ContractMapper mapper;
    private final FlowDebugLogger flowDebugLogger;

    public Dm8ContractRepository(ContractMapper mapper, FlowDebugLogger flowDebugLogger) {
        this.mapper = mapper;
        this.flowDebugLogger = flowDebugLogger;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Contract> findById(UUID id) {
        long startedAt = System.nanoTime();
        ContractPersistenceRow row = mapper.findById(id.toString());
        flowDebugLogger.log(
                "CONTRACT_REPOSITORY_READ", "CONTRACT", "REPOSITORY", "findById",
                Map.of("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
                        "outcome", row == null ? "NOT_FOUND" : "FOUND"));
        return domain(row);
    }

    @Override
    public Optional<Contract> findByIdForUpdate(UUID id) {
        return domain(mapper.findByIdForUpdate(id.toString()));
    }

    private Optional<Contract> domain(ContractPersistenceRow row) {
        if (row == null) return Optional.empty();
        List<ContractLineItem> items = mapper.findLineItems(row.getId()).stream()
                .map(this::lineItem)
                .toList();
        ContractAwardNoticePersistenceRow awardRow = mapper.findAwardNotice(row.getId());
        return Optional.of(new Contract(
                UUID.fromString(row.getId()), row.getContractNo(), row.getContractName(),
                row.getContractType(), row.getPartyA(), row.getPartyB(), row.getPartyBCreditCode(),
                Money.of(row.getContractAmount()), row.getAmountInWords(), row.getStatus(),
                row.getVersionNo(), row.getCreatedAt().toInstant(ZoneOffset.UTC),
                row.getUpdatedAt().toInstant(ZoneOffset.UTC), items,
                awardRow == null ? null : award(awardRow)));
    }

    private ContractLineItem lineItem(ContractLineItemPersistenceRow row) {
        return new ContractLineItem(
                UUID.fromString(row.id()), UUID.fromString(row.contractId()), row.itemKey(), row.itemName(),
                com.fuquan.audit.contract.domain.ContractLineItemBasis.valueOf(row.basis()),
                row.quantity(), row.unitPrice(), Money.of(row.declaredAmount()), row.sortNo());
    }

    private AwardNoticeReference award(ContractAwardNoticePersistenceRow row) {
        return new AwardNoticeReference(
                UUID.fromString(row.id()), UUID.fromString(row.contractId()), row.noticeNo(),
                row.projectName(), row.winningBidder(), row.winningBidderCreditCode(),
                Money.of(row.winningAmount()), row.winningDate(), row.sourceRef());
    }
}
