package cn.erp.workbench;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class WorkbenchController {
    private final AccessService access;
    private final DocumentBrowser browser;
    private final WorkbenchService workbench;
    WorkbenchController(AccessService access,DocumentBrowser browser,WorkbenchService workbench) {this.access=access;this.browser=browser;this.workbench=workbench;}
    @GetMapping("/document-browser/{kind}")
    ApiResponse<?> list(HttpServletRequest request,@PathVariable String kind,@RequestParam(required=false) String keyword,@RequestParam(required=false) String status,
            @RequestParam(required=false) Long partnerId,@RequestParam(required=false) Long warehouseId,@RequestParam(required=false) LocalDate dateFrom,
            @RequestParam(required=false) LocalDate dateTo,@RequestParam(required=false) String fulfillmentStatus,@RequestParam(required=false) String settlementStatus,
            @RequestParam(defaultValue="false") boolean signOverdue,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="true") boolean withTotal) {
        return ApiResponse.success(browser.page(access.requireCompany(request,browser.permission(kind)),kind,new DocumentBrowser.Filter(keyword,status,partnerId,warehouseId,dateFrom,dateTo,fulfillmentStatus,settlementStatus,signOverdue),cursor,limit,withTotal));
    }
    @GetMapping("/workbench/summary")
    ApiResponse<?> summary(HttpServletRequest request) {return ApiResponse.success(workbench.summary(access.requireCompany(request,null)));}
}
