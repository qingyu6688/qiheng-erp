package cn.erp.reconciliation;

import cn.erp.identity.*;
import cn.erp.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reconciliation-runs")
class ReconciliationController {
    private final AccessService access;
    private final ReconciliationService service;
    private final BusinessCommands commands;

    ReconciliationController(AccessService access,ReconciliationService service,BusinessCommands commands) {
        this.access=access;this.service=service;this.commands=commands;
    }

    private CompanyAccess scope(HttpServletRequest request,String permission) {
        var scope=access.requireCompany(request,permission);
        if(!scope.allWarehouses()) {throw new ApiException(403,"FORBIDDEN","公司级对账需要整家公司数据范围");}
        return scope;
    }

    @PostMapping
    ResponseEntity<ApiResponse<?>> request(HttpServletRequest req,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Input input) {
        var actor=scope(req,"reconciliation:run");
        @SuppressWarnings({"unchecked","rawtypes"})
        var result=commands.execute(actor,"reconciliation.run",key,input,202,()->service.request(actor,input.checkType(),input.periodId()),(Class)Map.class);
        return ResponseEntity.accepted().body(new ApiResponse<>(202,"任务已提交",result,RequestIds.current(),null,null));
    }

    @GetMapping
    ApiResponse<?> list(HttpServletRequest req,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="20") int limit) {
        return ApiResponse.success(service.list(scope(req,"reconciliation:view"),cursor,limit));
    }

    @GetMapping("/{id}")
    ApiResponse<?> get(HttpServletRequest req,@PathVariable long id) {
        return ApiResponse.success(service.get(scope(req,"reconciliation:view"),id));
    }

    @GetMapping("/{id}/diffs")
    ApiResponse<?> differences(HttpServletRequest req,@PathVariable long id,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="20") int limit) {
        return ApiResponse.success(service.differences(scope(req,"reconciliation:view"),id,cursor,limit));
    }

    record Input(@NotBlank(message="请选择对账类型") String checkType,@Positive(message="期间编号无效") Long periodId) {}
}
