package cn.erp.identity;

import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import static cn.erp.identity.AdminModels.*;

@RestController
@RequestMapping("/api/v1")
class AdminController {
    private final AccessService access;
    private final OrganizationService organization;
    private final IdentityAdminService admin;
    private final AuditQueryService auditQuery;

    AdminController(AccessService access,OrganizationService organization,IdentityAdminService admin,AuditQueryService auditQuery) {
        this.access=access;this.organization=organization;this.admin=admin;this.auditQuery=auditQuery;
    }

    @GetMapping("/companies")
    ApiResponse<Page<CompanyView>> companies(HttpServletRequest req) {
        return ApiResponse.success(organization.companies(access.requireTenant(req,"company:view")));
    }

    @GetMapping("/companies/{id}")
    ApiResponse<CompanyView> company(HttpServletRequest req,@PathVariable long id) {
        return ApiResponse.success(organization.company(access.requireTenant(req,"company:view"),id,false));
    }

    @PostMapping("/companies")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<CompanyView> createCompany(HttpServletRequest req,@RequestHeader(value="Idempotency-Key",required=false) String key,
                                          @Valid @RequestBody CompanyInput body) {
        return ApiResponse.created(organization.create(access.requireTenant(req,"company:create"),key,body));
    }

    @PutMapping("/companies/{id}")
    ApiResponse<CompanyView> editCompany(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody CompanyInput body) {
        return ApiResponse.success(organization.edit(access.requireTenant(req,"company:update"),id,body));
    }

    @PostMapping("/companies/{id}/{action:enable|disable}")
    ApiResponse<CompanyView> companyStatus(HttpServletRequest req,@PathVariable long id,@PathVariable String action,
                                         @RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Version body) {
        return ApiResponse.success(organization.status(access.requireTenant(req,"company:update"),id,action,key,body));
    }

    @GetMapping("/companies/{id}/policy")
    ApiResponse<PolicyView> policy(HttpServletRequest req,@PathVariable long id) {
        return ApiResponse.success(organization.policy(access.requireTenant(req,"company_policy:view"),id));
    }

    @PutMapping("/companies/{id}/policy")
    ApiResponse<PolicyView> policy(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody PolicyInput body) {
        return ApiResponse.success(organization.policy(access.requireTenant(req,"company_policy:update"),id,body));
    }

    @GetMapping("/users")
    ApiResponse<Page<UserView>> users(HttpServletRequest req,@RequestParam(required=false) String keyword,
                                     @RequestParam(required=false) String cursor,@RequestParam(defaultValue="50") int limit) {
        return ApiResponse.success(admin.users(access.requireTenant(req,"user:view"),keyword,cursor,limit));
    }

    @GetMapping("/users/{id}")
    ApiResponse<UserView> user(HttpServletRequest req,@PathVariable long id) {
        return ApiResponse.success(admin.user(access.requireTenant(req,"user:view"),id,false));
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<UserView> createUser(HttpServletRequest req,@RequestHeader(value="Idempotency-Key",required=false) String key,
                                    @Valid @RequestBody UserInput body) {
        return ApiResponse.created(admin.createUser(access.requireTenant(req,"user:create"),key,body));
    }

    @PutMapping("/users/{id}")
    ApiResponse<UserView> editUser(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody UserEdit body) {
        return ApiResponse.success(admin.editUser(access.requireTenant(req,"user:update"),id,body));
    }

    @PostMapping("/users/{id}/{action:enable|disable|unlock}")
    ApiResponse<UserView> userStatus(HttpServletRequest req,@PathVariable long id,@PathVariable String action,
                                   @RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Version body) {
        return ApiResponse.success(admin.userCommand(access.requireTenant(req,"user:update"),id,action,key,body));
    }

    @PostMapping("/users/{id}/reset-password")
    ApiResponse<UserView> reset(HttpServletRequest req,@PathVariable long id,
                              @RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody ResetPassword body) {
        return ApiResponse.success(admin.resetPassword(access.requireTenant(req,"user:reset_password"),id,key,body));
    }

    @PutMapping("/users/{id}/roles")
    ApiResponse<UserView> roles(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody RoleAssignment body) {
        return ApiResponse.success(admin.assignRoles(access.requireTenant(req,"user:assign_role"),id,body));
    }

    @PutMapping("/users/{id}/data-scopes")
    ApiResponse<UserView> dataScopes(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody ScopeAssignment body) {
        return ApiResponse.success(admin.assignScopes(access.requireTenant(req,"user:assign_scope"),id,body));
    }

    @GetMapping("/roles")
    ApiResponse<Page<RoleView>> roles(HttpServletRequest req,@RequestParam(required=false) String keyword,
                                     @RequestParam(required=false) String cursor,@RequestParam(defaultValue="50") int limit) {
        return ApiResponse.success(admin.roles(access.requireTenant(req,"role:view"),keyword,cursor,limit));
    }

    @GetMapping("/roles/{id}")
    ApiResponse<RoleView> role(HttpServletRequest req,@PathVariable long id) {
        return ApiResponse.success(admin.role(access.requireTenant(req,"role:view"),id,false));
    }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<RoleView> createRole(HttpServletRequest req,@RequestHeader(value="Idempotency-Key",required=false) String key,
                                    @Valid @RequestBody RoleInput body) {
        return ApiResponse.created(admin.createRole(access.requireTenant(req,"role:create"),key,body));
    }

    @PutMapping("/roles/{id}")
    ApiResponse<RoleView> editRole(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody RoleInput body) {
        return ApiResponse.success(admin.editRole(access.requireTenant(req,"role:update"),id,body));
    }

    @PutMapping("/roles/{id}/permissions")
    ApiResponse<RoleView> rolePermissions(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody PermissionAssignment body) {
        return ApiResponse.success(admin.assignPermissions(access.requireTenant(req,"role:assign_permission"),id,body));
    }

    @GetMapping("/permissions")
    ApiResponse<List<Map<String,Object>>> permissions(HttpServletRequest req) {
        access.requireTenant(req,"role:view");
        return ApiResponse.success(admin.permissions());
    }

    @GetMapping("/audit-logs")
    ApiResponse<Page<Map<String,Object>>> audits(HttpServletRequest req,@RequestParam(required=false) String cursor,
            @RequestParam(defaultValue="100") int limit,@RequestParam(required=false) String action,
            @RequestParam(required=false) String entityType,@RequestParam(required=false) Long entityId,
            @RequestParam(required=false) Long operatorId,@RequestParam(required=false) Long companyId,
            @RequestParam(required=false) java.time.LocalDate dateFrom,@RequestParam(required=false) java.time.LocalDate dateTo,
            @RequestParam(required=false) String requestId) {
        return ApiResponse.success(auditQuery.list(access.requireTenant(req,"audit:view"),
                new AuditQueryService.Filter(action,entityType,entityId,operatorId,companyId,dateFrom,dateTo,requestId),cursor,limit));
    }
}
