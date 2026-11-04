package com.fuquan.audit.contract.infra.memory;

import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.observability.ObservabilityProperties;
import com.fuquan.audit.contract.domain.Contract;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryContractCreationAdapterTest {

    @Test
    void existingFixturesAndNewCompleteSnapshotsShareOneNeutralStore() {
        LocalContractStore store = new LocalContractStore();
        InMemoryContractRepository reads = new InMemoryContractRepository(store,
                new FlowDebugLogger(new ObservabilityProperties()));
        InMemoryContractCreationAdapter writes = new InMemoryContractCreationAdapter(store);
        Contract promoted = PromotionContractFixtures.contract(UUID.randomUUID(), "PROMOTED-LOCAL-1");

        writes.insertCompleteSnapshot(promoted);

        assertThat(reads.findById(LocalContractFixtures.PASS_CONTRACT_ID)).isPresent();
        assertThat(reads.findById(promoted.id())).contains(promoted);
        assertThatThrownBy(() -> writes.insertCompleteSnapshot(
                PromotionContractFixtures.contract(UUID.randomUUID(), promoted.contractNo())))
                .isInstanceOf(BusinessException.class);
        assertThat(reads.findById(promoted.id())).contains(promoted);
    }
}
