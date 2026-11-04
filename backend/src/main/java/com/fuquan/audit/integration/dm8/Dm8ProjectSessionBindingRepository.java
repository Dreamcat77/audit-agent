package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.contract.application.session.ProjectSessionBindingRepository;
import com.fuquan.audit.contract.application.port.ContractReviewConcurrencyException;
import com.fuquan.audit.contract.domain.ReviewSessionProjectFileBinding;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Profile({"dev29","customer","local-dev"})
public class Dm8ProjectSessionBindingRepository implements ProjectSessionBindingRepository {
    private final ProjectSessionBindingMapper mapper;
    public Dm8ProjectSessionBindingRepository(ProjectSessionBindingMapper mapper) { this.mapper=mapper; }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void insert(ReviewSessionProjectFileBinding value) {
        var row=new ProjectSessionBindingPersistenceRow();
        row.setId(value.id().toString());
        row.setSessionId(value.sessionId().toString());
        row.setProjectId(value.projectId().toString());
        row.setProjectFileId(value.projectFileId().toString());
        row.setFileSha256(value.fileSha256());
        row.setParseResultId(value.parseResultId().toString());
        row.setParseVersion(value.parseVersion());
        row.setCanonicalTextSha256(value.canonicalTextSha256());
        row.setEffectiveRole(value.effectiveRole().name());
        row.setRoleDecisionId(value.roleDecisionId()==null ? null : value.roleDecisionId().toString());
        row.setIdentityDecisionId(value.identityDecisionId().toString());
        row.setSlotKey(value.slotKey());
        row.setOrdinal(value.ordinal());
        row.setActiveFlag(value.activeFlag() ? 1 : 0);
        row.setBoundBy(value.boundBy());
        row.setBoundAt(LocalDateTime.ofInstant(value.boundAt(),ZoneOffset.UTC));
        row.setSelectionVersion(value.selectionVersion());
        row.setVersionNo(value.versionNo());
        if (mapper.insert(row)!=1) throw new ContractReviewConcurrencyException("Binding insert failed");
    }
    @Override @Transactional(readOnly=true)
    public List<ReviewSessionProjectFileBinding> findBySessionId(UUID sessionId) {
        return mapper.findBySessionId(sessionId.toString()).stream().map(row -> new ReviewSessionProjectFileBinding(
UUID.fromString(row.getId()),
                UUID.fromString(row.getSessionId()),
                UUID.fromString(row.getProjectId()),
                UUID.fromString(row.getProjectFileId()),
                row.getFileSha256(),
                UUID.fromString(row.getParseResultId()),
                row.getParseVersion(),
                row.getCanonicalTextSha256(),
                DocumentRole.valueOf(row.getEffectiveRole()),
                row.getRoleDecisionId()==null ? null : UUID.fromString(row.getRoleDecisionId()),
                UUID.fromString(row.getIdentityDecisionId()),
                row.getSlotKey(),
                row.getOrdinal(),
                row.getActiveFlag()==1,
                row.getBoundBy(),
                row.getBoundAt().toInstant(ZoneOffset.UTC),
                row.getSelectionVersion(),
                row.getVersionNo())).toList();
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void deactivateBySessionId(UUID sessionId) { mapper.deactivateBySessionId(sessionId.toString()); }
}
