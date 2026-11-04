package com.fuquan.audit.procurement.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.common.material.application.MaterialIntakeService;
import com.fuquan.audit.common.material.domain.MaterialIntake;
import com.fuquan.audit.procurement.domain.ProcurementProject;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProcurementIntakeReviewServiceTest {
    final UUID projectId = UUID.randomUUID(), primary = UUID.randomUUID(), extra = UUID.randomUUID();
    final CurrentUser user = new CurrentUser("owner", "owner", "测试人员", Set.of("AUDITOR"));
    final ProcurementProjectQueryService projects = mock(ProcurementProjectQueryService.class);
    final MaterialIntakeService intakes = mock(MaterialIntakeService.class);
    final ProcurementMaterialBatchService batches = mock(ProcurementMaterialBatchService.class);
    final ProcurementIntakeReviewService service = new ProcurementIntakeReviewService(projects, intakes, batches);

    void scope(List<UUID> ids) {
        when(projects.requireById(projectId)).thenReturn(new ProcurementProject(projectId, "TEST", "仿真采购", "TEST",
            "测试", null, null, "DRAFT", Instant.EPOCH, null, null, null, null, null, null, primary, ids));
    }
    MaterialIntake intake(UUID id, String status) {
        return new MaterialIntake(id, "owner", id.toString(), "材料.docx", null, 100, "hash", "key", status,
            null, Instant.EPOCH, null, null, null, Instant.EPOCH, Instant.EPOCH);
    }
    @Test void reusesEntireDurableScopeAndOnlyThenAnalyzes() {
        scope(List.of(primary, extra));
        var a = intake(primary, "PARSED"); var b = intake(extra, "PARSED");
        when(intakes.findOwned(primary, user)).thenReturn(a); when(intakes.findOwned(extra, user)).thenReturn(b);
        var ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(batches.reuseIntakes(projectId, List.of(a,b), "owner")).thenReturn(ids);
        service.analyze(projectId, user);
        var ordered = inOrder(intakes, batches);
        ordered.verify(intakes).findOwned(primary, user); ordered.verify(intakes).findOwned(extra, user);
        ordered.verify(batches).reuseIntakes(projectId, List.of(a,b), "owner");
        ordered.verify(batches).analyzeSaved(projectId, ids, "owner", true);
    }
    @Test void inaccessibleLastMaterialPreventsAllReviewWritesAndAi() {
        scope(List.of(primary,extra));
        when(intakes.findOwned(primary,user)).thenReturn(intake(primary,"PARSED"));
        when(intakes.findOwned(extra,user)).thenThrow(new BusinessException(BusinessErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.analyze(projectId,user)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(batches);
    }
    @Test void queuedUnknownAndFailedMaterialsCannotEnterReview() {
        scope(List.of(primary,extra));
        when(intakes.findOwned(primary,user)).thenReturn(intake(primary,"PARSED"));
        for (var status: List.of("QUEUED","PROCESSING","FAILED","UNKNOWN")) {
            when(intakes.findOwned(extra,user)).thenReturn(intake(extra,status));
            assertThatThrownBy(() -> service.analyze(projectId,user)).hasMessageContaining("尚未完成");
        }
        verifyNoInteractions(batches);
    }
    @Test void invalidDurableScopeDoesNotReadOrMutateMaterials() {
        for (var ids: List.of(List.<UUID>of(), List.of(extra), List.of(primary,primary))) {
            scope(ids);
            assertThatThrownBy(() -> service.analyze(projectId,user)).hasMessageContaining("完整");
        }
        verifyNoInteractions(intakes,batches);
    }
}
