package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.infra.memory.PromotionContractFixtures;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.reset;

class Dm8ContractCreationAdapterTest {

    @Test
    void insertsRootThenLinesThenOptionalAwardAsOneSnapshotBoundary() {
        ContractCreationMapper mapper = mock(ContractCreationMapper.class);
        Dm8ContractCreationAdapter adapter = new Dm8ContractCreationAdapter(mapper);
        Contract contract = PromotionContractFixtures.contract(UUID.randomUUID(), "DM8-CREATE-1");

        adapter.insertCompleteSnapshot(contract);

        InOrder order = inOrder(mapper);
        order.verify(mapper).insertContract(any());
        order.verify(mapper).insertLineItem(any());
        order.verify(mapper).insertAwardNotice(any());
    }

    @Test
    void duplicateContractNumberMapsToConflictWhileStorageFailurePropagates() {
        ContractCreationMapper mapper = mock(ContractCreationMapper.class);
        Dm8ContractCreationAdapter adapter = new Dm8ContractCreationAdapter(mapper);
        Contract contract = PromotionContractFixtures.contract(UUID.randomUUID(), "DM8-DUPLICATE");
        when(mapper.insertContract(any())).thenThrow(new DuplicateKeyException("UK_CONTRACT_NO"));
        assertThatThrownBy(() -> adapter.insertCompleteSnapshot(contract)).isInstanceOf(BusinessException.class);

        reset(mapper);
        org.springframework.dao.DataAccessResourceFailureException outage =
                new org.springframework.dao.DataAccessResourceFailureException("outage");
        when(mapper.insertContract(any())).thenThrow(outage);
        assertThatThrownBy(() -> adapter.insertCompleteSnapshot(contract)).isSameAs(outage);
    }
}
