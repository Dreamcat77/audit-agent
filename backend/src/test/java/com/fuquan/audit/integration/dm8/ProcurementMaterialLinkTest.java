package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fuquan.audit.procurement.application.port.ProcurementProjectCommandPort.CreateCommand;
import com.fuquan.audit.common.exception.BusinessException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProcurementMaterialLinkTest {
    private final ProcurementProjectMapper mapper = mock(ProcurementProjectMapper.class);
    private final Dm8ProcurementProjectQueryAdapter adapter = new Dm8ProcurementProjectQueryAdapter(mapper);
    private final UUID primary = UUID.randomUUID();
    private final UUID supplement = UUID.randomUUID();
    private final CreateCommand command = new CreateCommand(primary, List.of(primary, supplement), "PR", "项目", "部门",
            "货物", new BigDecimal("100.00"), "A1", "设备", "公开招标", null, "申请人", null);
    private void demandInsert(int count) {
        when(mapper.lockParsedMaterial(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(mapper.insert(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyString(), isNull(), anyString(), isNull())).thenReturn(count);
    }
    @Test void createsBothRecordsInsideDeclaredTransaction() throws Exception {
        demandInsert(1); when(mapper.insertLegacyMaterialLink(anyString(), eq(primary.toString()))).thenReturn(1);
        when(mapper.insertMaterialLink(anyString(), anyString(), anyInt(), anyInt())).thenReturn(1);
        UUID id = adapter.create(command);
        verify(mapper).insertLegacyMaterialLink(id.toString(), primary.toString());
        verify(mapper).insertMaterialLink(id.toString(), primary.toString(), 1, 1);
        verify(mapper).insertMaterialLink(id.toString(), supplement.toString(), 2, 0);
        assertThat(adapter.getClass().getMethod("create", CreateCommand.class)
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isTrue();
    }
    @Test void missingParsedMaterialRaisesRollbackException() {
        demandInsert(1); when(mapper.insertLegacyMaterialLink(anyString(), anyString())).thenReturn(0);
        assertThatThrownBy(() -> adapter.create(command)).isInstanceOf(BusinessException.class);
    }
    @Test void failedDemandInsertCannotCreateOrphanLink() {
        demandInsert(0);
        assertThatThrownBy(() -> adapter.create(command)).isInstanceOf(BusinessException.class);
        verify(mapper, never()).insertLegacyMaterialLink(anyString(), anyString());
    }
    @Test void usedSourceIsRejectedAfterTakingDatabaseLock() {
        demandInsert(1);
        String used = command.sourceMaterialIds().stream().sorted().findFirst().orElseThrow().toString();
        when(mapper.countMaterialProjects(used)).thenReturn(1L);
        assertThatThrownBy(() -> adapter.create(command)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("已关联");
        var order = inOrder(mapper);
        order.verify(mapper).lockParsedMaterial(used);
        order.verify(mapper).countMaterialProjects(used);
        verify(mapper, never()).insertLegacyMaterialLink(anyString(), anyString());
    }
}
