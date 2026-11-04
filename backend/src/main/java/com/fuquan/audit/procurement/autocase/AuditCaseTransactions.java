package com.fuquan.audit.procurement.autocase;

import com.fuquan.audit.common.application.OperationAuditService;
import com.fuquan.audit.security.domain.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class AuditCaseTransactions {
    private final AuditCaseRepository repository;
    private final OperationAuditService audit;
    public AuditCaseTransactions(AuditCaseRepository repository,OperationAuditService audit) {this.repository=repository;this.audit=audit;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void create(AuditCase value,CurrentUser user) {
        repository.insert(value);
        audit.success("AUTO_CASE_CREATED",user,"AUDIT_CASE",value.id().toString(),null,null,null,"创建独立文件审查案件");
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean save(AuditCase previous,AuditCase next,CurrentUser user) {
        if(!repository.replace(previous,next))return false;
        audit.success("AUTO_CASE_CHECKPOINT",user,"AUDIT_CASE",next.id().toString(),null,null,
                java.util.Map.of("status",next.status(),"version",next.version()),"保存自动审查进度与证据");
        return true;
    }
    /** Lock the current version before durable alarms, then commit alarms and the checkpoint together. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public AuditCase blacklist(AuditCase previous,java.util.List<AuditCase.Document> docs,AuditCase.Report report,
            CaseBlacklistReview reviewer,CurrentUser user,java.time.Instant now) {
        var locked=new AuditCase(previous.id(),previous.owner(),previous.operationKey(),previous.fingerprint(),"RUNNING",previous.version()+1,
                previous.leaseToken(),now.plusSeconds(1800),previous.attempts(),previous.createdAt(),docs,report);
        if(!repository.replace(previous,locked))throw new org.springframework.dao.ConcurrencyFailureException("case blacklist fence lost");
        var checked=report.review("BLACKLIST",reviewer.check(previous.id(),docs,user));
        var next=new AuditCase(locked.id(),locked.owner(),locked.operationKey(),locked.fingerprint(),"RUNNING",locked.version()+1,
                locked.leaseToken(),locked.leaseUntil(),locked.attempts(),locked.createdAt(),docs,checked);
        if(!repository.replace(locked,next))throw new org.springframework.dao.ConcurrencyFailureException("case blacklist checkpoint lost");
        audit.success("AUTO_CASE_CHECKPOINT",user,"AUDIT_CASE",next.id().toString(),null,null,
                java.util.Map.of("status",next.status(),"version",next.version()),"保存黑名单比对与通知");
        return next;
    }
}
