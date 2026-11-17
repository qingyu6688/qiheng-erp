package cn.erp.crm;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/crm")
class CrmCycleController {
    private final AccessService access;
    private final CrmOpportunityService opportunities;
    private final CrmContractService contracts;
    private final CrmReceivableService receivables;
    private final CrmStatisticsService statistics;

    CrmCycleController(AccessService access, CrmOpportunityService opportunities, CrmContractService contracts,
                       CrmReceivableService receivables, CrmStatisticsService statistics) {
        this.access = access;
        this.opportunities = opportunities;
        this.contracts = contracts;
        this.receivables = receivables;
        this.statistics = statistics;
    }

    @GetMapping("/stage-groups")
    ApiResponse<?> groups(HttpServletRequest request) {
        return ApiResponse.success(opportunities.groups(access.requireCompany(request, "crm_business:view")));
    }

    @PostMapping("/stage-groups")
    ApiResponse<?> createGroup(HttpServletRequest request, @Valid @RequestBody CrmCycleModels.StageGroup input) {
        return ApiResponse.success(opportunities.createGroup(access.requireCompany(request, "crm_business:manage"), input));
    }

    @GetMapping("/businesses")
    ApiResponse<?> businesses(HttpServletRequest request, @RequestParam(required = false) String partnerId,
                              @RequestParam(required = false) String result, @RequestParam(required = false) String cursor,
                              @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(opportunities.list(access.requireCompany(request, "crm_business:view"), partnerId, result, cursor, limit));
    }

    @GetMapping("/businesses/{id}")
    ApiResponse<?> business(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.success(opportunities.get(access.requireCompany(request, "crm_business:view"), id));
    }

    @PostMapping("/businesses")
    ApiResponse<?> createBusiness(HttpServletRequest request, @Valid @RequestBody CrmCycleModels.Business input) {
        return ApiResponse.success(opportunities.save(access.requireCompany(request, "crm_business:manage"), null, input));
    }

    @PutMapping("/businesses/{id}")
    ApiResponse<?> updateBusiness(HttpServletRequest request, @PathVariable long id, @Valid @RequestBody CrmCycleModels.Business input) {
        return ApiResponse.success(opportunities.save(access.requireCompany(request, "crm_business:manage"), id, input));
    }

    @PostMapping("/businesses/{id}/{action:advance|win|lose|invalidate}")
    ApiResponse<?> businessAction(HttpServletRequest request, @PathVariable long id, @PathVariable String action,
                                  @Valid @RequestBody CrmCycleModels.Action input) {
        return ApiResponse.success(opportunities.command(access.requireCompany(request, "crm_business:manage"), id, action, input));
    }

    @GetMapping("/contracts")
    ApiResponse<?> contracts(HttpServletRequest request, @RequestParam(required = false) String partnerId,
                             @RequestParam(required = false) String status, @RequestParam(required = false) String cursor,
                             @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(contracts.list(access.requireCompany(request, "crm_contract:view"), partnerId, status, cursor, limit));
    }

    @GetMapping("/contracts/{id}")
    ApiResponse<?> contract(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.success(contracts.get(access.requireCompany(request, "crm_contract:view"), id));
    }

    @PostMapping("/contracts")
    ApiResponse<?> createContract(HttpServletRequest request, @Valid @RequestBody CrmCycleModels.Contract input) {
        return ApiResponse.success(contracts.save(access.requireCompany(request, "crm_contract:manage"), null, input));
    }

    @PutMapping("/contracts/{id}")
    ApiResponse<?> updateContract(HttpServletRequest request, @PathVariable long id, @Valid @RequestBody CrmCycleModels.Contract input) {
        return ApiResponse.success(contracts.save(access.requireCompany(request, "crm_contract:manage"), id, input));
    }

    @PostMapping("/contracts/{id}/{action:submit|approve|reject|cancel}")
    ApiResponse<?> contractAction(HttpServletRequest request, @PathVariable long id, @PathVariable String action,
                                  @Valid @RequestBody CrmCycleModels.Action input) {
        String permission = action.equals("approve") || action.equals("reject") ? "crm_contract:approve" : "crm_contract:manage";
        return ApiResponse.success(contracts.command(access.requireCompany(request, permission), id, action, input));
    }

    @PostMapping("/contracts/{id}/create-sales-order")
    ApiResponse<?> createOrder(HttpServletRequest request, @PathVariable long id,
                               @RequestHeader(value = "Idempotency-Key", required = false) String key,
                               @Valid @RequestBody CrmCycleModels.CreateOrder input) {
        return ApiResponse.success(contracts.createOrder(access.requireCompany(request, "crm_contract:order"), id, key, input));
    }

    @GetMapping("/receivable-plans")
    ApiResponse<?> plans(HttpServletRequest request, @RequestParam(required = false) String contractId,
                         @RequestParam(required = false) String partnerId,
                         @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(receivables.plans(access.requireCompany(request, "crm_receivable:view"), contractId, partnerId, cursor, limit));
    }

    @PostMapping("/receivable-plans")
    ApiResponse<?> createPlan(HttpServletRequest request, @Valid @RequestBody CrmCycleModels.Plan input) {
        return ApiResponse.success(receivables.createPlan(access.requireCompany(request, "crm_receivable:manage"), input));
    }

    @GetMapping("/receivables")
    ApiResponse<?> receivables(HttpServletRequest request, @RequestParam(required = false) String contractId,
                               @RequestParam(required = false) String planId, @RequestParam(required = false) String cursor,
                               @RequestParam(required = false) String partnerId,
                               @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(receivables.list(access.requireCompany(request, "crm_receivable:view"), contractId, planId, partnerId, cursor, limit));
    }

    @PostMapping("/receivables")
    ApiResponse<?> associate(HttpServletRequest request, @Valid @RequestBody CrmCycleModels.Receivable input) {
        return ApiResponse.success(receivables.associate(access.requireCompany(request, "crm_receivable:manage"), input));
    }

    @GetMapping("/performance-targets")
    ApiResponse<?> targets(HttpServletRequest request, @RequestParam int year, @RequestParam(required = false) Integer month) {
        return ApiResponse.success(statistics.targets(access.requireCompany(request, "crm_target:view"), year, month));
    }

    @PostMapping("/performance-targets")
    ApiResponse<?> saveTarget(HttpServletRequest request, @Valid @RequestBody CrmCycleModels.Target input) {
        return ApiResponse.success(statistics.saveTarget(access.requireCompany(request, "crm_target:manage"), input));
    }

    @GetMapping("/statistics/{kind}")
    ApiResponse<?> statistics(HttpServletRequest request, @PathVariable String kind,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
                              @RequestParam(required = false) String userIds, @RequestParam(required = false) String deptId) {
        return ApiResponse.success(statistics.statistics(access.requireCompany(request, "crm_statistic:view"), kind, dateFrom,
                dateTo, userIds, deptId));
    }
}
