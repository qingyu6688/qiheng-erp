package cn.erp.stockmisc;

import cn.erp.identity.AccessService;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static cn.erp.stockmisc.StockMiscModels.*;
import static cn.erp.stockmisc.StockMiscQueries.RESOURCE;

@RestController
@RequestMapping("/api/v1/stock-misc-docs")
class StockMiscController {
    private final AccessService access;
    private final StockMiscService service;
    StockMiscController(AccessService access,StockMiscService service){this.access=access;this.service=service;}

    @GetMapping
    ApiResponse<?> list(HttpServletRequest request,@RequestParam String direction,@RequestParam(required=false) String status,
                        @RequestParam(required=false) Long cursor,@RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="") String keyword){
        return ApiResponse.success(service.list(actor(request,"view"),direction,status,cursor,limit,keyword,false));
    }

    @GetMapping("/loans")
    ApiResponse<?> loans(HttpServletRequest request,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="50") int limit,
                         @RequestParam(defaultValue="") String keyword){
        return ApiResponse.success(service.list(actor(request,"view"),"out","posted",cursor,limit,keyword,true));
    }

    @GetMapping("/loans/{id}/outstanding")
    ApiResponse<?> outstanding(HttpServletRequest request,@PathVariable long id){
        return ApiResponse.success(service.outstanding(actor(request,"view"),id));
    }

    @GetMapping("/{id}")
    ApiResponse<?> detail(HttpServletRequest request,@PathVariable long id){return ApiResponse.success(service.detail(actor(request,"view"),id));}

    @PostMapping
    ResponseEntity<?> create(HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Input input){
        return ResponseEntity.status(201).body(ApiResponse.created(service.create(actor(request,"create"),key,input)));
    }

    @PutMapping("/{id}")
    ApiResponse<?> update(HttpServletRequest request,@PathVariable long id,@Valid @RequestBody Input input){
        return ApiResponse.success(service.update(actor(request,"update"),id,input));
    }

    @PostMapping("/{id}/{action:submit|approve|reject|post|void}")
    ApiResponse<?> command(HttpServletRequest request,@PathVariable long id,@PathVariable String action,
                           @RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Command input){
        return ApiResponse.success(service.command(actor(request,action),id,action,key,input));
    }

    private CompanyAccess actor(HttpServletRequest request,String action){
        var actor=access.requireCompany(request,RESOURCE+":"+action);
        if(!actor.hasPermission(RESOURCE+":view"))throw new ApiException(403,"FORBIDDEN","没有查看其他出入库单的权限");
        return actor;
    }
}
