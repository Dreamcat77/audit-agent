package com.fuquan.audit.procurement.autocase;

import com.fuquan.audit.common.application.OperationAuditService;
import com.fuquan.audit.common.application.port.OperationAuditQueryPort;
import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.security.domain.CurrentUser;
import java.util.*;
import org.springframework.stereotype.Service;

/** Acknowledging a warning records the reader; it never resolves or suppresses a risk. */
@Service
public class CaseFindingAcknowledgments {
    private final AuditCaseService cases;
    private final OperationAuditQueryPort queries;
    private final OperationAuditService audit;
    public CaseFindingAcknowledgments(AuditCaseService cases,OperationAuditQueryPort queries,OperationAuditService audit) {
        this.cases=cases;this.queries=queries;this.audit=audit;
    }
    public static String identity(AuditCase.ReviewFinding finding) {
        return AuditCaseService.hash(finding.code()+"\n"+finding.title()+"\n"+finding.explanation()+"\n"+
                finding.evidence().stream().map(e->e.documentId()+":"+e.id()).sorted().toList());
    }
    public List<String> list(UUID id,CurrentUser user) {
        cases.owned(id,user);
        return queries.find(user.userId(),"AUTO_CASE_FINDING_CONFIRMED","AUDIT_CASE",null,null).stream()
                .filter(e->id.toString().equals(e.resourceId())).map(e->e.resourceName()).filter(Objects::nonNull).distinct().toList();
    }
    public void confirm(UUID id,String key,CurrentUser user) {
        var value=cases.owned(id,user);
        if(value.report()==null||value.report().reviews().values().stream().flatMap(r->r.findings().stream())
                .noneMatch(f->identity(f).equals(key)))throw new BusinessException(BusinessErrorCode.NOT_FOUND);
        if(list(id,user).contains(key))return;
        audit.success("AUTO_CASE_FINDING_CONFIRMED",user,"AUDIT_CASE",id.toString(),key,null,Map.of("findingId",key),"确认已阅采购审查问题；保留原始风险结论");
    }
}
