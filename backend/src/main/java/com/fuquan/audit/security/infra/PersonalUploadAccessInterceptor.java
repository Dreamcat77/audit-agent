package com.fuquan.audit.security.infra;

import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.application.port.UploadOwnershipPort;
import com.fuquan.audit.security.domain.PersonalUploadRoles;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/** Extra resource checks for personal workspaces, including old resource URLs and mixed roles. */
@Component
public class PersonalUploadAccessInterceptor implements HandlerInterceptor {
    private final AuthContext auth;
    private final UploadOwnershipPort ownership;
    public PersonalUploadAccessInterceptor(AuthContext auth,UploadOwnershipPort ownership) {
        this.auth=auth;this.ownership=ownership;
    }
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
        var user=auth.currentUser().orElse(null);
        if(!PersonalUploadRoles.applies(user))return true;
        String route=Objects.toString(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE),"");
        if(under(route,"/api/blacklist"))route="/api/v1"+route.substring(4);
        @SuppressWarnings("unchecked") var variables=(Map<String,String>)request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        var vars=variables==null?Map.<String,String>of():variables;
        String actor=user.userId();
        requireAllowedModule(route, vars, user);
        // These endpoints scope task lists, counts and alarms in their query services.
        if(under(route,"/api/v1/contract-audit-tasks")
                ||under(route,"/api/v1/blacklist/alerts")
                ||under(route,"/api/v1/blacklist/entries")
                ||under(route,"/api/v1/blacklist/rules"))return true;
        if(vars.containsKey("projectId")) {
            UUID project=uuid(vars.get("projectId"));
            require(ownership.ownsProject(project,actor));
            if(vars.containsKey("contractId"))require(ownership.ownsContract(uuid(vars.get("contractId")),project,actor));
        }
        // These collections and child resources already apply persisted actor ownership in their services.
        if(under(route,"/api/v1/contract-analysis-cases")||under(route,"/api/v1/procurement/cases")
                ||under(route,"/api/v1/budget/material-uploads")||under(route,"/api/v1/budget/material-risk")
                ||under(route,"/api/v1/material-transfers")||under(route,"/api/v1/auth")
                ||under(route,"/api/v1/notifications")||route.equals("/api/v1/health"))return true;
        if(under(route,"/api/v1/projects")&&vars.containsKey("projectId"))return true;
        if(route.equals("/api/v1/projects")&&request.getMethod().equals("POST"))return true;
        if(route.equals("/api/v1/projects/creation-operations")&&request.getMethod().equals("GET"))return true;
        if(route.equals("/api/v1/contracts/{id}")&&request.getMethod().equals("GET")) {
            require(ownership.ownsContract(uuid(vars.get("id")),null,actor));return true;
        }
        if(under(route,"/api/v1/budget/reviews/{taskId}")||under(route,"/api/v1/budget/review-tasks/{taskId}")) {
            require(ownership.ownsBudgetTask(uuid(vars.get("taskId")),actor));return true;
        }
        if(Set.of("/api/v1/budget/validation/pending-confirm","/api/v1/budget/validation/confirm-logs").contains(route)) {
            require(ownership.ownsBudgetTask(uuid(request.getParameter("batchId")),actor));return true;
        }
        // The body-bound task is checked before writing in BudgetController.
        if(Set.of("/api/v1/budget/validation/confirm-row","/api/v1/budget/validation/confirm-exception").contains(route))return true;
        throw new BusinessException(BusinessErrorCode.FORBIDDEN,"当前账号仅可访问本人上传的材料及结果");
    }
    private static void requireAllowedModule(String route, Map<String,String> vars,
            com.fuquan.audit.security.domain.CurrentUser actor) {
        if (under(route,"/api/v1/auth") || under(route,"/api/v1/notifications") || route.equals("/api/v1/health")) return;
        if (under(route,"/api/v1/budget")) {
            requireModule(PersonalUploadRoles.allowsModule(actor, "budget"));
            return;
        }
        if (under(route,"/api/v1/procurement") || under(route,"/api/v1/blacklist")) {
            requireModule(PersonalUploadRoles.allowsModule(actor, "procurement"));
            return;
        }
        if (under(route,"/api/v1/projects") || under(route,"/api/v1/contracts")
                || under(route,"/api/v1/contract-analysis-cases") || under(route,"/api/v1/contract-audit-tasks")) {
            requireModule(PersonalUploadRoles.allowsModule(actor, "contract"));
            return;
        }
        if (under(route,"/api/v1/material-transfers")) {
            String kind=vars.get("kind");
            requireModule("budget".equals(kind) && PersonalUploadRoles.allowsModule(actor, "budget"));
            return;
        }
        throw new BusinessException(BusinessErrorCode.FORBIDDEN,"当前账号无权访问此业务模块");
    }
    private static void requireModule(boolean allowed) {
        if(!allowed)throw new BusinessException(BusinessErrorCode.FORBIDDEN,"当前账号无权访问此业务模块");
    }
    private static boolean under(String value,String root){return value.equals(root)||value.startsWith(root+"/");}
    private static UUID uuid(String value){try{return UUID.fromString(value);}catch(RuntimeException invalid){throw new BusinessException(BusinessErrorCode.NOT_FOUND);}}
    private static void require(boolean value){if(!value)throw new BusinessException(BusinessErrorCode.NOT_FOUND);}
}
