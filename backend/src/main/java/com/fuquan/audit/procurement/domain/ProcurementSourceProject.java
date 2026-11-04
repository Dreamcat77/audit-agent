package com.fuquan.audit.procurement.domain;

import com.fuquan.audit.common.material.domain.MaterialIntake;
import com.fuquan.audit.common.material.domain.MaterialIntakeFields;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Parsed procurement material, independent of contract Project identity. */
public record ProcurementSourceProject(
        UUID id,
        String name,
        String projectNo,
        String category,
        Instant updatedAt,
        String fileName,
        MaterialIntakeFields fields) {

    public ProcurementSourceProject(UUID id, String name, String projectNo, String category, Instant updatedAt) {
        this(id, name, projectNo, category, updatedAt, null, null);
    }

    public static ProcurementSourceProject from(MaterialIntake material) {
        var f = material.fields();
        return new ProcurementSourceProject(material.id(), f.projectName(), f.projectNo(),
                f.procurementType(), material.updatedAt(), material.fileName(), f);
    }

    public ProcurementSourceProject {
        Objects.requireNonNull(id, "id must not be null");
    }
}
