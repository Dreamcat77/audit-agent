package com.fuquan.audit.security.api;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.security.application.*;
import com.fuquan.audit.security.domain.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/system/users")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class UserAdministrationController {
    private final UserAdministrationService service;
    private final AuthContext auth;
    public UserAdministrationController(UserAdministrationService service, AuthContext auth) { this.service=service;this.auth=auth; }
    @GetMapping public ApiResponse<UserAdministrationService.People> list(
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size) {
        return ApiResponse.success(service.list(page,size,operator()));
    }
    @PostMapping public ApiResponse<UserAdministrationService.Person> create(@RequestBody CreatePerson request) {
        return ApiResponse.success(service.create(request.username(),request.displayName(),request.password(),operator()));
    }
    @PutMapping("/{id}") public ApiResponse<UserAdministrationService.Person> update(@PathVariable String id,@RequestBody UpdatePerson request) {
        return ApiResponse.success(service.update(id,request.username(),request.displayName(),request.password(),operator()));
    }
    @PatchMapping("/{id}/status") public ApiResponse<UserAdministrationService.Person> setStatus(@PathVariable String id,@RequestBody StatusChange request) {
        return ApiResponse.success(service.setStatus(id,request.status(),operator()));
    }
    private CurrentUser operator() { return auth.currentUser().orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED)); }
    public record CreatePerson(String username,String displayName,String password) {
        @Override public String toString() { return "CreatePerson[REDACTED]"; }
    }
    public record UpdatePerson(String username,String displayName,String password) { @Override public String toString(){return "UpdatePerson[REDACTED]";} }
    public record StatusChange(String status) {}
}
