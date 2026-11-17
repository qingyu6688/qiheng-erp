package cn.erp.wms;

import cn.erp.identity.AccessService;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wms")
class MoveWorkController {
    private final AccessService access;
    private final WmsMoves moves;
    private final LocationRules rules;
    private final Replenishment replenishment;

    MoveWorkController(AccessService access,WmsMoves moves,LocationRules rules,Replenishment replenishment) {
        this.access=access;
        this.moves=moves;
        this.rules=rules;
        this.replenishment=replenishment;
    }

    @GetMapping("/moves")
    ApiResponse<?> moves(HttpServletRequest request,@RequestParam(required=false) Long warehouseId,
                         @RequestParam(required=false) String type,@RequestParam(required=false) String status,
                         @RequestParam(required=false) Long cursor,@RequestParam(defaultValue="30") int limit) {
        return ApiResponse.success(moves.page(access.requireCompany(request,"wms_move:view"),warehouseId,type,status,cursor,limit));
    }

    @GetMapping("/moves/{id}")
    ApiResponse<?> move(HttpServletRequest request,@PathVariable long id) {
        return ApiResponse.success(moves.detail(access.requireCompany(request,"wms_move:view"),id));
    }

    @PostMapping("/moves")
    ApiResponse<?> create(HttpServletRequest request,@RequestHeader(name="Idempotency-Key",required=false) String key,
                          @RequestBody WmsMoves.Draft draft) {
        var actor=write(request);
        return ApiResponse.success(moves.detail(actor,Long.parseLong(moves.create(actor,key,draft))));
    }

    @PutMapping("/moves/{id}")
    ApiResponse<?> edit(HttpServletRequest request,@PathVariable long id,@RequestHeader(name="Idempotency-Key",required=false) String key,
                        @RequestBody WmsMoves.Draft draft) {
        var actor=write(request);
        moves.edit(actor,id,key,draft);
        return ApiResponse.success(moves.detail(actor,id));
    }

    @DeleteMapping("/moves/{id}")
    ApiResponse<?> cancel(HttpServletRequest request,@PathVariable long id,@RequestHeader(name="Idempotency-Key",required=false) String key,
                          @RequestBody WmsScopes.Revision revision) {
        var actor=write(request);
        moves.cancel(actor,id,key,revision);
        return ApiResponse.success(moves.detail(actor,id));
    }

    @PostMapping("/moves/{id}/post")
    ApiResponse<?> post(HttpServletRequest request,@PathVariable long id,@RequestHeader(name="Idempotency-Key",required=false) String key,
                        @RequestBody WmsScopes.Revision revision) {
        var actor=write(request);
        moves.post(actor,id,key,revision,false);
        return ApiResponse.success(moves.detail(actor,id));
    }

    @PostMapping("/replenishments/{id}/post")
    ApiResponse<?> replenish(HttpServletRequest request,@PathVariable long id,@RequestHeader(name="Idempotency-Key",required=false) String key,
                             @RequestBody WmsScopes.Revision revision) {
        var actor=write(request);
        moves.post(actor,id,key,revision,true);
        return ApiResponse.success(moves.detail(actor,id));
    }

    @PostMapping("/replenishments/generate")
    ApiResponse<?> generate(HttpServletRequest request,@RequestHeader(name="Idempotency-Key",required=false) String key,
                            @RequestBody Replenishment.Warehouse input) {
        var actor=write(request);
        var generated=replenishment.generate(actor,input.warehouseId(),key);
        return ApiResponse.success(Map.of("items",generated.stream().map(id->moves.detail(actor,Long.parseLong(id))).toList()));
    }

    @GetMapping("/location-rules")
    ApiResponse<?> rules(HttpServletRequest request,@RequestParam long warehouseId) {
        return ApiResponse.success(Map.of("items",rules.list(access.requireCompany(request,"wms_task:configure"),warehouseId)));
    }

    @PutMapping("/location-rules")
    ApiResponse<?> saveRule(HttpServletRequest request,@RequestBody LocationRules.Input input) {
        var actor=access.requireCompany(request,"wms_task:configure");
        WmsScopes.permission(actor,"warehouse:update");
        return ApiResponse.success(rules.save(actor,input));
    }

    private CompanyAccess write(HttpServletRequest request) {
        var actor=access.requireCompany(request,"wms_move:manage");
        WmsScopes.permission(actor,"wms_move:view");
        return actor;
    }
}
