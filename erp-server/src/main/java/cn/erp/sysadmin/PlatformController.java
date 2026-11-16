package cn.erp.sysadmin;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class PlatformController {
    private final AccessService access;
    private final DictionaryService dictionaries;
    private final SystemConfiguration configurations;
    private final AreaService areas;
    PlatformController(AccessService access,DictionaryService dictionaries,SystemConfiguration configurations,AreaService areas) {this.access=access;this.dictionaries=dictionaries;this.configurations=configurations;this.areas=areas;}
    @GetMapping("/dicts") ApiResponse<?> dictionaries(HttpServletRequest request,@RequestParam String types,@RequestParam(required=false) String version) {return ApiResponse.success(dictionaries.dictionaries(access.requireUser(request),types,version));}
    @GetMapping("/areas/tree") ApiResponse<?> areas(HttpServletRequest request) {access.requireUser(request);return ApiResponse.success(areas.tree());}
    @GetMapping("/sys/dict-types") ApiResponse<?> types(HttpServletRequest request,@RequestParam(defaultValue="") String keyword,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="50") int limit) {return ApiResponse.success(dictionaries.types(access.requireTenant(request,"dict:view"),keyword,cursor,limit));}
    @PostMapping("/sys/dict-types") ApiResponse<?> createType(HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody DictionaryService.TypeInput input) {return ApiResponse.success(dictionaries.saveType(access.requireTenant(request,"dict:create"),null,key,input));}
    @PutMapping("/sys/dict-types/{id}") ApiResponse<?> updateType(HttpServletRequest request,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody DictionaryService.TypeInput input) {return ApiResponse.success(dictionaries.saveType(access.requireTenant(request,"dict:update"),id,key,input));}
    @GetMapping("/sys/dict-items") ApiResponse<?> items(HttpServletRequest request,@RequestParam String typeCode,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="50") int limit) {return ApiResponse.success(dictionaries.items(access.requireTenant(request,"dict:view"),typeCode,cursor,limit));}
    @PostMapping("/sys/dict-items") ApiResponse<?> createItem(HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody DictionaryService.ItemInput input) {return ApiResponse.success(dictionaries.saveItem(access.requireTenant(request,"dict:create"),null,key,input));}
    @PutMapping("/sys/dict-items/{id}") ApiResponse<?> updateItem(HttpServletRequest request,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody DictionaryService.ItemInput input) {return ApiResponse.success(dictionaries.saveItem(access.requireTenant(request,"dict:update"),id,key,input));}
    @GetMapping("/sys/configs") ApiResponse<?> configs(HttpServletRequest request,@RequestParam(defaultValue="") String keyword) {return ApiResponse.success(configurations.list(access.requireTenant(request,"sys_config:view"),keyword));}
    @PutMapping("/sys/configs/{key}") ApiResponse<?> config(HttpServletRequest request,@PathVariable String key,@RequestHeader(value="Idempotency-Key",required=false) String idempotencyKey,@Valid @RequestBody SystemConfiguration.Input input) {return ApiResponse.success(configurations.save(access.requireTenant(request,"sys_config:update"),key,idempotencyKey,input));}
    record DeleteVersion(@jakarta.validation.constraints.NotNull(message="请提供版本号") @jakarta.validation.constraints.PositiveOrZero(message="版本号无效") Long version) {}
    @DeleteMapping("/sys/dict-types/{id}") ApiResponse<?> deleteType(HttpServletRequest request,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody DeleteVersion input) {return ApiResponse.success(dictionaries.delete(access.requireTenant(request,"dict:delete"),true,id,input.version(),key));}
    @DeleteMapping("/sys/dict-items/{id}") ApiResponse<?> deleteItem(HttpServletRequest request,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody DeleteVersion input) {return ApiResponse.success(dictionaries.delete(access.requireTenant(request,"dict:delete"),false,id,input.version(),key));}
}
