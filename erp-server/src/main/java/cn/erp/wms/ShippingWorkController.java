package cn.erp.wms;

import cn.erp.identity.AccessService;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wms")
class ShippingWorkController {
    private final AccessService access;
    private final PickTasks picks;
    private final Waves waves;
    private final Packing packing;

    ShippingWorkController(AccessService access,PickTasks picks,Waves waves,Packing packing) {
        this.access = access;
        this.picks = picks;
        this.waves = waves;
        this.packing = packing;
    }

    @GetMapping("/picks")
    ApiResponse<?> picks(HttpServletRequest request,@RequestParam(required=false) Long warehouseId,
                         @RequestParam(required=false) String status,@RequestParam(required=false) Long cursor,
                         @RequestParam(defaultValue="30") int limit) {
        return ApiResponse.success(picks.page(access.requireCompany(request,"wms_task:view"),warehouseId,status,cursor,limit));
    }

    @GetMapping("/picks/{id}")
    ApiResponse<?> pick(HttpServletRequest request,@PathVariable long id) {
        return ApiResponse.success(picks.detail(access.requireCompany(request,"wms_task:view"),id));
    }

    @PostMapping("/picks/{id}/claim")
    ApiResponse<?> pickClaim(HttpServletRequest request,@PathVariable long id,
                             @RequestHeader(name="Idempotency-Key",required=false) String key,@RequestBody PickTasks.Revision input) {
        picks.claim(operator(request,"wms_task:execute","wms_task:view"),id,key,input);
        return changed(id);
    }

    @PostMapping("/picks/{id}/lines/{lineId}/confirm")
    ApiResponse<?> pickScan(HttpServletRequest request,@PathVariable long id,@PathVariable long lineId,
                            @RequestHeader(name="Idempotency-Key",required=false) String key,@RequestBody PickTasks.Scan input) {
        picks.confirm(operator(request,"wms_task:execute","wms_task:view"),id,lineId,key,input);
        return changed(id);
    }

    @PostMapping("/picks/{id}/finish")
    ApiResponse<?> pickFinish(HttpServletRequest request,@PathVariable long id,
                              @RequestHeader(name="Idempotency-Key",required=false) String key,@RequestBody PickTasks.Revision input) {
        picks.finish(operator(request,"wms_task:execute","wms_task:view"),id,key,input);
        return changed(id);
    }

    @GetMapping("/waves")
    ApiResponse<?> waves(HttpServletRequest request,@RequestParam(required=false) Long warehouseId,
                         @RequestParam(required=false) Long cursor,@RequestParam(defaultValue="30") int limit) {
        return ApiResponse.success(waves.page(operator(request,"wms_wave:view","wms_task:view"),warehouseId,cursor,limit));
    }

    @GetMapping("/waves/{id}")
    ApiResponse<?> wave(HttpServletRequest request,@PathVariable long id) {
        return ApiResponse.success(waves.detail(operator(request,"wms_wave:view","wms_task:view"),id));
    }

    @PostMapping("/waves")
    ApiResponse<?> createWave(HttpServletRequest request,@RequestHeader(name="Idempotency-Key",required=false) String key,
                               @RequestBody Waves.Create input) {
        return ApiResponse.success(waves.create(operator(request,"wms_wave:manage","wms_wave:view","wms_task:view"),key,input));
    }

    @PostMapping("/waves/{id}/claim")
    ApiResponse<?> claimWave(HttpServletRequest request,@PathVariable long id,
                             @RequestHeader(name="Idempotency-Key",required=false) String key,@RequestBody Waves.Revision input) {
        waves.claim(operator(request,"wms_wave:manage","wms_wave:view","wms_task:view","wms_task:execute"),id,key,input);
        return changed(id);
    }

    @GetMapping("/packs")
    ApiResponse<?> packs(HttpServletRequest request,@RequestParam(required=false) Long shipmentId,
                         @RequestParam(required=false) Long warehouseId,
                         @RequestParam(required=false) Long cursor,@RequestParam(defaultValue="30") int limit) {
        return ApiResponse.success(packing.page(access.requireCompany(request,"wms_pack:view"),shipmentId,warehouseId,cursor,limit));
    }

    @GetMapping("/packs/{id}")
    ApiResponse<?> pack(HttpServletRequest request,@PathVariable long id) {
        return ApiResponse.success(packing.detail(access.requireCompany(request,"wms_pack:view"),id));
    }

    @PostMapping("/packs")
    ApiResponse<?> createPack(HttpServletRequest request,@RequestHeader(name="Idempotency-Key",required=false) String key,
                               @RequestBody Packing.Create input) {
        return ApiResponse.success(packing.create(operator(request,"wms_pack:create","wms_pack:view","wms_task:view"),key,input));
    }

    private CompanyAccess operator(HttpServletRequest request,String permission,String... related) {
        var actor = access.requireCompany(request,permission);
        for (String extra : related)
            if (!actor.hasPermission(extra)) throw new ApiException(403,"FORBIDDEN","当前账户缺少关联仓储作业权限");
        return actor;
    }

    private ApiResponse<?> changed(long id) { return ApiResponse.success(Map.of("id",Long.toString(id))); }
}
