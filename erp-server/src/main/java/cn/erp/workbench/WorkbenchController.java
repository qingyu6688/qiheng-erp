package cn.erp.workbench;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import cn.erp.reporting.TradeSummaryService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class WorkbenchController {
    private final AccessService access;
    private final DocumentBrowser browser;
    private final WorkbenchService workbench;
    private final TradeSummaryService trade;
    private final SetupChecklist setup;
    WorkbenchController(AccessService access,DocumentBrowser browser,WorkbenchService workbench,TradeSummaryService trade,SetupChecklist setup) {this.access=access;this.browser=browser;this.workbench=workbench;this.trade=trade;this.setup=setup;}
    @GetMapping("/document-browser/{kind}")
    ApiResponse<?> list(HttpServletRequest request,@PathVariable String kind,@RequestParam(required=false) String keyword,@RequestParam(required=false) String status,
            @RequestParam(required=false) Long partnerId,@RequestParam(required=false) Long warehouseId,@RequestParam(required=false) LocalDate dateFrom,
            @RequestParam(required=false) LocalDate dateTo,@RequestParam(required=false) String fulfillmentStatus,@RequestParam(required=false) String settlementStatus,
            @RequestParam(defaultValue="false") boolean signOverdue,@RequestParam(required=false) Long cursor,
            @RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="true") boolean withTotal,
            @RequestParam(required=false) String documentNo) {
        var filter=new DocumentBrowser.Filter(keyword,status,partnerId,warehouseId,dateFrom,dateTo,
                fulfillmentStatus,settlementStatus,signOverdue,documentNo);
        return ApiResponse.success(browser.page(access.requireCompany(request,browser.permission(kind)),kind,
                filter,cursor,limit,withTotal,request.getParameterMap(),false));
    }
    @GetMapping("/document-browser/{kind}/custom-fields.csv")
    ResponseEntity<byte[]> export(HttpServletRequest request,@PathVariable String kind,@RequestParam(required=false) String keyword,
            @RequestParam(required=false) String status,@RequestParam(required=false) Long partnerId,@RequestParam(required=false) Long warehouseId,
            @RequestParam(required=false) LocalDate dateFrom,@RequestParam(required=false) LocalDate dateTo,
            @RequestParam(required=false) String fulfillmentStatus,@RequestParam(required=false) String settlementStatus,
            @RequestParam(required=false) Long cursor,@RequestParam(defaultValue="20") int limit) {
        var actor=access.requireCompany(request,browser.permission(kind));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header("Content-Disposition","attachment; filename=erp-order-fields.csv")
                .body(browser.customExport(actor,kind,new DocumentBrowser.Filter(keyword,status,partnerId,warehouseId,dateFrom,dateTo,
                        fulfillmentStatus,settlementStatus,false),cursor,limit,request.getParameterMap()));
    }
    @GetMapping("/workbench/summary")
    ApiResponse<?> summary(HttpServletRequest request) {return ApiResponse.success(workbench.summary(access.requireCompany(request,null)));}
    @GetMapping("/workbench/setup")
    ApiResponse<?> setup(HttpServletRequest request) {return ApiResponse.success(setup.status(access.requireCompany(request,null)));}
    @GetMapping("/workbench/trade-summary")
    ApiResponse<?> tradeSummary(HttpServletRequest request,@RequestParam(defaultValue="12") int months) {
        return ApiResponse.success(trade.summary(access.requireCompany(request,null),months));
    }

    record Selection(List<Long> ids) {}

    @PostMapping("/document-browser/{kind}/selected.csv")
    ResponseEntity<byte[]> exportSelection(HttpServletRequest request, @PathVariable String kind,
                                          @RequestBody Selection selection) {
        var actor = access.requireCompany(request, browser.permission(kind));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header("Content-Disposition", "attachment; filename=erp-selected-documents.csv")
                .header("Cache-Control", "no-store")
                .body(browser.exportSelection(actor, kind, selection.ids()));
    }
}
