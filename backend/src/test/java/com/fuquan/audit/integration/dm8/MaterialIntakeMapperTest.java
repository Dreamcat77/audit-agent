package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.material.domain.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MaterialIntakeMapperTest {
    @Test void clobFieldsAndEvidenceRoundTripAndFencedCompletion() {
        var mapper = mock(MaterialIntakeMapper.class);
        var repo = new Dm8MaterialIntakeRepository(mapper, new ObjectMapper());
        var now = Instant.parse("2026-10-07T00:00:00Z");
        var fields = new MaterialIntakeFields("项目", null, null, null, new BigDecimal("120000.00"), null,
                null, null, null, null, Map.of("projectName", List.of(new MaterialIntakeFields.Evidence("项目", "p1", "名称：项目"))),
                List.of(), "model", "v1");
        var record = new MaterialIntake(UUID.randomUUID(), "actor", UUID.randomUUID().toString(), "采购.docx", "docx",
                100, "a".repeat(64), "b".repeat(64), "PARSED", "token", now, fields, "DOCX_TEXT", null, now, now);
        repo.insert(record);
        var captor = ArgumentCaptor.forClass(MaterialIntakePersistenceRow.class);
        verify(mapper).insert(captor.capture());
        when(mapper.find(record.id().toString())).thenReturn(captor.getValue());
        assertThat(repo.find(record.id())).contains(record);
        when(mapper.complete(any())).thenReturn(0);
        assertThat(repo.complete(record)).isFalse();
    }
    @Test void sqlPreservesHistoricalProjectForeignKeyAndFencesLeaseToken() throws Exception {
        String xml = Files.readString(Path.of("src/main/resources/mappers/dm8/MaterialIntakeMapper.xml"));
        assertThat(xml).contains("ACTOR_ID = #{actorId} AND OPERATION_KEY = #{key}",
                "STATUS = 'PROCESSING' AND LEASE_TOKEN = #{leaseToken}", "LEASE_UNTIL &lt;= #{now}", "jdbcType=CLOB");
        String ddl = Files.readString(Path.of("../db/dm8/V046__business_material_intake.sql"));
        assertThat(ddl).contains("UNIQUE (ACTOR_ID, OPERATION_KEY)", "DECIMAL(18,2)", "PROCUREMENT_MATERIAL_LINK", "FIELDS_JSON CLOB");
        String multi = Files.readString(Path.of("../db/dm8/V047__procurement_multi_material_link.sql"));
        assertThat(multi).contains("PROCUREMENT_DEMAND_MATERIAL", "PRIMARY KEY (DEMAND_ID, MATERIAL_ID)",
                "UNIQUE (MATERIAL_ID)", "UNIQUE (DEMAND_ID, MATERIAL_ORDER)",
                "IS_PRIMARY SMALLINT", "FROM PROCUREMENT_MATERIAL_LINK");
        assertThat(ddl).doesNotContain("DROP ", "UPDATE PROCUREMENT_DEMAND", "ALTER TABLE PROCUREMENT_DEMAND");
        String project = Files.readString(Path.of("src/main/resources/mappers/dm8/ProcurementProjectMapper.xml"));
        assertThat(project).contains("WHERE L.DEMAND_ID = PROCUREMENT_DEMAND.ID", "M.STATUS = 'PARSED'",
                "D.ID = #{id} AND D.DELETED = 0", "LISTAGG(L.MATERIAL_ID, ',')",
                "AS SOURCE_MATERIAL_IDS");
    }
}
