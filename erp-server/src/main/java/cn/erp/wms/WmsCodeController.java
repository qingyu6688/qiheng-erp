package cn.erp.wms;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wms/scan")
class WmsCodeController {
    private static final Set<String> SCANNERS=Set.of("wms_task:view","wms_move:view","wms_pack:view",
            "goods_receipt:create","goods_receipt:update","stock_count:record_counts","inventory:view","label:print");
    private final AccessService access;
    private final WmsCodes codes;

    WmsCodeController(AccessService access,WmsCodes codes) { this.access=access;this.codes=codes; }

    @GetMapping("/resolve")
    ApiResponse<?> scan(HttpServletRequest request,@RequestParam String code,@RequestParam(required=false) Long warehouseId) {
        var actor=access.requireCompany(request,null);
        if (actor.permissions().stream().noneMatch(SCANNERS::contains))
            throw new ApiException(403,"FORBIDDEN","没有使用仓储扫码的权限");
        return ApiResponse.success(codes.scan(actor,warehouseId,code));
    }
}
