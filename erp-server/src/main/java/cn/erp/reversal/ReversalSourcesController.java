package cn.erp.reversal;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/document-reversal-sources")
class ReversalSourcesController {
    private final AccessService access;
    private final ReversalSourceQueries queries;
    ReversalSourcesController(AccessService access,ReversalSourceQueries queries) {this.access=access;this.queries=queries;}
    @GetMapping ApiResponse<?> list(HttpServletRequest request,@RequestParam String sourceType,@RequestParam(required=false) Long cursor,
        @RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="") String keyword) {
        return ApiResponse.success(queries.list(access.requireCompany(request,"reversal:create"),sourceType,cursor,limit,keyword));
    }
}
