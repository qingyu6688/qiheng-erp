package cn.erp.exporting;

import cn.erp.documents.FileTickets;
import cn.erp.identity.AccessService;
import cn.erp.reporting.ReportType;
import cn.erp.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/export-jobs")
class ExportController {
    private final AccessService access;
    private final ExportJobs jobs;
    private final FileTickets tickets;
    ExportController(AccessService access,ExportJobs jobs,FileTickets tickets) { this.access=access;this.jobs=jobs;this.tickets=tickets; }
    @PostMapping
    ResponseEntity<?> create(HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody ExportJobs.Input input) {
        var actor=access.requireCompany(request,DocumentPrintExportPort.supports(input.exportType())?null:
                FinancialExportPort.supports(input.exportType())?"gl_export:create":ReportType.parse(input.exportType()).exportPermission());
        return ResponseEntity.accepted().body(new ApiResponse<>(202,"导出任务已提交",jobs.create(actor,key,input),RequestIds.current(),null,null));
    }
    @GetMapping
    ApiResponse<?> list(HttpServletRequest request,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="20") int limit) {
        return ApiResponse.success(jobs.list(access.requireCompany(request,null),cursor,limit));
    }
    @GetMapping("/{id}")
    ApiResponse<?> get(HttpServletRequest request,@PathVariable long id) { return ApiResponse.success(jobs.get(access.requireCompany(request,null),id)); }
    @PostMapping("/{id}/download-ticket")
    ApiResponse<?> ticket(HttpServletRequest request,@PathVariable long id) {
        var actor=access.requireCompany(request,null);jobs.ready(actor,id);
        String ticket=tickets.issue(actor,"export_job",id);
        return ApiResponse.success(Map.of("url","/api/v1/export-jobs/"+id+"/download?ticket="+ticket,"expiresAt",Instant.now().plusSeconds(300).toString()));
    }
    @GetMapping("/{id}/download")
    ResponseEntity<byte[]> download(HttpServletRequest request,@PathVariable long id,@RequestParam(required=false) String ticket) {
        var actor=access.requireCompany(request,null);tickets.verify(actor,"export_job",id,ticket);jobs.ready(actor,id);
        var result=jobs.download(actor,id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(result.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(result.fileName(),StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL,"private, no-store").header("X-Content-Type-Options","nosniff").body(result.bytes());
    }
}
