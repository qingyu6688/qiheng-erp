package cn.erp.opening;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class OpeningController {
    private final AccessService access;
    private final OpeningService service;
    OpeningController(AccessService access,OpeningService service){this.access=access;this.service=service;}
    @GetMapping("/{kind:stock-adjustments|opening-entries}") ApiResponse<?> list(HttpServletRequest request,@PathVariable String kind,@RequestParam(required=false) String status,@RequestParam(required=false) String adjustType,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="20") int limit){
        boolean stock=kind.equals("stock-adjustments");return ApiResponse.success(service.list(access.requireCompany(request,permission(stock,"view")),stock,status,adjustType,cursor,limit));
    }
    @GetMapping("/{kind:stock-adjustments|opening-entries}/{id}") ApiResponse<?> get(HttpServletRequest request,@PathVariable String kind,@PathVariable long id){
        boolean stock=kind.equals("stock-adjustments");return ApiResponse.success(service.get(access.requireCompany(request,permission(stock,"view")),stock,id));
    }
    @PostMapping("/stock-adjustments") ResponseEntity<?> createStock(HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody OpeningService.Stock input){
        return ResponseEntity.status(201).body(ApiResponse.created(service.createStock(access.requireCompany(request,"stock_adjustment:create"),key,input)));
    }
    @PutMapping("/stock-adjustments/{id}") ApiResponse<?> editStock(HttpServletRequest request,@PathVariable long id,@Valid @RequestBody OpeningService.Stock input){
        return ApiResponse.success(service.updateStock(access.requireCompany(request,"stock_adjustment:update"),id,input));
    }
    @PostMapping("/opening-entries") ResponseEntity<?> createEntry(HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody OpeningService.Entry input){
        return ResponseEntity.status(201).body(ApiResponse.created(service.createEntry(access.requireCompany(request,"opening_entry:create"),key,input)));
    }
    @PutMapping("/opening-entries/{id}") ApiResponse<?> editEntry(HttpServletRequest request,@PathVariable long id,@Valid @RequestBody OpeningService.Entry input){
        return ApiResponse.success(service.updateEntry(access.requireCompany(request,"opening_entry:update"),id,input));
    }
    @PostMapping("/{kind:stock-adjustments|opening-entries}/{id}/{action:submit|approve|reject|post|void}") ApiResponse<?> command(HttpServletRequest request,@PathVariable String kind,@PathVariable long id,@PathVariable String action,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody OpeningService.Version input){
        boolean stock=kind.equals("stock-adjustments");return ApiResponse.success(service.command(access.requireCompany(request,permission(stock,action)),stock,id,action,key,input));
    }
    private String permission(boolean stock,String action){return(stock?"stock_adjustment:":"opening_entry:")+action;}
}
