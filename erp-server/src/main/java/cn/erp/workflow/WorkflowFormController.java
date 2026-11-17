package cn.erp.workflow;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;

@RestController
@RequestMapping("/api/v1/workflow")
class WorkflowFormController {
    private final AccessService access;
    private final WorkflowForms forms;

    WorkflowFormController(AccessService access,WorkflowForms forms) {
        this.access = access;
        this.forms = forms;
    }

    @GetMapping("/forms")
    ApiResponse<?> forms(HttpServletRequest request,@RequestParam(defaultValue="false") boolean manage) {
        return ApiResponse.success(forms.list(access.requireCompany(request,null),manage));
    }

    @GetMapping("/forms/models")
    ApiResponse<?> models(HttpServletRequest request) {
        return ApiResponse.success(forms.models(access.requireCompany(request,null)));
    }

    @PostMapping("/forms")
    ApiResponse<?> createForm(HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestBody WorkflowForms.FormInput input) {
        return ApiResponse.success(forms.save(access.requireCompany(request,null),null,key,input));
    }

    @PutMapping("/forms/{id}")
    ApiResponse<?> updateForm(HttpServletRequest request,@PathVariable long id,
            @RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody WorkflowForms.FormInput input) {
        return ApiResponse.success(forms.save(access.requireCompany(request,null),id,key,input));
    }

    @PostMapping("/requests")
    ApiResponse<?> create(HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestBody WorkflowForms.ApplicationInput input) {
        return ApiResponse.success(forms.create(access.requireCompany(request,null),key,input));
    }

    @GetMapping("/requests/{id}")
    ApiResponse<?> request(HttpServletRequest request,@PathVariable long id,@RequestParam(required=false) Long taskId) {
        return ApiResponse.success(forms.detail(access.requireCompany(request,null),id,taskId));
    }
}
