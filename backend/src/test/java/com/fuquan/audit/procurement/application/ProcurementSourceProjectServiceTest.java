package com.fuquan.audit.procurement.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.procurement.application.port.ProcurementProjectCommandPort;
import com.fuquan.audit.procurement.application.port.ProcurementSourceProjectPort;
import com.fuquan.audit.procurement.domain.ProcurementSourceProject;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProcurementSourceProjectServiceTest {

    private static final UUID SOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");
    private static final UUID SUPPLEMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000902");

    @Test
    void creationAllowsHumanCorrectionOfExtractedName() {
        var sourceService = sources(new Sources(true));
        var writes = new Writes();
        var service = new ProcurementProjectCommandService(writes, null, null, null, sourceService);
        var request = new ProcurementProjectCommandPort.CreateCommand(SOURCE_ID, "PR-1", "伪造名称",
                "信息中心", "货物", new BigDecimal("100.00"), "A1", "设备",
                "公开招标", null, "申请人", null);

        service.create(request);

        assertThat(writes.created.projectName()).isEqualTo("伪造名称");
        assertThat(writes.created.sourceProjectId()).isEqualTo(SOURCE_ID);
        assertThat(writes.created.sourceMaterialIds()).containsExactly(SOURCE_ID);
    }

    @Test
    void creationFailsClosedWhenSourceIsNotEligible() {
        var service = new ProcurementProjectCommandService(new Writes(), null, null, null,
                sources(new Sources(false)));
        var request = new ProcurementProjectCommandPort.CreateCommand(SOURCE_ID, "PR-1", null,
                "信息中心", "货物", new BigDecimal("100.00"), "A1", "设备",
                "公开招标", null, "申请人", null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(BusinessErrorCode.BUSINESS_VALIDATION_FAILED));
    }

    @Test
    void creationPreservesOnePrimaryAndSupplementaryMaterialOrder() {
        var writes = new Writes();
        var service = new ProcurementProjectCommandService(writes, null, null, null,
                sources(new MultipleSources(Set.of(SOURCE_ID, SUPPLEMENT_ID))));

        service.create(command(SOURCE_ID, List.of(SOURCE_ID, SUPPLEMENT_ID)));

        assertThat(writes.created.sourceProjectId()).isEqualTo(SOURCE_ID);
        assertThat(writes.created.sourceMaterialIds()).containsExactly(SOURCE_ID, SUPPLEMENT_ID);
    }

    @Test
    void creationSupportsMoreThanTenIndependentMaterials() {
        var ids = java.util.stream.IntStream.range(0, 12)
                .mapToObj(index -> new UUID(1L, index + 1L)).toList();
        var writes = new Writes();
        var service = new ProcurementProjectCommandService(writes, null, null, null,
                sources(new MultipleSources(Set.copyOf(ids))));

        service.create(command(ids.get(0), ids));

        assertThat(writes.created.sourceMaterialIds()).containsExactlyElementsOf(ids);
    }

    @Test
    void creationRejectsInvalidMaterialSetsBeforeWriting() {
        var writes = new Writes();
        var service = new ProcurementProjectCommandService(writes, null, null, null,
                sources(new MultipleSources(Set.of(SOURCE_ID, SUPPLEMENT_ID))));

        assertThatThrownBy(() -> service.create(command(SOURCE_ID, List.of(SOURCE_ID, SOURCE_ID))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.create(command(SOURCE_ID, List.of(SUPPLEMENT_ID))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.create(command(SOURCE_ID,
                java.util.stream.IntStream.range(0, 101)
                        .mapToObj(index -> new UUID(1L, index + 1L)).toList())))
                .isInstanceOf(BusinessException.class);
        assertThat(writes.created).isNull();
    }

    private static ProcurementProjectCommandPort.CreateCommand command(UUID primary, List<UUID> materials) {
        return new ProcurementProjectCommandPort.CreateCommand(primary, materials, "PR-1", "采购需求",
                "信息中心", "货物", new BigDecimal("100.00"), "A1", "设备",
                "公开招标", null, "申请人", null);
    }
    private static ProcurementSourceProjectService sources(ProcurementSourceProjectPort port) {
        return new ProcurementSourceProjectService(port, () -> Optional.of(
                new com.fuquan.audit.security.domain.CurrentUser("actor", "actor", "用户", Set.of("PROCUREMENT_APPLICANT"))));
    }

    private record Sources(boolean eligible) implements ProcurementSourceProjectPort {
        @Override
        public List<ProcurementSourceProject> findEligible(String actorId, String keyword, int limit) {
            return eligible ? List.of(source()) : List.of();
        }

        @Override
        public Optional<ProcurementSourceProject> findEligibleById(String actorId, UUID projectId) {
            return eligible && SOURCE_ID.equals(projectId) ? Optional.of(source()) : Optional.empty();
        }

        private static ProcurementSourceProject source() {
            return new ProcurementSourceProject(SOURCE_ID, "已解析来源项目", "SRC-1", "货物", Instant.EPOCH);
        }
    }

    private record MultipleSources(Set<UUID> eligibleIds) implements ProcurementSourceProjectPort {
        @Override
        public List<ProcurementSourceProject> findEligible(String actorId, String keyword, int limit) {
            return eligibleIds.stream().limit(limit).map(MultipleSources::source).toList();
        }

        @Override
        public Optional<ProcurementSourceProject> findEligibleById(String actorId, UUID projectId) {
            return eligibleIds.contains(projectId) ? Optional.of(source(projectId)) : Optional.empty();
        }

        private static ProcurementSourceProject source(UUID id) {
            return new ProcurementSourceProject(id, "已解析采购材料", "SRC-" + id, "货物", Instant.EPOCH);
        }
    }

    private static final class Writes implements ProcurementProjectCommandPort {
        private CreateCommand created;

        @Override
        public UUID create(CreateCommand command) {
            created = command;
            return UUID.randomUUID();
        }

        @Override
        public void updateStatus(UUID projectId, String status) {
        }
    }
}
