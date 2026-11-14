package cn.erp.masterdata;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
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

@RestController
@RequestMapping("/api/v1")
class WarehouseController {
    private final AccessService access;
    private final WarehouseService service;

    WarehouseController(AccessService access, WarehouseService service) {
        this.access = access;
        this.service = service;
    }

    @GetMapping("/warehouses")
    ApiResponse<WarehouseModels.Page> list(HttpServletRequest request,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "false") boolean withTotal) {
        return ApiResponse.success(service.list(access.requireCompany(request, "warehouse:view"),
                keyword, status, limit, cursor, withTotal));
    }

    @GetMapping("/warehouses/{id}")
    ApiResponse<WarehouseModels.View> get(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.success(service.get(access.requireCompany(request, "warehouse:view"), id));
    }

    @PostMapping("/warehouses")
    ResponseEntity<ApiResponse<WarehouseModels.View>> create(HttpServletRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody WarehouseModels.Create input) {
        var result = service.create(access.requireCompany(request, "warehouse:create"), key, input);
        return ResponseEntity.created(URI.create("/api/v1/warehouses/" + result.id()))
                .body(ApiResponse.created(result));
    }

    @PutMapping("/warehouses/{id}")
    ApiResponse<WarehouseModels.View> update(HttpServletRequest request, @PathVariable long id,
            @Valid @RequestBody WarehouseModels.Edit input) {
        return ApiResponse.success(service.edit(access.requireCompany(request, "warehouse:update"), id, input));
    }

    @PostMapping("/warehouses/{id}/{action:enable|disable}")
    ApiResponse<WarehouseModels.View> changeStatus(HttpServletRequest request, @PathVariable long id,
            @PathVariable String action, @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody WarehouseModels.Version input) {
        return ApiResponse.success(service.changeStatus(access.requireCompany(request, "warehouse:update"),
                id, action, key, input.version()));
    }

    @GetMapping("/command-results")
    ApiResponse<WarehouseModels.Result> result(HttpServletRequest request,
            @RequestParam String operation, @RequestParam String key) {
        String permission = switch (operation) {
            case "warehouse.create" -> "warehouse:create";
            case "warehouse.disable", "warehouse.enable" -> "warehouse:update";
            default -> throw new ApiException(404, "NOT_FOUND", "未找到该操作结果");
        };
        return ApiResponse.success(service.result(access.requireCompany(request, permission), operation, key));
    }
}
