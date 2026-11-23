package cn.erp.reporting;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reports")
class ReportController {
    private final AccessService access;
    private final ReportService reports;
    ReportController(AccessService access,ReportService reports) { this.access=access;this.reports=reports; }

    @GetMapping("/{type}")
    ApiResponse<?> get(HttpServletRequest request,@PathVariable String type,
            @RequestParam(required=false) LocalDate dateFrom,@RequestParam(required=false) LocalDate dateTo,
            @RequestParam(required=false) Long warehouseId,@RequestParam(required=false) Long skuId,
            @RequestParam(required=false) Long partnerId,@RequestParam(required=false) Long cashAccountId,
            @RequestParam(defaultValue="false") boolean detail,@RequestParam(defaultValue="false") boolean feesOnly,@RequestParam(required=false) Long cursor,
            @RequestParam(defaultValue="20") int limit,@RequestParam(required=false) Long businessUserId,@RequestParam(required=false) String groupBy,
            @RequestParam(required=false) LocalDate agingDate,@RequestParam(required=false) String agingBasis) {
        var report=ReportType.parse(type);
        return ApiResponse.success(reports.page(access.requireCompany(request,report.permission()),report,
                new ReportQuery(dateFrom,dateTo,warehouseId,skuId,partnerId,cashAccountId,detail,feesOnly,businessUserId,groupBy,agingDate,agingBasis),cursor,limit));
    }
}
