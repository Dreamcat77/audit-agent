package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.common.observability.ObservabilityProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Dm8ContractRepositoryTest {

    @Mock
    private ContractMapper mapper;

    @Test
    void mapsContractLineItemsAndAwardBasisWithoutInfrastructureTypesLeaking() {
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000001");
        ContractPersistenceRow contract = contractRow(id);
        when(mapper.findById(id.toString())).thenReturn(contract);
        when(mapper.findLineItems(id.toString())).thenReturn(List.of(
                new ContractLineItemPersistenceRow(
                        "20000000-0000-0000-0000-000000000001", id.toString(), "ITEM-1", "Item",
                        new BigDecimal("2.0000"), new BigDecimal("50.0000"), new BigDecimal("100.00"), 1)));
        when(mapper.findAwardNotice(id.toString())).thenReturn(new ContractAwardNoticePersistenceRow(
                "30000000-0000-0000-0000-000000000001", id.toString(), "AWARD-1", "Project",
                "Supplier", "CREDIT-1", new BigDecimal("100.00"), LocalDate.of(2026, 8, 1),
                "source", LocalDateTime.parse("2026-08-17T00:00:00")));
        Dm8ContractRepository repository = new Dm8ContractRepository(
                mapper, new FlowDebugLogger(new ObservabilityProperties()));

        var result = repository.findById(id).orElseThrow();

        assertThat(result.contractAmount().amount()).isEqualByComparingTo("100.00");
        assertThat(result.lineItems()).singleElement().satisfies(item ->
                assertThat(item.calculatedAmount()).isEqualByComparingTo("100.00000000"));
        assertThat(result.awardNotice().winningBidderCreditCode()).isEqualTo("CREDIT-1");
    }

    @Test
    void missingContractReturnsEmptyWithoutReadingChildren() {
        UUID id = UUID.randomUUID();
        when(mapper.findById(id.toString())).thenReturn(null);
        Dm8ContractRepository repository = new Dm8ContractRepository(
                mapper, new FlowDebugLogger(new ObservabilityProperties()));

        assertThat(repository.findById(id)).isEmpty();
    }

    private ContractPersistenceRow contractRow(UUID id) {
        ContractPersistenceRow row = new ContractPersistenceRow();
        row.setId(id.toString());
        row.setContractNo("C-1");
        row.setContractName("Contract");
        row.setContractType("PROCUREMENT");
        row.setPartyA("Buyer");
        row.setPartyB("Supplier");
        row.setPartyBCreditCode("CREDIT-1");
        row.setContractAmount(new BigDecimal("100.00"));
        row.setAmountInWords("壹佰元整");
        row.setStatus("READY");
        row.setVersionNo(2);
        row.setCreatedAt(LocalDateTime.parse("2026-08-17T00:00:00"));
        row.setUpdatedAt(LocalDateTime.parse("2026-08-17T00:00:00"));
        return row;
    }
}
