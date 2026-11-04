package com.fuquan.audit.common.material.application;

import com.fuquan.audit.common.application.OperationAuditService;
import com.fuquan.audit.common.material.application.port.MaterialIntakeRepository;
import com.fuquan.audit.common.material.domain.MaterialIntake;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Short durable transactions; document/OCR work must remain outside these methods. */
@Service
public class MaterialIntakeTransactions {
    private final MaterialIntakeRepository repository;
    private final OperationAuditService audit;
    public MaterialIntakeTransactions(MaterialIntakeRepository repository, OperationAuditService audit) {
        this.repository = repository; this.audit = audit;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void start(MaterialIntake record, CurrentUser user) {
        repository.insert(record);
        audit.success("PROCUREMENT_MATERIAL_START", user, "PROCUREMENT_MATERIAL", record.id().toString(),
                record.fileName(), null, null, "上传采购材料并开始解析");
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(MaterialIntake record, Instant now, CurrentUser user) {
        boolean claimed = repository.claim(record, now);
        if (claimed) audit.success("PROCUREMENT_MATERIAL_RETRY", user, "PROCUREMENT_MATERIAL", record.id().toString(),
                record.fileName(), null, null, "继续解析已保存的采购材料");
        return claimed;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean complete(MaterialIntake record, CurrentUser user) {
        boolean changed = repository.complete(record);
        if (changed) audit.success("PROCUREMENT_MATERIAL_" + record.status(), user, "PROCUREMENT_MATERIAL", record.id().toString(),
                record.fileName(), null, java.util.Map.of("status", record.status(), "errorCode",
                        record.errorCode() == null ? "" : record.errorCode()), "保存采购材料解析状态与字段");
        return changed;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean enqueue(MaterialIntake record, CurrentUser user) {
        boolean changed = repository.complete(record);
        if (changed) audit.success("PROCUREMENT_MATERIAL_QUEUED", user, "PROCUREMENT_MATERIAL", record.id().toString(),
                record.fileName(), null, null, "材料已保存，等待后台解析");
        return changed;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimPending(MaterialIntake record, Instant now, CurrentUser user) {
        boolean claimed=repository.claimPending(record,now);
        if(claimed) audit.success("PROCUREMENT_MATERIAL_BACKGROUND_CLAIM",user,"PROCUREMENT_MATERIAL",record.id().toString(),
                record.fileName(),null,null,"后台取得采购材料解析租约");
        return claimed;
    }
}
