package cn.erp.quality;

import cn.erp.identity.AccessService;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import static cn.erp.quality.QualityModels.*;

@RestController
@RequestMapping("/api/v1/qc")
class QualityController {
    private final AccessService access;
    private final QualitySettings settings;
    private final QualityInspections inspections;

    QualityController(AccessService access,QualitySettings settings,QualityInspections inspections){
        this.access=access;
        this.settings=settings;
        this.inspections=inspections;
    }

    @GetMapping("/items")
    ApiResponse<?> items(HttpServletRequest request,@RequestParam(defaultValue="9223372036854775807") long cursor,
                         @RequestParam(defaultValue="50") int limit) {
        return ApiResponse.success(settings.items(viewSettings(request),cursor,limit));
    }

    @PostMapping("/items")
    ApiResponse<?> createItem(HttpServletRequest request,@RequestBody Item input){return saveItem(request,null,input);}

    @PutMapping("/items/{id}")
    ApiResponse<?> updateItem(HttpServletRequest request,@PathVariable long id,@RequestBody Item input){return saveItem(request,id,input);}

    @GetMapping("/templates")
    ApiResponse<?> templates(HttpServletRequest request,@RequestParam(defaultValue="9223372036854775807") long cursor,
                             @RequestParam(defaultValue="50") int limit) {
        return ApiResponse.success(settings.templates(viewSettings(request),cursor,limit));
    }

    @PostMapping("/templates")
    ApiResponse<?> createTemplate(HttpServletRequest request,@RequestBody Template input){return saveTemplate(request,null,input);}

    @PutMapping("/templates/{id}")
    ApiResponse<?> updateTemplate(HttpServletRequest request,@PathVariable long id,@RequestBody Template input){return saveTemplate(request,id,input);}

    @GetMapping("/sku-rules")
    ApiResponse<?> rules(HttpServletRequest request,@RequestParam(defaultValue="9223372036854775807") long cursor,
                         @RequestParam(defaultValue="50") int limit) {
        return ApiResponse.success(settings.rules(viewSettings(request),cursor,limit));
    }

    @PostMapping("/sku-rules")
    ApiResponse<?> createRule(HttpServletRequest request,@RequestBody Rule input){return saveRule(request,null,input);}

    @PutMapping("/sku-rules/{id}")
    ApiResponse<?> updateRule(HttpServletRequest request,@PathVariable long id,@RequestBody Rule input){return saveRule(request,id,input);}

    @GetMapping("/inspections")
    ApiResponse<?> list(HttpServletRequest request,@RequestParam(required=false) String status,@RequestParam(required=false) Long warehouseId,
                        @RequestParam(required=false) Long cursor,@RequestParam(defaultValue="50") int limit) {
        return ApiResponse.success(inspections.list(access.requireCompany(request,"qc_inspection:view"),status,warehouseId,cursor,limit,false));
    }

    @GetMapping("/nonconforming")
    ApiResponse<?> nonconforming(HttpServletRequest request,@RequestParam(required=false) Long warehouseId,
                                @RequestParam(required=false) Long cursor,@RequestParam(defaultValue="50") int limit) {
        return ApiResponse.success(inspections.list(access.requireCompany(request,"qc_inspection:view"),null,warehouseId,cursor,limit,true));
    }

    @GetMapping("/inspections/{id}")
    ApiResponse<?> detail(HttpServletRequest request,@PathVariable long id){
        return ApiResponse.success(inspections.detail(access.requireCompany(request,"qc_inspection:view"),id));
    }

    @PostMapping("/inspections/{id}/submit")
    ApiResponse<?> submit(HttpServletRequest request,@PathVariable long id,
                          @RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody Submission input){
        return ApiResponse.success(inspections.submit(inspector(request,"submit"),id,key,input));
    }

    @PostMapping("/inspections/{id}/{action:approve|reject}")
    ApiResponse<?> decide(HttpServletRequest request,@PathVariable long id,@PathVariable String action,
                          @RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody Decision input){
        return ApiResponse.success(inspections.decide(inspector(request,"approve"),id,action,key,input));
    }

    @PostMapping("/nonconforming/{id}/dispose")
    ApiResponse<?> dispose(HttpServletRequest request,@PathVariable long id,
                           @RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody Disposition input){
        return ApiResponse.success(inspections.dispose(inspector(request,"dispose"),id,key,input));
    }

    private CompanyAccess viewSettings(HttpServletRequest request){return access.requireCompany(request,"qc_setting:view");}

    private CompanyAccess manage(HttpServletRequest request){
        var actor=access.requireCompany(request,"qc_setting:manage");
        if(!actor.hasPermission("qc_setting:view")||!actor.allWarehouses()||!actor.orgScope().allDepartments())throw forbidden();
        return actor;
    }

    private CompanyAccess inspector(HttpServletRequest request,String action){
        var actor=access.requireCompany(request,"qc_inspection:"+action);
        if(!actor.hasPermission("qc_inspection:view"))throw forbidden();
        return actor;
    }

    private ApiResponse<?> saveItem(HttpServletRequest request,Long id,Item input){return ApiResponse.success(settings.item(manage(request),id,input));}
    private ApiResponse<?> saveTemplate(HttpServletRequest request,Long id,Template input){return ApiResponse.success(settings.template(manage(request),id,input));}
    private ApiResponse<?> saveRule(HttpServletRequest request,Long id,Rule input){return ApiResponse.success(settings.rule(manage(request),id,input));}
}
