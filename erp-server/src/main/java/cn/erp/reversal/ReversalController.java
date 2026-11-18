package cn.erp.reversal;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/document-reversals")
class ReversalController {
    private final AccessService access;
    private final ReversalService service;
    ReversalController(AccessService access,ReversalService service){this.access=access;this.service=service;}
    @GetMapping ApiResponse<?> list(HttpServletRequest request,@RequestParam(required=false) String status,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="20") int limit){return ApiResponse.success(service.list(access.requireCompany(request,"reversal:view"),status,cursor,limit));}
    @GetMapping("/{id}") ApiResponse<?> get(HttpServletRequest request,@PathVariable long id){return ApiResponse.success(service.get(access.requireCompany(request,"reversal:view"),id));}
    @PostMapping ResponseEntity<?> create(HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody ReversalModels.Input input){return ResponseEntity.status(201).body(ApiResponse.created(service.create(access.requireCompany(request,"reversal:create"),key,input)));}
    @PostMapping("/{id}/{action:approve|reject|post|cancel}") ApiResponse<?> command(HttpServletRequest request,@PathVariable long id,@PathVariable String action,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody ReversalModels.Version input){
        String permission=switch(action) {
            case "reject","cancel" -> "reversal:approve";
            default -> "reversal:"+action;
        };
        return ApiResponse.success(service.command(access.requireCompany(request,permission),id,action,key,input));
    }
}
