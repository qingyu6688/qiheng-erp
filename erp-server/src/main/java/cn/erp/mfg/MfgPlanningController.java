package cn.erp.mfg;

import cn.erp.identity.AccessService;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static cn.erp.mfg.MfgPlanningModels.*;

@RestController
@RequestMapping("/api/v1/mfg")
class MfgPlanningController {
    private static final String BOMS="mfg_bom";
    private static final String ORDERS="mfg_work_order";
    private static final String KEY="Idempotency-Key";
    private final AccessService access;
    private final MfgBomService boms;
    private final MfgWorkOrderService orders;
    private final MfgShortClose shortClose;

    MfgPlanningController(AccessService access,MfgBomService boms,MfgWorkOrderService orders,MfgShortClose shortClose) {
        this.access=access; this.boms=boms; this.orders=orders; this.shortClose=shortClose;
    }

    @GetMapping("/boms")
    ApiResponse<?> listBoms(HttpServletRequest request,@RequestParam(required=false) Long skuId,
                            @RequestParam(required=false) String status,@RequestParam(required=false) String keyword,
                            @RequestParam(required=false) Long cursor,@RequestParam(defaultValue="30") int limit) {
        return ApiResponse.success(boms.list(actor(request,BOMS,"view"),skuId,status,keyword,cursor,limit));
    }

    @GetMapping("/boms/{id}")
    ApiResponse<?> bom(HttpServletRequest request,@PathVariable long id) {
        return ApiResponse.success(boms.detail(actor(request,BOMS,"view"),id));
    }

    @GetMapping("/boms/{id}/explode")
    ApiResponse<?> explode(HttpServletRequest request,@PathVariable long id,@RequestParam String qty,
                            @RequestParam(required=false) LocalDate date,@RequestParam(required=false) Long warehouseId) {
        return ApiResponse.success(boms.explode(actor(request,BOMS,"view"),id,qty,date,warehouseId));
    }

    @PostMapping("/boms")
    ResponseEntity<?> createBom(HttpServletRequest request,@RequestHeader(value=KEY,required=false) String key,@RequestBody BomDraft input) {
        return ResponseEntity.status(201).body(ApiResponse.created(boms.create(actor(request,BOMS,"create"),key,input)));
    }

    @PutMapping("/boms/{id}")
    ApiResponse<?> updateBom(HttpServletRequest request,@PathVariable long id,@RequestBody BomDraft input) {
        return ApiResponse.success(boms.update(actor(request,BOMS,"update"),id,input));
    }

    @PostMapping("/boms/{id}/{action:submit|approve|disable}")
    ApiResponse<?> commandBom(HttpServletRequest request,@PathVariable long id,@PathVariable String action,
                              @RequestHeader(value=KEY,required=false) String key,@RequestBody Revision input) {
        return ApiResponse.success(boms.command(actor(request,BOMS,action),id,action,key,input));
    }

    @GetMapping("/work-orders")
    ApiResponse<?> listOrders(HttpServletRequest request,@RequestParam(required=false) Long warehouseId,
                              @RequestParam(required=false) String status,@RequestParam(required=false) String keyword,
                              @RequestParam(required=false) Long cursor,@RequestParam(defaultValue="30") int limit) {
        return ApiResponse.success(orders.list(actor(request,ORDERS,"view"),warehouseId,status,keyword,cursor,limit));
    }

    @GetMapping("/work-orders/{id}")
    ApiResponse<?> workOrder(HttpServletRequest request,@PathVariable long id) {
        return ApiResponse.success(orders.detail(actor(request,ORDERS,"view"),id));
    }

    @PostMapping("/work-orders")
    ResponseEntity<?> createOrder(HttpServletRequest request,@RequestHeader(value=KEY,required=false) String key,@RequestBody WorkOrderDraft input) {
        return ResponseEntity.status(201).body(ApiResponse.created(orders.create(actor(request,ORDERS,"create"),key,input)));
    }

    @PostMapping("/work-orders/from-sales-order")
    ResponseEntity<?> fromSalesOrder(HttpServletRequest request,@RequestHeader(value=KEY,required=false) String key,@RequestBody FromSalesOrder input) {
        return ResponseEntity.status(201).body(ApiResponse.created(orders.fromSalesOrder(actor(request,ORDERS,"create"),key,input)));
    }

    @PutMapping("/work-orders/{id}")
    ApiResponse<?> updateOrder(HttpServletRequest request,@PathVariable long id,@RequestBody WorkOrderDraft input) {
        return ApiResponse.success(orders.update(actor(request,ORDERS,"update"),id,input));
    }

    @PostMapping("/work-orders/{id}/{action:release|close|cancel}")
    ApiResponse<?> commandOrder(HttpServletRequest request,@PathVariable long id,@PathVariable String action,
                                @RequestHeader(value=KEY,required=false) String key,@RequestBody Revision input) {
        return ApiResponse.success(orders.command(actor(request,ORDERS,action),id,action,key,input));
    }

    @PostMapping("/work-orders/{id}/short-close")
    ApiResponse<?> requestShortClose(HttpServletRequest request,@PathVariable long id,
                                    @RequestHeader(value=KEY,required=false) String key,@RequestBody ShortCloseDraft input) {
        return ApiResponse.success(shortClose.request(actor(request,ORDERS,"short_close"),id,key,input));
    }

    @PostMapping("/work-orders/{id}/approve-short-close")
    ApiResponse<?> approveShortClose(HttpServletRequest request,@PathVariable long id,
                                    @RequestHeader(value=KEY,required=false) String key,@RequestBody Revision input) {
        return ApiResponse.success(shortClose.approve(actor(request,ORDERS,"approve_short_close"),id,key,input));
    }

    private CompanyAccess actor(HttpServletRequest request,String resource,String action) {
        var actor=access.requireCompany(request,resource+":"+action);
        if(!actor.hasPermission(resource+":view")) throw new ApiException(403,"FORBIDDEN","没有查看该生产资料的权限");
        return actor;
    }
}
