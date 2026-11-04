package com.fuquan.audit.contract.infra.memory;

import com.fuquan.audit.contract.application.ContractListQuery;
import com.fuquan.audit.contract.application.ContractSummary;
import com.fuquan.audit.contract.application.port.ContractQueryPort;
import com.fuquan.audit.contract.domain.Contract;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** local profile 的合同目录读模型。 */
@Repository
@Profile("local")
public class InMemoryContractQueryAdapter implements ContractQueryPort {
    @Override
    public List<ContractSummary> findPage(ContractListQuery query) {
        return filtered(query)
                .sorted(Comparator.comparing(Contract::updatedAt).reversed()
                        .thenComparing(Contract::id, Comparator.reverseOrder()))
                .skip(query.offset())
                .limit(query.size())
                .map(InMemoryContractQueryAdapter::summary)
                .toList();
    }

    @Override
    public long count(ContractListQuery query) {
        return filtered(query).count();
    }

    private Stream<Contract> filtered(ContractListQuery query) {
        String keyword = query.keyword() == null ? null : query.keyword().toUpperCase(Locale.ROOT);
        return LocalContractFixtures.contracts().stream()
                .filter(contract -> query.status() == null || contract.status().equals(query.status()))
                .filter(contract -> keyword == null
                        || contains(contract.contractNo(), keyword)
                        || contains(contract.contractName(), keyword)
                        || contains(contract.partyA(), keyword)
                        || contains(contract.partyB(), keyword));
    }

    private static boolean contains(String value, String keyword) {
        return value.toUpperCase(Locale.ROOT).contains(keyword);
    }

    private static ContractSummary summary(Contract contract) {
        return new ContractSummary(
                contract.id(), contract.contractNo(), contract.contractName(), contract.contractType(),
                contract.partyA(), contract.partyB(), contract.partyBCreditCode(),
                contract.contractAmount().amount(), contract.amountInWords(), contract.status(),
                contract.versionNo(), contract.createdAt(), contract.updatedAt());
    }
}
