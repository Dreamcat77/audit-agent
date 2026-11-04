package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.contract.application.ContractListQuery;
import com.fuquan.audit.contract.application.ContractSummary;
import com.fuquan.audit.contract.application.port.ContractQueryPort;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** DM8-backed contract catalog projection with database pagination. */
@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8ContractQueryAdapter implements ContractQueryPort {
    private final ContractMapper mapper;

    public Dm8ContractQueryAdapter(ContractMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<ContractSummary> findPage(ContractListQuery query) {
        return mapper.findSummaryPage(
                        query.status(), query.keyword(), query.offset(), query.offset() + query.size())
                .stream().map(Dm8ContractQueryAdapter::summary).toList();
    }

    @Override
    public long count(ContractListQuery query) {
        return mapper.countSummaries(query.status(), query.keyword());
    }

    private static ContractSummary summary(ContractPersistenceRow row) {
        return new ContractSummary(
                UUID.fromString(row.getId()), row.getContractNo(), row.getContractName(),
                row.getContractType(), row.getPartyA(), row.getPartyB(), row.getPartyBCreditCode(),
                row.getContractAmount(), row.getAmountInWords(), row.getStatus(), row.getVersionNo(),
                row.getCreatedAt().toInstant(ZoneOffset.UTC), row.getUpdatedAt().toInstant(ZoneOffset.UTC));
    }
}
