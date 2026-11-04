package com.fuquan.audit.common.api;

import com.fuquan.audit.common.application.port.*;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/audit/operations") @PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class OperationAuditController {
 private final OperationAuditQueryPort query; public OperationAuditController(OperationAuditQueryPort query){this.query=query;}
 @GetMapping public ApiResponse<List<OperationAuditEvent>> list(@RequestParam(required=false) String userId,
   @RequestParam(required=false) String action,@RequestParam(required=false) String resourceType,
   @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant from,
   @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant to){
   return ApiResponse.success(query.find(userId,action,resourceType,from,to)); }
}
