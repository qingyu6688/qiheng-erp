package cn.erp.reference;

import cn.erp.identity.AccessService;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/v1")
class ReferenceController {
    private final AccessService access;
    private final PartnerService partners;
    private final CashService accounts;
    private final ReferenceService reference;
    private final BusinessCommands commands;

    ReferenceController(AccessService access,PartnerService partners,CashService accounts,
                        ReferenceService reference,BusinessCommands commands) {
        this.access=access;this.partners=partners;this.accounts=accounts;this.reference=reference;this.commands=commands;
    }

    @GetMapping("/partners")
    ApiResponse<Map<String,Object>> partners(HttpServletRequest req,@RequestParam(defaultValue="") String keyword,
            @RequestParam(required=false) String role,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit) {
        return ApiResponse.success(partners.list(access.requireTenant(req,"partner:view"),keyword,role,cursor,limit,req.getParameterMap()));
    }

    @GetMapping("/partners/{id}")
    ApiResponse<Map<String,Object>> partner(HttpServletRequest req,@PathVariable long id) {
        return ApiResponse.success(partners.get(access.requireTenant(req,"partner:view"),id,false));
    }

    @PostMapping("/partners")
    ResponseEntity<ApiResponse<Map<String,Object>>> createPartner(HttpServletRequest req,
            @RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody PartnerModels.Partner input) {
        var scope=access.requireTenant(req,"partner:create");
        return ResponseEntity.status(201).body(ApiResponse.created(command(scope,"partner.create",key,input,201,()->partners.create(scope,input))));
    }

    @PutMapping("/partners/{id}")
    ApiResponse<Map<String,Object>> updatePartner(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody PartnerModels.Partner input) {
        return ApiResponse.success(partners.update(access.requireTenant(req,"partner:update"),id,input));
    }

    @PostMapping("/partners/{id}/{action:enable|disable}")
    ApiResponse<Map<String,Object>> partnerStatus(HttpServletRequest req,@PathVariable long id,@PathVariable String action,
            @RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody PartnerModels.Version input) {
        var scope=access.requireTenant(req,"partner:update");
        return ApiResponse.success(command(scope,"partner."+action,key,List.of(id,input.version()),200,()->partners.status(scope,id,action,input.version())));
    }

    @GetMapping("/partners/{id}/contacts")
    ApiResponse<Map<String,Object>> contacts(HttpServletRequest req,@PathVariable long id) {
        // 联系人姓名可随往来单位查看，电话及地址独立授权。
        return ApiResponse.success(partners.contacts(access.requireTenant(req,"partner:view"),id));
    }

    @PostMapping("/partners/{id}/contacts")
    ResponseEntity<ApiResponse<Map<String,Object>>> addContact(HttpServletRequest req,@PathVariable long id,
            @RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody PartnerModels.Contact input) {
        var scope=access.requireTenant(req,"partner_contact:create");
        var created=command(scope,"partner_contact.create",key,List.of(id,input),201,()->{
            var saved=partners.saveContact(scope,id,null,input);
            saved.remove("mobile");saved.remove("address");return saved;
        });
        return ResponseEntity.status(201).body(ApiResponse.created(created));
    }

    @PutMapping("/partners/{id}/contacts/{cid}")
    ApiResponse<Map<String,Object>> editContact(HttpServletRequest req,@PathVariable long id,@PathVariable long cid,@Valid @RequestBody PartnerModels.Contact input) {
        return ApiResponse.success(partners.saveContact(access.requireTenant(req,"partner_contact:update"),id,cid,input));
    }

    @DeleteMapping("/partners/{id}/contacts/{cid}")
    ApiResponse<Void> deleteContact(HttpServletRequest req,@PathVariable long id,@PathVariable long cid,@RequestParam long version) {
        partners.deleteContact(access.requireTenant(req,"partner_contact:delete"),id,cid,version);return ApiResponse.success(null);
    }

    @GetMapping("/cash-accounts")
    ApiResponse<Map<String,Object>> accounts(HttpServletRequest req,@RequestParam(defaultValue="") String keyword,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit) {
        return ApiResponse.success(accounts.list(access.requireCompany(req,"cash_account:view"),keyword,cursor,limit));
    }

    @GetMapping("/cash-accounts/{id}")
    ApiResponse<Map<String,Object>> account(HttpServletRequest req,@PathVariable long id) {
        return ApiResponse.success(accounts.get(access.requireCompany(req,"cash_account:view"),id,false));
    }

    @PostMapping("/cash-accounts")
    ResponseEntity<ApiResponse<Map<String,Object>>> createAccount(HttpServletRequest req,
            @RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody CashModels.Account input) {
        var scope=access.requireCompany(req,"cash_account:create");
        // 幂等结果仅包含公开字段，完整账号每次读取都重新校验字段权限。
        var result=command(scope,"cash_account.create",key,input,201,()->{
            var created=accounts.create(scope,input);var safe=new java.util.LinkedHashMap<>(created);safe.remove("accountNo");return safe;
        });
        return ResponseEntity.status(201).body(ApiResponse.created(result));
    }

    @PutMapping("/cash-accounts/{id}")
    ApiResponse<Map<String,Object>> editAccount(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody CashModels.Account input) {
        return ApiResponse.success(accounts.update(access.requireCompany(req,"cash_account:update"),id,input));
    }

    @PostMapping("/cash-accounts/{id}/{action:enable|disable}")
    ApiResponse<Map<String,Object>> accountStatus(HttpServletRequest req,@PathVariable long id,@PathVariable String action,
            @RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody PartnerModels.Version input) {
        var scope=access.requireCompany(req,"cash_account:update");
        var result=command(scope,"cash_account."+action,key,List.of(id,input.version()),200,()->{
            var after=accounts.status(scope,id,action,input.version());var safe=new java.util.LinkedHashMap<>(after);safe.remove("accountNo");return safe;
        });
        return ApiResponse.success(result);
    }

    @PostMapping("/cash-accounts/{id}/set-default")
    ApiResponse<Map<String,Object>> defaultAccount(HttpServletRequest req,@PathVariable long id,
            @RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody CashModels.DefaultAccount input) {
        var scope=access.requireCompany(req,"cash_account:update");
        return ApiResponse.success(command(scope,"cash_account.set_default",key,List.of(id,input),200,()->{
            var result=new java.util.LinkedHashMap<>(accounts.setDefault(scope,id,input));
            result.remove("accountNo");return result;
        }));
    }

    @GetMapping("/income-expense-items")
    ApiResponse<Map<String,Object>> items(HttpServletRequest req,@RequestParam(defaultValue="") String keyword,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit) {
        return ApiResponse.success(reference.items(access.requireTenant(req,"income_expense_item:view"),keyword,cursor,limit));
    }

    @PostMapping("/income-expense-items")
    ResponseEntity<ApiResponse<Map<String,Object>>> addItem(HttpServletRequest req,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody CashModels.Item input) {
        var scope=access.requireTenant(req,"income_expense_item:create");
        return ResponseEntity.status(201).body(ApiResponse.created(command(scope,"income_expense_item.create",key,input,201,()->reference.saveItem(scope,null,input))));
    }

    @PutMapping("/income-expense-items/{id}")
    ApiResponse<Map<String,Object>> editItem(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody CashModels.Item input) {
        return ApiResponse.success(reference.saveItem(access.requireTenant(req,"income_expense_item:update"),id,input));
    }

    @GetMapping("/accounting-periods")
    ApiResponse<Map<String,Object>> periods(HttpServletRequest req,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit) {
        return ApiResponse.success(reference.periods(access.requireCompany(req,"period:view"),cursor,limit));
    }

    @PostMapping("/accounting-periods")
    ResponseEntity<ApiResponse<Map<String,Object>>> open(HttpServletRequest req,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody CashModels.Period input) {
        var scope=access.requireCompany(req,"period:open");
        return ResponseEntity.status(201).body(ApiResponse.created(command(scope,"period.open",key,input,201,()->reference.openPeriod(scope,input.period()))));
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private Map<String,Object> command(CompanyAccess scope,String operation,String key,Object payload,int status,Supplier<Map<String,Object>> work) {
        return commands.execute(scope,operation,key,payload,status,work,(Class)Map.class);
    }
}
