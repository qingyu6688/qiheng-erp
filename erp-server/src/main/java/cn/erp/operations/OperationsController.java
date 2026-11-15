package cn.erp.operations;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/operations")
class OperationsController {
    private final AccessService access;
    private final OperationsService service;
    OperationsController(AccessService access,OperationsService service) {this.access=access;this.service=service;}
    @GetMapping
    ApiResponse<?> status(HttpServletRequest request) {return ApiResponse.success(service.status(access.requireCompany(request,"admin:outbox")));}
}
