package com.fuquan.audit.procurement.api;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.procurement.application.port.ProcurementNotificationPort;
import com.fuquan.audit.procurement.domain.ProcurementNotification;
import com.fuquan.audit.security.application.AuthContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notifications")
@PreAuthorize("isAuthenticated()")
public class ProcurementNotificationController {
    private final ProcurementNotificationPort port; private final AuthContext auth;
    private com.fuquan.audit.procurement.autocase.CaseBlacklistNotifications caseNotices;
    @org.springframework.beans.factory.annotation.Autowired
    public void setCaseNotices(com.fuquan.audit.procurement.autocase.CaseBlacklistNotifications value){caseNotices=value;}
    public ProcurementNotificationController(ProcurementNotificationPort port, AuthContext auth) { this.port=port; this.auth=auth; }
    @GetMapping public ApiResponse<List<NotificationResponse>> list(@RequestParam(defaultValue="false") boolean unreadOnly) {
        var values=new java.util.ArrayList<ProcurementNotification>();
        if(!personal())values.addAll(port.findByRecipient(userId(),unreadOnly));
        if(caseNotices!=null)values.addAll(caseNotices.list(userId(),unreadOnly));
        return ApiResponse.success(values.stream().sorted(java.util.Comparator.comparing(ProcurementNotification::createdTime).reversed()).map(NotificationResponse::from).toList());
    }
    @GetMapping("/unread-count") public ApiResponse<Integer> unreadCount() {
        return ApiResponse.success((personal()?0:port.findByRecipient(userId(), true).size())+(caseNotices==null?0:caseNotices.list(userId(),true).size()));
    }
    @PostMapping("/{id}/read") public ApiResponse<Void> read(@PathVariable UUID id) {
        var user=auth.currentUser().orElseThrow(()->new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
        if(caseNotices==null||!caseNotices.readIfOwned(id,user)) {
            if(personal())throw new BusinessException(BusinessErrorCode.NOT_FOUND);
            port.markRead(id, userId(), Instant.now());
        }
        return ApiResponse.success(null);
    }
    private String userId() { return auth.currentUser().orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED)).userId(); }
    private boolean personal(){return com.fuquan.audit.security.domain.PersonalUploadRoles.applies(auth.currentUser().orElse(null));}
    public record NotificationResponse(UUID id, UUID projectId, String projectName, String type, String title,
                                       String content, String operatorName, String opinion, boolean read,
                                       Instant createdTime) {
        static NotificationResponse from(ProcurementNotification n) { return new NotificationResponse(n.id(), n.projectId(),
                n.projectName(), n.type(), n.title(), n.content(), n.operatorName(), n.opinion(), n.read(), n.createdTime()); }
    }
}
