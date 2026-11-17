package cn.erp.crm;

import cn.erp.identity.AccessService;
import cn.erp.importing.ClueCsv;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/crm")
class CrmCoreController {
    private final AccessService access;
    private final CrmClues clues;
    private final CrmCustomers customers;
    private final CrmPoolSettings pool;
    private final CrmFollowUps follows;
    private final CrmScope scope;
    private final CrmWorkbench workbench;

    CrmCoreController(AccessService access, CrmClues clues, CrmCustomers customers, CrmPoolSettings pool, CrmFollowUps follows, CrmScope scope, CrmWorkbench workbench) {
        this.access = access;
        this.clues = clues;
        this.customers = customers;
        this.pool = pool;
        this.follows = follows;
        this.scope = scope;
        this.workbench = workbench;
    }

    @GetMapping("/clues")
    ApiResponse<Map<String, Object>> clues(HttpServletRequest request, @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String keyword, @RequestParam(required = false) String cursor,
                                           @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(clues.list(access.requireCompany(request, "crm_clue:view"), status, keyword, cursor, limit));
    }

    @GetMapping("/clues/{id:[0-9]+}")
    ApiResponse<Map<String, Object>> clue(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.success(clues.get(access.requireCompany(request, "crm_clue:view"), id));
    }

    @PostMapping("/clues")
    ApiResponse<Map<String, Object>> createClue(HttpServletRequest request, @Valid @RequestBody CrmCoreModels.ClueInput input) {
        return ApiResponse.success(clues.create(access.requireCompany(request, "crm_clue:create"), input));
    }

    @PutMapping("/clues/{id}")
    ApiResponse<Map<String, Object>> updateClue(HttpServletRequest request, @PathVariable long id,
                                               @Valid @RequestBody CrmCoreModels.ClueInput input) {
        return ApiResponse.success(clues.update(access.requireCompany(request, "crm_clue:update"), id, input));
    }

    @PostMapping("/clues/{id}/{action:assign|convert|invalidate}")
    ApiResponse<Map<String, Object>> clueAction(HttpServletRequest request, @PathVariable long id, @PathVariable String action,
                                               @Valid @RequestBody CrmCoreModels.ClueAction input) {
        return ApiResponse.success(clues.action(access.requireCompany(request, "crm_clue:" + action), id, action, input));
    }

    @PostMapping("/clues/assign")
    ApiResponse<Map<String, Object>> batchAssign(HttpServletRequest request, @Valid @RequestBody CrmCoreModels.BatchAssign input) {
        return ApiResponse.success(clues.batchAssign(access.requireCompany(request, "crm_clue:assign"), input));
    }

    @GetMapping("/employee-options")
    ApiResponse<?> employeeOptions(HttpServletRequest request, @RequestParam(required = false) String keyword) {
        return ApiResponse.success(scope.employeeOptions(access.requireCompany(request, null), keyword));
    }

    @GetMapping("/department-options")
    ApiResponse<?> departmentOptions(HttpServletRequest request) {
        return ApiResponse.success(pool.departmentOptions(access.requireCompany(request, "crm_pool:manage")));
    }

    @GetMapping("/workbench")
    ApiResponse<?> workbench(HttpServletRequest request) {
        return ApiResponse.success(workbench.summary(access.requireCompany(request, null)));
    }

    @GetMapping("/clues/import-template")
    ResponseEntity<byte[]> clueTemplate(HttpServletRequest request) {
        access.requireCompany(request, "crm_clue:import");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=crm-clues.csv").body(ClueCsv.template());
    }

    @PostMapping(path = "/clues/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiResponse<Map<String, Object>> importClues(HttpServletRequest request, @RequestParam MultipartFile file) {
        var actor = access.requireCompany(request, "crm_clue:import");
        if (file.isEmpty() || file.getSize() > ClueCsv.MAX_BYTES) throw CrmScope.invalid("请选择不超过 2 MB 的 CSV 文件");
        try { return ApiResponse.success(clues.importRows(actor, ClueCsv.read(file.getBytes()))); }
        catch (IOException failure) { throw new ApiException(422, "CRM_IMPORT_UNREADABLE", "无法读取线索文件，请重新选择"); }
    }

    @GetMapping("/customers")
    ApiResponse<Map<String, Object>> customers(HttpServletRequest request, @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(customers.list(access.requireCompany(request, "crm_customer:view"), false, keyword, cursor, limit));
    }

    @GetMapping("/customers/{partnerId}")
    ApiResponse<Map<String, Object>> customer(HttpServletRequest request, @PathVariable long partnerId) {
        return ApiResponse.success(customers.get(access.requireCompany(request, "crm_customer:view"), partnerId));
    }

    @PostMapping("/customers")
    ApiResponse<Map<String, Object>> registerCustomer(HttpServletRequest request, @Valid @RequestBody CrmCoreModels.CustomerInput input) {
        return ApiResponse.success(customers.create(access.requireCompany(request, "crm_customer:create"), input));
    }

    @PutMapping("/customers/{partnerId}")
    ApiResponse<Map<String, Object>> updateCustomer(HttpServletRequest request, @PathVariable long partnerId,
                                                   @Valid @RequestBody CrmCoreModels.CustomerInput input) {
        return ApiResponse.success(customers.update(access.requireCompany(request, "crm_customer:update"), partnerId, input));
    }

    @PostMapping("/customers/{partnerId}/{action:transfer|lock|unlock|release}")
    ApiResponse<Map<String, Object>> action(HttpServletRequest request, @PathVariable long partnerId, @PathVariable String action,
                                           @Valid @RequestBody CrmCoreModels.OwnerAction input) {
        String permission = "unlock".equals(action) ? "lock" : action;
        return ApiResponse.success(customers.action(access.requireCompany(request, "crm_customer:" + permission), partnerId, action, input));
    }

    @GetMapping("/customers/{partnerId}/team")
    ApiResponse<Map<String, Object>> team(HttpServletRequest request, @PathVariable long partnerId) {
        return ApiResponse.success(customers.team(access.requireCompany(request, "crm_customer:view"), partnerId));
    }

    @PostMapping("/customers/{partnerId}/team")
    ApiResponse<Map<String, Object>> saveTeam(HttpServletRequest request, @PathVariable long partnerId, @Valid @RequestBody CrmCoreModels.TeamInput input) {
        return ApiResponse.success(customers.saveTeam(access.requireCompany(request, "crm_customer:team"), partnerId, input));
    }

    @DeleteMapping("/customers/{partnerId}/team/{userId}")
    ApiResponse<Map<String, Object>> removeTeam(HttpServletRequest request, @PathVariable long partnerId, @PathVariable long userId) {
        return ApiResponse.success(customers.removeTeam(access.requireCompany(request, "crm_customer:team"), partnerId, userId));
    }

    @GetMapping("/customers/{partnerId}/contacts")
    ApiResponse<Map<String, Object>> contacts(HttpServletRequest request, @PathVariable long partnerId) {
        return ApiResponse.success(customers.contacts(access.requireCompany(request, "crm_customer:view"), partnerId));
    }

    @PostMapping("/customers/{partnerId}/contacts")
    ApiResponse<Map<String, Object>> saveContact(HttpServletRequest request, @PathVariable long partnerId,
                                                @Valid @RequestBody CrmCoreModels.ContactInput input) {
        return ApiResponse.success(customers.saveContact(access.requireCompany(request, "crm_customer:contact"), partnerId, input));
    }

    @GetMapping("/pool")
    ApiResponse<Map<String, Object>> pool(HttpServletRequest request, @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(customers.list(access.requireCompany(request, "crm_pool:view"), true, keyword, cursor, limit));
    }

    @PostMapping("/pool/{partnerId}/{action:claim|assign}")
    ApiResponse<Map<String, Object>> poolAction(HttpServletRequest request, @PathVariable long partnerId, @PathVariable String action,
                                               @Valid @RequestBody(required = false) CrmCoreModels.OwnerAction input) {
        return ApiResponse.success(customers.poolAction(access.requireCompany(request, "crm_pool:" + action), partnerId, action,
                input == null ? new CrmCoreModels.OwnerAction(null, null) : input));
    }

    @GetMapping("/pool-rules")
    ApiResponse<Map<String, Object>> rule(HttpServletRequest request) {
        return ApiResponse.success(pool.rule(access.requireCompany(request, "crm_pool:manage")));
    }

    @PutMapping("/pool-rules")
    ApiResponse<Map<String, Object>> rule(HttpServletRequest request, @Valid @RequestBody CrmCoreModels.PoolRuleInput input) {
        return ApiResponse.success(pool.saveRule(access.requireCompany(request, "crm_pool:manage"), input));
    }

    @GetMapping("/owner-limits")
    ApiResponse<Map<String, Object>> limits(HttpServletRequest request) {
        return ApiResponse.success(pool.limits(access.requireCompany(request, "crm_pool:manage")));
    }

    @PutMapping("/owner-limits")
    ApiResponse<Map<String, Object>> limits(HttpServletRequest request, @Valid @RequestBody CrmCoreModels.OwnerLimitInput input) {
        return ApiResponse.success(pool.saveLimit(access.requireCompany(request, "crm_pool:manage"), input));
    }

    @DeleteMapping("/owner-limits/{type}/{id}")
    ApiResponse<Map<String, Object>> deleteLimit(HttpServletRequest request, @PathVariable String type, @PathVariable long id,
                                                @RequestParam long version) {
        return ApiResponse.success(pool.deleteLimit(access.requireCompany(request, "crm_pool:manage"), type, id, version));
    }

    @GetMapping("/follow-ups")
    ApiResponse<Map<String, Object>> followUps(HttpServletRequest request, @RequestParam String bizType, @RequestParam long bizId,
                                               @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(follows.list(access.requireCompany(request, null), bizType, bizId, cursor, limit));
    }

    @PostMapping("/follow-ups")
    ApiResponse<Map<String, Object>> followUp(HttpServletRequest request, @Valid @RequestBody CrmCoreModels.FollowInput input) {
        return ApiResponse.success(follows.create(access.requireCompany(request, null), input));
    }
}
