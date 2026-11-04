package com.fuquan.audit.procurement.autocase;

import com.fuquan.audit.blacklist.application.port.BlacklistAlertRepository;
import com.fuquan.audit.common.application.OperationAuditService;
import com.fuquan.audit.common.application.port.OperationAuditQueryPort;
import com.fuquan.audit.procurement.domain.ProcurementNotification;
import com.fuquan.audit.security.domain.CurrentUser;
import java.util.*;
import org.springframework.stereotype.Service;

/** The alarm and its recipient/read audit facts survive restart in DM8. */
@Service
public class CaseBlacklistNotifications {
    private final OperationAuditQueryPort queries;
    private final OperationAuditService audit;
    private final BlacklistAlertRepository alerts;
    public CaseBlacklistNotifications(OperationAuditQueryPort queries,OperationAuditService audit,BlacklistAlertRepository alerts) {
        this.queries=queries;this.audit=audit;this.alerts=alerts;
    }
    public List<ProcurementNotification> list(String user,boolean unreadOnly) {
        var read=new HashSet<String>();
        queries.find(user,"AUTO_CASE_BLACKLIST_NOTICE_READ","BLACKLIST_ALERT",null,null).forEach(e->read.add(e.resourceId()));
        var result=new LinkedHashMap<UUID,ProcurementNotification>();
        for(var event:queries.find(user,"AUTO_CASE_BLACKLIST_ALERT","BLACKLIST_ALERT",null,null)) {
            var alert=alerts.findById(UUID.fromString(event.resourceId())).orElse(null);
            if(alert==null||unreadOnly&&read.contains(event.resourceId()))continue;
            result.putIfAbsent(alert.id(),new ProcurementNotification(alert.id(),user,null,Objects.toString(event.resourceName(),"采购材料分析"),
                    "BLACKLIST_CASE","采购供应商黑名单提醒",alert.supplierName()+"："+alert.blacklistType()+"，"+alert.blacklistReason(),
                    "系统",null,read.contains(event.resourceId()),event.occurredAt(),null));
        }
        return List.copyOf(result.values());
    }
    public boolean readIfOwned(UUID id,CurrentUser user) {
        if(list(user.userId(),false).stream().noneMatch(n->n.id().equals(id)))return false;
        if(list(user.userId(),true).stream().anyMatch(n->n.id().equals(id)))
            audit.success("AUTO_CASE_BLACKLIST_NOTICE_READ",user,"BLACKLIST_ALERT",id.toString(),null,null,null,"阅读黑名单站内提醒；不改变报警处置状态");
        return true;
    }
}
