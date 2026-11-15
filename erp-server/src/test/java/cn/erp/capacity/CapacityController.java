package cn.erp.capacity;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/capacity")
@Profile("dev & load & !prod & !production")
class CapacityController {
    private final AccessService access;
    private final CapacityFixture fixture;
    private final CapacityWork work;
    private final CapacityDiagnostics diagnostics;
    CapacityController(AccessService access,CapacityFixture fixture,CapacityWork work,CapacityDiagnostics diagnostics){this.access=access;this.fixture=fixture;this.work=work;this.diagnostics=diagnostics;}
    @GetMapping("/manifest") ApiResponse<?> manifest(HttpServletRequest req,@RequestHeader("X-Load-Key") String key){
        fixture.guard(key);access.requireCompany(req,"warehouse:view");
        return ApiResponse.success(fixture.contexts().stream().map(context->Map.of("tenantCode",fixture.tenantCode(context.tenant()),"companyId",Long.toString(context.company()),"warehouseId",Long.toString(context.warehouse()),"hotWarehouseId",Long.toString(context.hotWarehouse()),"skuIds",context.skus().stream().map(Object::toString).toList(),"unitId",Long.toString(context.unit()),"customerId",Long.toString(context.customer()),"cashAccountId",Long.toString(context.cash()),"businessDate",java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString())).toList());
    }
    @PostMapping("/workflow") ApiResponse<?> workflow(HttpServletRequest req,@RequestHeader("X-Load-Key") String secret,@RequestHeader("Idempotency-Key") String key,@RequestBody Input input){fixture.guard(secret);return ApiResponse.success(work.workflow(access.requireCompany(req,"sales_order:create"),input.lines(),input.slot(),key));}
    @PostMapping("/reserve/{slot}") ApiResponse<?> reserve(HttpServletRequest req,@RequestHeader("X-Load-Key") String secret,@PathVariable int slot){fixture.guard(secret);return ApiResponse.success(work.reserve(access.requireCompany(req,"sales_order:reserve"),slot));}
    @PostMapping("/duplicate") ApiResponse<?> duplicate(HttpServletRequest req,@RequestHeader("X-Load-Key") String secret,@RequestHeader("Idempotency-Key") String key){fixture.guard(secret);return ApiResponse.success(work.duplicate(access.requireCompany(req,"stock_adjustment:post"),key));}
    @PostMapping("/mixed-inbound") ApiResponse<?> mixedInbound(HttpServletRequest req,@RequestHeader("X-Load-Key") String secret,@RequestHeader("Idempotency-Key") String key){fixture.guard(secret);return ApiResponse.success(work.mixedInbound(access.requireCompany(req,"stock_adjustment:post"),key));}
    @PostMapping("/refund/{slot}") ApiResponse<?> refund(HttpServletRequest req,@RequestHeader("X-Load-Key") String secret,@PathVariable int slot){fixture.guard(secret);return ApiResponse.success(work.refund(access.requireCompany(req,"refund_request:submit"),slot));}
    @PostMapping("/verify") ApiResponse<?> verify(HttpServletRequest req,@RequestHeader("X-Load-Key") String secret,@RequestParam(defaultValue="false") boolean finish){fixture.guard(secret);return ApiResponse.success(work.verify(access.requireCompany(req,"reconciliation:run"),finish));}
    @GetMapping("/diagnostics") ApiResponse<?> diagnostics(HttpServletRequest req,@RequestHeader("X-Load-Key") String secret){fixture.guard(secret);return ApiResponse.success(diagnostics.inspect(access.requireCompany(req,"reconciliation:run")));}
    record Input(int lines,int slot) {}
}
