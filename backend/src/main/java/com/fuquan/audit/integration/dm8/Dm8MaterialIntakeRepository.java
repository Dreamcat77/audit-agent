package com.fuquan.audit.integration.dm8;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.material.application.port.MaterialIntakeRepository;
import com.fuquan.audit.common.material.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8MaterialIntakeRepository implements MaterialIntakeRepository {
    private final MaterialIntakeMapper mapper;
    private final ObjectMapper json;
    public Dm8MaterialIntakeRepository(MaterialIntakeMapper mapper, ObjectMapper json) {
        this.mapper = mapper; this.json = json;
    }
    @Override public Optional<MaterialIntake> find(UUID id) {
        return Optional.ofNullable(mapper.find(id.toString())).map(this::domain);
    }
    @Override public Optional<MaterialIntake> findOperation(String actor, String key) {
        return Optional.ofNullable(mapper.findOperation(actor, key)).map(this::domain);
    }
    @Override public List<MaterialIntake> eligible(String keyword, int limit) {
        return mapper.eligible(keyword, limit).stream().map(this::domain).toList();
    }
    @Override public List<MaterialIntake> recent(String actor, int limit) {
        return mapper.recent(actor, limit).stream().map(this::domain).toList();
    }
    @Override public List<MaterialIntake> pending(Instant now, int limit) {
        return mapper.pending(LocalDateTime.ofInstant(now, ZoneOffset.UTC), limit).stream().map(this::domain).toList();
    }
    @Override public boolean renew(UUID id, String token, Instant until) {
        return mapper.renew(id.toString(), token, LocalDateTime.ofInstant(until, ZoneOffset.UTC)) == 1;
    }
    @Override public void insert(MaterialIntake intake) { mapper.insert(row(intake)); }
    @Override public boolean claim(MaterialIntake intake, Instant now) {
        return mapper.claim(row(intake), LocalDateTime.ofInstant(now, ZoneOffset.UTC)) == 1;
    }
    @Override public boolean claimPending(MaterialIntake intake, Instant now) {
        return mapper.claimPending(row(intake), LocalDateTime.ofInstant(now, ZoneOffset.UTC)) == 1;
    }
    @Override public boolean complete(MaterialIntake intake) { return mapper.complete(row(intake)) == 1; }
    public List<MaterialIntake> timedOutForCleanup(int limit){return mapper.timedOutForCleanup(limit).stream().map(this::domain).toList();}
    public void cleaned(UUID id,String reason){mapper.cleaned(id.toString(),reason);}
    MaterialIntake domain(MaterialIntakePersistenceRow r) {
        try {
            return new MaterialIntake(UUID.fromString(r.getId()), r.getActorId(), r.getOperationKey(),
                    r.getFileName(), r.getMediaType(), r.getFileSize(), r.getSha256(), r.getStorageKey(), r.getStatus(),
                    r.getLeaseToken(), r.getLeaseUntil().toInstant(ZoneOffset.UTC), r.getFieldsJson() == null ? null
                    : json.readValue(r.getFieldsJson(), MaterialIntakeFields.class), r.getParseMethod(),
                    r.getErrorCode(), r.getCreatedAt().toInstant(ZoneOffset.UTC), r.getUpdatedAt().toInstant(ZoneOffset.UTC));
        } catch (JsonProcessingException e) { throw new IllegalStateException("invalid persisted procurement fields", e); }
    }
    private MaterialIntakePersistenceRow row(MaterialIntake r) {
        var row = new MaterialIntakePersistenceRow();
        row.setId(r.id().toString()); row.setActorId(r.actorId()); row.setOperationKey(r.operationKey());
        row.setFileName(r.fileName()); row.setMediaType(r.mediaType()); row.setFileSize(r.fileSize());
        row.setSha256(r.sha256()); row.setStorageKey(r.storageKey()); row.setStatus(r.status());
        row.setLeaseToken(r.leaseToken()); row.setLeaseUntil(LocalDateTime.ofInstant(r.leaseUntil(), ZoneOffset.UTC));
        row.setParseMethod(r.parseMethod()); row.setErrorCode(r.errorCode());
        row.setCreatedAt(LocalDateTime.ofInstant(r.createdAt(), ZoneOffset.UTC));
        row.setUpdatedAt(LocalDateTime.ofInstant(r.updatedAt(), ZoneOffset.UTC));
        if (r.fields() != null) {
            var f = r.fields();
            try { row.setFieldsJson(json.writeValueAsString(f)); }
            catch (JsonProcessingException e) { throw new IllegalStateException("cannot serialize procurement fields", e); }
            row.setProjectName(f.projectName()); row.setProjectNo(f.projectNo()); row.setDepartmentName(f.departmentName());
            row.setProcurementType(f.procurementType()); row.setBudgetAmount(f.budgetAmount());
            row.setSubjectCode(f.subjectCode()); row.setSubjectName(f.subjectName()); row.setProcurementMethod(f.procurementMethod());
            row.setRequirementDescription(f.requirementDescription()); row.setApplicantName(f.applicantName());
        }
        return row;
    }
}
