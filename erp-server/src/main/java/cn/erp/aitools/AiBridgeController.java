package cn.erp.aitools;

import cn.erp.masterdata.WarehouseService;
import cn.erp.shared.ApiResponse;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequestMapping(AiBridgeController.PREFIX)
class AiBridgeController {
    static final String PREFIX = "/api/v1/ai-tools";

    private final AiBridgeSignature signature;
    private final AiBridgeService bridge;

    AiBridgeController(AiBridgeSignature signature, AiBridgeService bridge) {
        this.signature = signature;
        this.bridge = bridge;
    }

    // 写入类 AI 请求通过 POST 继承 ERP 的登录态与 CSRF 检查，返回的仍是只读授权资料。
    @RequestMapping(path = "/context", method = {RequestMethod.GET, RequestMethod.POST})
    ApiResponse<AiBridgePrincipalRepository.Principal> context(HttpServletRequest request) {
        signature.verify(request, PREFIX + "/context", true);
        return ApiResponse.success(bridge.context(request));
    }

    @GetMapping("/warehouses")
    ApiResponse<WarehouseService.ActiveSelections> warehouses(HttpServletRequest request) {
        signature.verify(request, PREFIX + "/warehouses", false);
        return ApiResponse.success(bridge.listWarehouses(request));
    }

    @PostMapping(path="/query/{code}",consumes="application/json")
    ApiResponse<?> query(HttpServletRequest request,@PathVariable("code") String code) {
        return ApiResponse.success(bridge.query(request,code,signature.verifyQuery(request,PREFIX+"/query/"+code)));
    }

    @GetMapping("/scope/{code}")
    ApiResponse<?> scope(HttpServletRequest request,@PathVariable("code") String code) {
        signature.verify(request,PREFIX+"/scope/"+code,false);
        return ApiResponse.success(bridge.scope(request,code));
    }

    @PostMapping(path="/scope/document_explain",consumes="application/json")
    ApiResponse<?> documentScope(HttpServletRequest request) {
        return ApiResponse.success(bridge.documentScope(request,signature.verifyQuery(request,PREFIX+"/scope/document_explain")));
    }
}
