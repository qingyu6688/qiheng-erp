package cn.erp.importing;

import cn.erp.identity.AccessService;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiResponse;
import cn.erp.shared.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.LocalDate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
class ImportController {
    private final AccessService access;
    private final ImportJobs jobs;
    ImportController(AccessService access,ImportJobs jobs){this.access=access;this.jobs=jobs;}
    @GetMapping("/import-templates/{jobType}")
    ResponseEntity<byte[]> template(HttpServletRequest request,@PathVariable String jobType) {
        var scope=access.requireCompany(request,"import:"+jobType);ImportAccess.permissions(scope,jobType,jobType.equals("opening_stock"));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+jobType+"-template.csv\"").body(ImportCsv.template(jobType));
    }
    @PostMapping(value="/import-jobs",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<?> create(HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestParam String jobType,@RequestParam MultipartFile file,@RequestParam(required=false) Long warehouseId,
            @RequestParam(required=false) LocalDate businessDate) {
        var scope=access.requireCompany(request,"import:"+jobType);
        if(file.getSize()>ImportCsv.MAX_BYTES)throw ImportCsv.invalid("CSV 文件不能超过 20MB");
        byte[] bytes;try{bytes=file.getBytes();}catch(IOException error){throw ImportCsv.invalid("无法读取上传文件，请重新选择文件");}
        var job=jobs.create(scope,key,jobType,warehouseId,businessDate,file.getOriginalFilename(),bytes);
        return ResponseEntity.status(202).body(new ApiResponse<>(202,"任务已受理",job,RequestIds.current(),null,null));
    }
    @GetMapping("/import-jobs")
    ApiResponse<?> list(HttpServletRequest request,@RequestParam(required=false) String jobType,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="20") int limit){return ApiResponse.success(jobs.list(anyAccess(request),jobType,cursor,limit));}
    @GetMapping("/import-jobs/{id}")
    ApiResponse<?> get(HttpServletRequest request,@PathVariable long id){return ApiResponse.success(jobs.get(anyAccess(request),id));}
    @GetMapping("/import-jobs/{id}/errors")
    ApiResponse<?> errors(HttpServletRequest request,@PathVariable long id,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="100") int limit){return ApiResponse.success(jobs.errors(anyAccess(request),id,cursor,limit));}
    @PostMapping("/import-jobs/{id}/retry")
    ResponseEntity<?> retry(HttpServletRequest request,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        return ResponseEntity.accepted().body(new ApiResponse<>(202,"任务已重新排队",jobs.retry(anyAccess(request),id,key),RequestIds.current(),null,null));
    }
    private CompanyAccess anyAccess(HttpServletRequest request) {
        for(String type:ImportCsv.HEADERS.keySet()) {
            try{return access.requireCompany(request,"import:"+type);}
            catch(ApiException error){if(!error.getErrorCode().equals("FORBIDDEN"))throw error;}
        }
        throw ImportAccess.forbidden();
    }
}
