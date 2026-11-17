package cn.erp.inventory;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import cn.erp.shared.ApiException;
import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class InventoryTraceController {
    private static final Set<String> PICK_PERMISSIONS=Set.of("inventory:view","goods_receipt:create","goods_receipt:update",
            "purchase_return:create","purchase_return:update","sales_return:create","sales_return:update","shipment:create","shipment:update",
            "stock_adjustment:create","stock_adjustment:update","stock_transfer:create","stock_transfer:update","stock_transfer:receive",
            "stock_count:create","stock_count:update","stock_count:record_counts","stock_misc_doc:create","stock_misc_doc:update");
    private final AccessService access;
    private final InventoryTraceQueries queries;

    InventoryTraceController(AccessService access,InventoryTraceQueries queries){
        this.access=access;
        this.queries=queries;
    }

    @GetMapping("/batches")
    ApiResponse<?> batches(HttpServletRequest request,@RequestParam(required=false) Long skuId,@RequestParam(required=false) Long warehouseId,
            @RequestParam(required=false) Integer expiringWithinDays,@RequestParam(defaultValue="") String keyword,
            @RequestParam(required=false) Long cursor,@RequestParam(defaultValue="50") int limit){
        return ApiResponse.success(queries.batches(access.requireCompany(request,"batch:view"),skuId,warehouseId,expiringWithinDays,keyword,cursor,limit));
    }

    @GetMapping("/batches/{id}/trace")
    ApiResponse<?> batch(HttpServletRequest request,@PathVariable long id,@RequestParam(required=false) Long cursor,
            @RequestParam(defaultValue="100") int limit){
        return ApiResponse.success(queries.batch(access.requireCompany(request,"batch:trace"),id,cursor,limit));
    }

    @GetMapping("/serials/{serialNo}/trace")
    ApiResponse<?> serial(HttpServletRequest request,@PathVariable String serialNo,@RequestParam long skuId,
            @RequestParam(required=false) Long cursor,@RequestParam(defaultValue="100") int limit){
        return ApiResponse.success(queries.serial(access.requireCompany(request,"serial:trace"),skuId,serialNo,cursor,limit));
    }

    @GetMapping("/serials/trace")
    ApiResponse<?> serialQuery(HttpServletRequest request,@RequestParam String serialNo,@RequestParam long skuId,
            @RequestParam(required=false) Long cursor,@RequestParam(defaultValue="100") int limit){
        return ApiResponse.success(queries.serial(access.requireCompany(request,"serial:trace"),skuId,serialNo,cursor,limit));
    }

    @GetMapping("/trace/customer")
    ApiResponse<?> customer(HttpServletRequest request,@RequestParam long partnerId,@RequestParam(required=false) Long skuId,
            @RequestParam(required=false) Long cursor,@RequestParam(defaultValue="100") int limit){
        return ApiResponse.success(queries.customer(access.requireCompany(request,"inventory_trace:customer"),partnerId,skuId,cursor,limit));
    }

    @GetMapping("/inventory-tracking/options")
    ApiResponse<?> options(HttpServletRequest request,@RequestParam long warehouseId,@RequestParam long skuId){
        var actor=access.requireCompany(request,null);
        if(actor.permissions().stream().noneMatch(PICK_PERMISSIONS::contains))throw new ApiException(403,"FORBIDDEN","没有查看库存选项的权限");
        return ApiResponse.success(queries.options(actor,warehouseId,skuId));
    }
}
