package cn.erp.org;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import static cn.erp.org.OrgModels.*;

@RestController
@RequestMapping("/api/v1")
class OrgController {
    private final RoleDataScopeService roleScopes;
    private final AccessService access;
    private final DepartmentService departments;
    private final PostService posts;
    private final UserOrganizationService users;
    OrgController(AccessService access,DepartmentService departments,PostService posts,UserOrganizationService users,RoleDataScopeService roleScopes) {this.access=access;this.departments=departments;this.posts=posts;this.users=users;this.roleScopes=roleScopes;}
    @GetMapping("/org/roles/{id}/data-scope")
    ApiResponse<?> roleScope(HttpServletRequest request,@PathVariable long id) {return ApiResponse.success(roleScopes.get(access.requireCompany(request,null),id));}
    @PutMapping("/org/roles/{id}/data-scope")
    ApiResponse<?> saveRoleScope(HttpServletRequest request,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody RoleDataScopeService.Input input) {
        return ApiResponse.success(roleScopes.save(access.requireCompany(request,"role:assign_permission"),id,key,input));
    }
    @GetMapping("/org/departments/tree")
    ApiResponse<?> tree(HttpServletRequest request) {return ApiResponse.success(departments.tree(access.requireCompany(request,"org_department:view")));}
    @GetMapping("/org/departments/{id}")
    ApiResponse<?> department(HttpServletRequest request,@PathVariable long id) {return ApiResponse.success(departments.get(access.requireCompany(request,"org_department:view"),id));}
    @PostMapping("/org/departments") @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<?> createDepartment(HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody DepartmentInput input) {
        return ApiResponse.created(departments.create(access.requireCompany(request,"org_department:create"),key,input));
    }
    @PutMapping("/org/departments/{id}")
    ApiResponse<?> editDepartment(HttpServletRequest request,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody DepartmentInput input) {
        return ApiResponse.success(departments.edit(access.requireCompany(request,"org_department:update"),id,key,input));
    }
    @PostMapping("/org/departments/{id}/move")
    ApiResponse<?> move(HttpServletRequest request,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Move input) {
        return ApiResponse.success(departments.move(access.requireCompany(request,"org_department:update"),id,key,input));
    }
    @PostMapping("/org/departments/{id}/{action:enable|disable}")
    ApiResponse<?> departmentStatus(HttpServletRequest request,@PathVariable long id,@PathVariable String action,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Version input) {
        return ApiResponse.success(departments.status(access.requireCompany(request,"org_department:update"),id,action,key,input));
    }
    @GetMapping("/org/departments/{id}/members")
    ApiResponse<?> members(HttpServletRequest request,@PathVariable long id,@RequestParam(required=false) String cursor,
            @RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="false") boolean includeChildren) {
        return ApiResponse.success(departments.members(access.requireCompany(request,"org_department:view"),id,cursor,limit,includeChildren));
    }
    @GetMapping("/org/posts")
    ApiResponse<?> posts(HttpServletRequest request,@RequestParam(required=false) String keyword,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="50") int limit) {
        return ApiResponse.success(posts.list(access.requireTenant(request,"org_post:view"),keyword,cursor,limit));
    }
    @GetMapping("/org/posts/{id}")
    ApiResponse<?> post(HttpServletRequest request,@PathVariable long id) {return ApiResponse.success(posts.get(access.requireTenant(request,"org_post:view"),id));}
    @PostMapping("/org/posts") @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<?> createPost(HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody PostInput input) {
        return ApiResponse.created(posts.create(access.requireTenant(request,"org_post:create"),key,input));
    }
    @PutMapping("/org/posts/{id}")
    ApiResponse<?> editPost(HttpServletRequest request,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody PostInput input) {
        return ApiResponse.success(posts.edit(access.requireTenant(request,"org_post:update"),id,key,input));
    }
    @PostMapping("/org/posts/{id}/{action:enable|disable}")
    ApiResponse<?> postStatus(HttpServletRequest request,@PathVariable long id,@PathVariable String action,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Version input) {
        return ApiResponse.success(posts.status(access.requireTenant(request,"org_post:update"),id,action,key,input));
    }
    @GetMapping("/org/company-users")
    ApiResponse<?> candidates(HttpServletRequest request,@RequestParam(required=false) String keyword,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="50") int limit) {
        return ApiResponse.success(users.candidates(access.requireCompany(request,null),keyword,cursor,limit));
    }
    @GetMapping("/org/assignment-options")
    ApiResponse<?> options(HttpServletRequest request) {return ApiResponse.success(users.options(access.requireCompany(request,"user:update")));}
    @GetMapping("/users/{id}/organization")
    ApiResponse<?> organization(HttpServletRequest request,@PathVariable long id) {return ApiResponse.success(users.get(access.requireCompany(request,null),id));}
    @PutMapping("/users/{id}/organization")
    ApiResponse<?> assign(HttpServletRequest request,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Assignment input) {
        return ApiResponse.success(users.assign(access.requireCompany(request,"user:update"),id,key,input));
    }
}
